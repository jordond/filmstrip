package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.color.sdrSignalFromNits
import dev.jordond.filmstrip.color.signalFromTenBitCodes
import dev.jordond.filmstrip.color.tenBitCodesFromSignal
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.encodesHdr
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.hdr.toneMappedSdr
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import dev.jordond.filmstrip.webcodecs.internal.BrowserProber
import dev.jordond.filmstrip.webcodecs.internal.preview.toBrowserPreview
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import dev.jordond.filmstrip.transform.internal.hdr.Rgb as SignalRgb

/**
 * HDR clips exported to SDR through the browser's tone map stage, read back against the shared tone curve.
 *
 * The clips are ten-bit stripes, each one flat patch, so a read in the middle of a stripe sees one signal. PQ has greys
 * at 200, 500 and 800 cd/m2, on the knee's straight part and in each Hermite piece, and a colour with 2000 cd/m2 green
 * past the mastering peak. HLG has greys at mid signals. Both have an off-grey colour whose channels take one gain and
 * a colour with no green. Every patch is also read against the hard clip a browser draws an HDR frame with, which the
 * cell has to tell apart from the curve.
 */
class BrowserToneMapMediaTest {
  @Test
  fun aPqClipLandsOnTheSharedToneCurve() =
    runTest {
      if (!webCodecsFilmstrip().encodesHdr()) return@runTest

      val clip = patchClip(HdrTransfer.Pq, PQ_PATCHES)
      val success = exportOf(single(clip), MediaSink.Uri(""), TONE_MAP)

      val video = success.info.video.shouldNotBeNull()
      video.hdrTransfer.shouldBeNull()
      video.colorSpace shouldBe ColorSpace.Bt709
      assertOnCurve(HdrTransfer.Pq, PQ_PATCHES, decodeFrames(outputOf(success))[MIDDLE])
    }

  @Test
  fun anHlgClipLandsOnTheSharedToneCurve() =
    runTest {
      if (!webCodecsFilmstrip().encodesHdr()) return@runTest

      val clip = patchClip(HdrTransfer.Hlg, HLG_PATCHES)
      val success = exportOf(single(clip), MediaSink.Uri(""), TONE_MAP)

      assertOnCurve(HdrTransfer.Hlg, HLG_PATCHES, decodeFrames(outputOf(success))[MIDDLE])
    }

  // No one transfer describes a PQ clip beside an SDR one, so Auto brings both down and says so. The PQ clip runs the
  // curve and the SDR clip is drawn as it is, each by its own transfer in the one export.
  @Test
  fun aPqClipBesideAnSdrOneToneMapsByItsOwnTransfer() =
    runTest {
      if (!webCodecsFilmstrip().encodesHdr()) return@runTest

      val hdr = patchClip(HdrTransfer.Pq, PQ_PATCHES)
      val sdr = MediaSource.Bytes(makeClip { _, _ -> Rgb.Green })
      val composition =
        EditComposition(tracks = listOf(Track(listOf(Clip(hdr), Clip(sdr)))), audio = AudioSpec.Remove)
      val success = exportOf(composition, MediaSink.Uri(""), AUTO)

      success.adjustments.map { it.kind } shouldBe listOf(AdjustmentKind.HdrToneMapped)
      val frames = decodeFrames(outputOf(success))
      assertOnCurve(HdrTransfer.Pq, PQ_PATCHES, frames[MIDDLE])
      val expected = decodeFrames(sdr)[MIDDLE].at(x = 0.5, y = 0.5)
      val read = frames[CLIP_FRAMES + MIDDLE].at(x = 0.5, y = 0.5)
      withClue("the SDR clip wrote $read where it reads $expected") { read.isNear(expected, CELL) shouldBe true }
    }

  // The preview draws through the compositor the export does, so its frame is the curve's answer before any encoder
  // has touched it, and the export's frame at the same time is that frame after one.
  @Test
  fun thePreviewDrawsTheFrameTheExportWrites() =
    runTest {
      if (!webCodecsFilmstrip().encodesHdr()) return@runTest

      val composition = single(patchClip(HdrTransfer.Pq, PQ_PATCHES))
      val preview = resolve(composition, TONE_MAP).toBrowserPreview(composition)
      val exported = decodeFrames(outputOf(exportOf(composition, MediaSink.Uri(""), TONE_MAP)))[MIDDLE]

      try {
        val frame = preview.frameAt((MIDDLE * MILLIS_PER_FRAME).milliseconds).shouldNotBeNull()
        PQ_PATCHES.indices.forEach { index ->
          val y = (index + 0.5) / PQ_PATCHES.size
          val expected = curveCodes(HdrTransfer.Pq, PQ_PATCHES[index])
          val drawn = frame.at(x = 0.5, y = y)
          withClue("patch $index previewed as $drawn where the curve puts $expected") {
            drawn.isNear(expected, PREVIEW_CELL) shouldBe true
          }
          val written = exported.at(x = 0.5, y = y)
          withClue("patch $index previewed as $drawn and exported as $written") {
            written.isNear(drawn, CELL) shouldBe true
          }
        }
      } finally {
        preview.release()
      }
    }

  // A player's frame spec asks for nothing, so the grade reaches the lowering on its own.
  @Test
  fun aPreviewHandedTheGradeOfItsExportDrawsTheCurveOnAPlainFrameSpec() =
    runTest {
      val composition = single(patchClip(HdrTransfer.Pq, PQ_PATCHES))
      val preview = resolve(composition, ExportSpec(), ResolvedHdr.ToneMap).toBrowserPreview(composition)

      try {
        val frame = preview.frameAt((MIDDLE * MILLIS_PER_FRAME).milliseconds).shouldNotBeNull()
        PQ_PATCHES.indices.forEach { index ->
          val expected = curveCodes(HdrTransfer.Pq, PQ_PATCHES[index])
          val drawn = frame.at(x = 0.5, y = (index + 0.5) / PQ_PATCHES.size)
          withClue("patch $index previewed as $drawn where the curve puts $expected") {
            drawn.isNear(expected, PREVIEW_CELL) shouldBe true
          }
        }
      } finally {
        preview.release()
      }
    }

  // The pass tone maps into a target the size of the frame it was handed, so a source larger than the output grows it
  // before the composite scales it down.
  @Test
  fun aPqClipLargerThanTheOutputLandsOnTheSharedToneCurve() =
    runTest {
      if (!webCodecsFilmstrip().encodesHdr()) return@runTest

      val clip = patchClip(HdrTransfer.Pq, PQ_PATCHES, scale = 2)
      val composition =
        EditComposition(
          tracks = listOf(Track(listOf(Clip(clip)))),
          effects = listOf(Scale(PATCH_ROWS * PQ_PATCHES.size)),
          audio = AudioSpec.Remove,
        )
      val frame = decodeFrames(outputOf(exportOf(composition, MediaSink.Uri(""), TONE_MAP)))[MIDDLE]

      Size(frame.width, frame.height) shouldBe Size(PATCH_WIDTH, PATCH_ROWS * PQ_PATCHES.size)
      assertOnCurve(HdrTransfer.Pq, PQ_PATCHES, frame)
    }

  // The second clip is twice the first's size on both sides, so it fills the frame the first one set, and the pass
  // moves its target to each clip's size in turn.
  @Test
  fun twoPqClipsOfDifferentSizesBothLandOnTheSharedToneCurve() =
    runTest {
      if (!webCodecsFilmstrip().encodesHdr()) return@runTest

      val small = patchClip(HdrTransfer.Pq, PQ_PATCHES)
      val large = patchClip(HdrTransfer.Pq, PQ_PATCHES, scale = 2)
      val composition =
        EditComposition(tracks = listOf(Track(listOf(Clip(small), Clip(large), Clip(small)))), audio = AudioSpec.Remove)
      val frames = decodeFrames(outputOf(exportOf(composition, MediaSink.Uri(""), TONE_MAP)))

      for (clip in 0 until 3) {
        withClue("clip $clip") { assertOnCurve(HdrTransfer.Pq, PQ_PATCHES, frames[clip * CLIP_FRAMES + MIDDLE]) }
      }
    }

  // The compositor is built for one grade, so a swap onto a plan that tone maps where the standing one kept the grade
  // is refused and the caller rebuilds. The next plan copies every field of the standing one but the grade, so the
  // grade alone is what the swap refuses on.
  @Test
  fun aPreviewRefusesASwapFromAKeptGradeToAToneMap() =
    runTest {
      if (!webCodecsFilmstrip().encodesHdr()) return@runTest

      val composition = single(patchClip(HdrTransfer.Pq, PQ_PATCHES))
      val kept = resolve(composition, KEEP)
      kept.hdr shouldBe ResolvedHdr.Keep
      val toneMapped =
        ResolvedComposition(
          tracks = kept.tracks,
          compositionEffects = kept.compositionEffects,
          output = kept.output,
          layoutSize = kept.layoutSize,
          fill = kept.fill,
          duration = kept.duration,
          hdr = ResolvedHdr.ToneMap,
          hdrTransfer = kept.hdrTransfer,
          path = kept.path,
          audio = kept.audio,
          adjustments = kept.adjustments,
          encoderName = kept.encoderName,
        )
      val preview = kept.toBrowserPreview(composition)

      try {
        preview.updateParameters(toneMapped, composition) shouldBe false
      } finally {
        preview.release()
      }
    }

  private fun assertOnCurve(
    transfer: HdrTransfer,
    patches: List<FloatArray>,
    frame: OutputFrame,
  ) {
    patches.forEachIndexed { index, patch ->
      val expected = curveCodes(transfer, patch)
      val clipped = hardClipCodes(transfer, patch)
      val read = frame.at(x = 0.5, y = (index + 0.5) / patches.size)
      withClue("$transfer patch $index wrote $read where the curve puts $expected") {
        read.isNear(expected, CELL) shouldBe true
      }
      withClue("$transfer patch $index hard clips to $clipped, inside the cell around $expected") {
        clipped.isNear(expected, CELL) shouldBe false
      }
    }
  }

  // One stripe of PATCH_ROWS rows per patch, each painted in the light that patch carries, with both sides times scale.
  private suspend fun patchClip(
    transfer: HdrTransfer,
    patches: List<FloatArray>,
    scale: Int = 1,
  ): MediaSource =
    MediaSource.Bytes(
      makeHdrClip(
        transfer,
        width = PATCH_WIDTH * scale,
        height = PATCH_ROWS * scale * patches.size,
        frames = CLIP_FRAMES,
      ) { y ->
        nitsOf(transfer, patches[y / (PATCH_ROWS * scale)])
      },
    )

  private suspend fun resolve(
    composition: EditComposition,
    spec: ExportSpec,
    grade: ResolvedHdr? = null,
  ): ResolvedComposition =
    webCodecsExportEngine(
      components = ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build(),
      prober = BrowserProber(),
    ).resolve(composition, spec, grade = grade)
      .shouldBeInstanceOf<ResolveResult.Resolved>()
      .composition

  private fun single(source: MediaSource): EditComposition =
    EditComposition(tracks = listOf(Track(listOf(Clip(source)))), audio = AudioSpec.Remove)

  // The signal the fixture really stores for a patch, which is its codes read back, through the shared curve.
  private fun curveCodes(
    transfer: HdrTransfer,
    patch: FloatArray,
  ): Rgb {
    val stored = storedSignal(transfer, patch)
    val sdr = toneMappedSdr(transfer, SignalRgb(stored[0].toDouble(), stored[1].toDouble(), stored[2].toDouble()))
    return Rgb(code(sdr.r), code(sdr.g), code(sdr.b))
  }

  // Display light clipped at reference white through the SDR display curve, with no tone curve and no gamut change.
  private fun hardClipCodes(
    transfer: HdrTransfer,
    patch: FloatArray,
  ): Rgb {
    val stored = storedSignal(transfer, patch)
    val clipped = List(3) { sdrSignalFromNits(transfer.displayNitsFromSignal(stored[it]).toDouble()).coerceAtMost(1.0) }
    return Rgb(code(clipped[0]), code(clipped[1]), code(clipped[2]))
  }

  private fun storedSignal(
    transfer: HdrTransfer,
    patch: FloatArray,
  ): FloatArray {
    val (luma, cb, cr) = tenBitCodesFromSignal(transfer.pictureSignalOf(nitsOf(transfer, patch)))
    return signalFromTenBitCodes(luma, cb, cr)
  }

  // A PQ patch is written as light and an HLG one as its signal, the way each range is easiest to read the middle of.
  private fun nitsOf(
    transfer: HdrTransfer,
    patch: FloatArray,
  ): FloatArray =
    when (transfer) {
      HdrTransfer.Pq -> patch
      HdrTransfer.Hlg -> FloatArray(3) { transfer.displayNitsFromSignal(patch[it]) }
    }

  private fun code(signal: Double): Int = (signal * MAX_CODE).roundToInt()

  private fun Rgb.isNear(
    other: Rgb,
    cell: Int,
  ): Boolean = abs(red - other.red) <= cell && abs(green - other.green) <= cell && abs(blue - other.blue) <= cell

  private companion object {
    const val PATCH_ROWS = 32
    const val PATCH_WIDTH = 64
    const val CLIP_FRAMES = 12
    const val MIDDLE = CLIP_FRAMES / 2
    const val MILLIS_PER_FRAME = 1_000.0 / 30
    const val MAX_CODE = 255.0

    val TONE_MAP =
      ExportSpec(
        bitrate = MEASURING_BITRATE,
        videoCodec = VideoCodec.H264,
        audioCodec = AudioCodec.None,
        hdr = HdrMode.ToneMapToSdr,
      )
    val KEEP = ExportSpec(bitrate = MEASURING_BITRATE, audioCodec = AudioCodec.None, hdr = HdrMode.KeepHdr)
    val AUTO = ExportSpec(bitrate = MEASURING_BITRATE, videoCodec = VideoCodec.H264, audioCodec = AudioCodec.None)

    val PQ_PATCHES =
      listOf(
        floatArrayOf(200f, 200f, 200f),
        floatArrayOf(500f, 500f, 500f),
        floatArrayOf(800f, 800f, 800f),
        floatArrayOf(300f, 500f, 100f),
        floatArrayOf(1500f, 2000f, 1200f),
        floatArrayOf(300f, 0f, 100f),
      )
    val HLG_PATCHES =
      listOf(
        floatArrayOf(0.5f, 0.5f, 0.5f),
        floatArrayOf(0.65f, 0.65f, 0.65f),
        floatArrayOf(0.75f, 0.75f, 0.75f),
        floatArrayOf(0.7f, 0.55f, 0.3f),
        floatArrayOf(0.6f, 0f, 0.4f),
      )

    // Eight-bit full range codes, after one H.264 encode. On Chrome 156 the widest miss is two codes, on the 800 nit
    // grey, the HLG off-grey and the SDR clip. The hard clip's closest patch misses by 59.
    const val CELL = 3

    // The preview reads the canvas the curve drew into before any encoder. On Chrome 156 it lands on the curve's own
    // codes on every patch, and the one code left covers a GPU whose pow rounds the other way.
    const val PREVIEW_CELL = 1
  }
}

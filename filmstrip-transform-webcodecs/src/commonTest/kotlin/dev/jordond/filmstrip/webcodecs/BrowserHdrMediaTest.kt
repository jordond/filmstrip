@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.color.authoredHdrNits
import dev.jordond.filmstrip.color.blurPlane
import dev.jordond.filmstrip.color.hlgNitsFromScene
import dev.jordond.filmstrip.color.hlgSceneFromDisplayNits
import dev.jordond.filmstrip.color.linearDimGain
import dev.jordond.filmstrip.color.nitsFromSdrSignal
import dev.jordond.filmstrip.color.sceneFromHlgSignal
import dev.jordond.filmstrip.color.sdrSignalFromHlgScene
import dev.jordond.filmstrip.color.sdrSignalFromNits
import dev.jordond.filmstrip.color.signalFromNits
import dev.jordond.filmstrip.color.signalFromTenBitCodes
import dev.jordond.filmstrip.color.tenBitCodesFromSignal
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.color.Contrast
import dev.jordond.filmstrip.effects.color.Saturation
import dev.jordond.filmstrip.effects.color.colorMatrixOf
import dev.jordond.filmstrip.effects.color.transformNits
import dev.jordond.filmstrip.effects.geometry.CropRect
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.encodesHdr
import dev.jordond.filmstrip.transform.internal.geometry.blurPlan
import dev.jordond.filmstrip.webcodecs.internal.SourceReader
import dev.jordond.filmstrip.webcodecs.internal.interop.asString
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit

/**
 * The ten-bit path end to end in a real browser: a graded clip is planned, composited through the
 * float pipeline, packed into `I420P10` and encoded as VP9 Profile 2, then demuxed back by a reader
 * that never saw the encoder and read as code values.
 *
 * Every expected figure comes off the shared functions rather than off a number written here, so a
 * change to what a transfer or a color matrix means moves the assertion with it. The fixtures sit
 * in the middle of the range rather than at black and peak, because the ends agree under readings
 * that part company everywhere else.
 */
class BrowserHdrMediaTest {
  @Test
  fun aCroppedGradeKeepsItsCodesAndItsTag() =
    runTest {
      if (!webCodecsFilmstrip().encodesHdr()) return@runTest

      val bytes = makeHdrClip(HdrTransfer.Pq) { y -> if (y < HALF) DIM else BRIGHT }
      val success = hdrExportOf(cropped(bytes), HdrTransfer.Pq)

      val video = success.info.video.shouldNotBeNull()
      video.hdrTransfer shouldBe HdrTransfer.Pq
      video.colorSpace shouldBe ColorSpace.Bt2020
      withClue("the HDR ladder pins the encode to VP9") { video.codec.kind shouldBe CodecKind.Vp9 }

      val output = outputOf(success)
      assertTag(output, "pq")

      val frame = decodeTenBitFrames(output, HdrTransfer.Pq).firstOrNull().shouldNotBeNull()
      val (expectedLuma, expectedCb, expectedCr) = tenBitCodesFromSignal(HdrTransfer.Pq.pictureSignalOf(DIM))
      for (y in listOf(0.2, 0.5, 0.8)) {
        assertNear(expectedLuma, frame.lumaAt(x = 0.5, y = y), LUMA_TOLERANCE, "luma at $y")
        val (cb, cr) = frame.chromaAt(x = 0.5, y = y)
        assertNear(expectedCb, cb, CHROMA_TOLERANCE, "Cb at $y")
        assertNear(expectedCr, cr, CHROMA_TOLERANCE, "Cr at $y")
      }
    }

  @Test
  fun contrastOnAPqGradeLandsWhereTheSharedReferenceSaysItShould() =
    runTest { assertGraded(HdrTransfer.Pq, Contrast(STRETCHED)) }

  @Test
  fun saturationOnAPqGradeMixesTheChannelsTheSharedWay() = runTest { assertGraded(HdrTransfer.Pq, Saturation(MUTED)) }

  // On HLG unpack runs the luminance driven opto-optical transfer to reach display light, and the
  // grade reads the scene light back out of it, which is the light media3 and ffmpeg grade HLG on,
  // so the same reference holds. The tolerance is wider because the transfer runs twice more on the
  // way in and out than PQ's does.
  @Test
  fun contrastOnAnHlgGradeLandsWhereTheSharedReferenceSaysItShould() =
    runTest { assertGraded(HdrTransfer.Hlg, Contrast(STRETCHED), tolerance = HLG_NITS_TOLERANCE) }

  @Test
  fun saturationOnAnHlgGradeMixesTheChannelsTheSharedWay() =
    runTest { assertGraded(HdrTransfer.Hlg, Saturation(MUTED), tolerance = HLG_NITS_TOLERANCE) }

  // The fill is authored as sRGB and the bar has to carry the same light every other backend puts
  // there. On HLG the pack pass runs the luminance driven inverse transfer the shared answer runs,
  // and a purple fill is colored enough to tell it from a per channel one.
  @Test
  fun aLetterboxBarCarriesTheFillsOwnSignalOnBothTransfers() =
    runTest {
      if (!webCodecsFilmstrip().encodesHdr()) return@runTest

      HdrTransfer.entries.forEach { transfer ->
        val success = hdrExportOf(letterboxed(transfer, Fill.Solid(PURPLE_ARGB)), transfer)
        val frame =
          withClue("the letterboxed export decoded no frames on $transfer") {
            decodeTenBitFrames(outputOf(success), transfer).lastOrNull().shouldNotBeNull()
          }

        val expected = transfer.signalFromNits(authoredHdrNits(PURPLE_ARGB))
        val (expectedLuma, expectedCb, expectedCr) = tenBitCodesFromSignal(expected)
        val (cb, cr) = frame.chromaAt(x = 0.5, y = BAR)
        assertNear(expectedLuma, frame.lumaAt(x = 0.5, y = BAR), LUMA_TOLERANCE, "$transfer bar luma")
        assertNear(expectedCb, cb, CHROMA_TOLERANCE, "$transfer bar Cb")
        assertNear(expectedCr, cr, CHROMA_TOLERANCE, "$transfer bar Cr")
      }
    }

  // A gap in front of a late primary is the fill and nothing else, so on a kept grade it carries the
  // signal a bar does, on either transfer. It is read in the middle of the gap, and the clip in the
  // middle of its own run.
  @Test
  fun aLateStartCarriesTheFillsOwnSignalThroughItsGapOnBothTransfers() =
    runTest {
      if (!webCodecsFilmstrip().encodesHdr()) return@runTest

      HdrTransfer.entries.forEach { transfer ->
        val bytes = makeHdrClip(transfer) { BRIGHT }
        val composition =
          Composition(
            tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(bytes))), start = LATE_START)),
            audio = AudioSpec.Remove,
            fill = Fill.Solid(PURPLE_ARGB),
          )
        val frames = decodeTenBitFrames(outputOf(hdrExportOf(composition, transfer)), transfer)
        val startUs = LATE_START.toDouble(DurationUnit.MICROSECONDS)

        val gap = frames.minBy { abs(it.timestampUs - startUs / 2) }
        withClue("the $transfer file has no frame in front of ${startUs}us") {
          (gap.timestampUs < startUs) shouldBe true
        }
        val (fillLuma, fillCb, fillCr) = tenBitCodesFromSignal(transfer.signalFromNits(authoredHdrNits(PURPLE_ARGB)))
        val (gapCb, gapCr) = gap.chromaAt(x = 0.5, y = 0.5)
        assertNear(fillLuma, gap.lumaAt(x = 0.5, y = 0.5), LUMA_TOLERANCE, "$transfer gap luma")
        assertNear(fillCb, gapCb, CHROMA_TOLERANCE, "$transfer gap Cb")
        assertNear(fillCr, gapCr, CHROMA_TOLERANCE, "$transfer gap Cr")

        val run = frames.filter { it.timestampUs >= startUs }
        val clip = run[run.size / 2]
        val clipLuma = tenBitCodesFromSignal(transfer.pictureSignalOf(BRIGHT))[0]
        assertNear(clipLuma, clip.lumaAt(x = 0.5, y = 0.5), LUMA_TOLERANCE, "$transfer clip luma")
      }
    }

  // A dim is written against an encoded value, which the blur holds on a grade, so it is a gain on the SDR signal and
  // takes the display's own curve of light. Asserted directionally: the blur spreads the clip's own pixels into
  // the bar, so what is there is the clip dimmed rather than any one figure this test could name.
  @Test
  fun aBlurredFillDimsTheBackgroundOfAGrade() =
    runTest {
      if (!webCodecsFilmstrip().encodesHdr()) return@runTest

      val bright = barLightOf(Fill.Blurred(dim = 0f))
      val dimmed = barLightOf(Fill.Blurred(dim = HALF_DIM))

      withClue("the dimmed bar carried $dimmed nits and the undimmed one $bright") { (dimmed < bright) shouldBe true }
      // The gain lands on light, so a half dim takes far more than half of it away. A gain read
      // straight off the dim would leave the bar above this.
      withClue("the bar dimmed to $dimmed nits from $bright, which is short of the ${linearDimGain(HALF_DIM)} gain") {
        (dimmed < bright * linearDimGain(HALF_DIM) * DIM_HEADROOM) shouldBe true
      }
    }

  // A source whose grade this backend cannot read is a different thing from a device with no HDR
  // encoder, and the ten-bit fixtures are exactly the sources it can read.
  @Test
  fun aTenBitSourceReadsItsPlanesAndAnEightBitOneDoesNot() =
    runTest {
      val graded = MediaSource.Bytes(makeHdrClip(HdrTransfer.Pq) { BRIGHT })
      val plain = MediaSource.Bytes(makeClip())

      withClue("a VP9 Profile 2 fixture decoded to something other than $TEN_BIT") { readsTenBit(graded) shouldBe true }
      withClue("an eight-bit H.264 fixture claimed to hand out ten-bit planes") { !readsTenBit(plain) shouldBe true }
    }

  /**
   * Exports [transfer] fixture graded by [spec] and checks the centre pixel against
   * [transformNits], which is the reference every backend keeping a grade is measured against.
   */
  private suspend fun assertGraded(
    transfer: HdrTransfer,
    spec: EffectSpec,
    tolerance: Float = NITS_TOLERANCE,
  ) {
    if (!webCodecsFilmstrip().encodesHdr()) return

    val bytes = makeHdrClip(transfer) { COLORED }
    val composition =
      Composition(
        tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(bytes), effects = listOf(spec))))),
        audio = AudioSpec.Remove,
      )
    val success = hdrExportOf(composition, transfer)

    val frame = decodeTenBitFrames(outputOf(success), transfer).firstOrNull().shouldNotBeNull()
    val matrix = checkNotNull(colorMatrixOf(spec))
    val expected = matrix.transformNits(COLORED[0], COLORED[1], COLORED[2], transfer)
    val measured = frame.nitsAt(x = 0.5, y = 0.5)

    repeat(3) { channel ->
      assertRelative(
        expected[channel],
        measured[channel],
        tolerance,
        "$spec on $transfer, channel $channel, whole pixel ${measured.toList()} against ${expected.toList()}",
      )
    }
  }

  /**
   * The light the letterbox bar carries under [fill], which the blur has spread the clip into.
   */
  private suspend fun barLightOf(fill: Fill): Float {
    val success = hdrExportOf(letterboxed(HdrTransfer.Pq, fill), HdrTransfer.Pq)
    val frame =
      withClue("the blurred export decoded no frames") {
        decodeTenBitFrames(outputOf(success), HdrTransfer.Pq).lastOrNull().shouldNotBeNull()
      }
    return frame.nitsAt(x = 0.5, y = BAR)[1]
  }

  private fun cropped(bytes: ByteArray): Composition =
    Composition(
      tracks =
        listOf(
          Track(
            listOf(
              Clip(
                MediaSource.Bytes(bytes),
                effects = listOf(CropRect(NormalizedRect(0f, 0f, 1f, 0.5f))),
              ),
            ),
          ),
        ),
      audio = AudioSpec.Remove,
    )

  /**
   * A square clip that sets the output frame, followed by a half-height one that letterboxes into
   * it, so the bars are the fill's and nothing else's.
   */
  private suspend fun letterboxed(
    transfer: HdrTransfer,
    fill: Fill,
  ): Composition {
    val filler = makeHdrClip(transfer) { BRIGHT }
    val wide = makeHdrClip(transfer, height = HALF, frames = SHORT) { COLORED }
    return Composition(
      tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(filler)), Clip(MediaSource.Bytes(wide))))),
      audio = AudioSpec.Remove,
      fill = fill,
    )
  }

  private suspend fun assertTag(
    source: MediaSource,
    transfer: String,
  ) {
    val reader = checkNotNull(SourceReader.of(source))
    try {
      val colorSpace = checkNotNull(reader.videoTrack()).getColorSpace().await()
      colorSpace.primaries?.asString() shouldBe "bt2020"
      colorSpace.transfer?.asString() shouldBe transfer
      colorSpace.matrix?.asString() shouldBe "bt2020-ncl"
    } finally {
      reader.close()
    }
  }

  private suspend fun readsTenBit(source: MediaSource): Boolean {
    val reader = checkNotNull(SourceReader.of(source))
    return try {
      reader.readsTenBit()
    } finally {
      reader.close()
    }
  }

  private fun assertNear(
    expected: Int,
    actual: Int,
    tolerance: Int,
    what: String,
  ) {
    withClue("$what was $actual, and the shared functions say $expected") {
      (abs(expected - actual) <= tolerance) shouldBe true
    }
  }

  private fun assertRelative(
    expected: Float,
    actual: Float,
    tolerance: Float,
    what: String,
  ) {
    val allowed = maxOf(abs(expected) * tolerance, NITS_FLOOR)
    withClue("$what: expected $expected but was $actual") { (abs(expected - actual) <= allowed) shouldBe true }
  }

  internal companion object {
    const val HALF = 32

    const val SHORT = 6
    const val BAR = 0.05
    const val STRETCHED = 1.5f
    const val MUTED = 0.4f
    const val HALF_DIM = 0.5f
    const val PURPLE_ARGB = 0xFFA060C8.toInt()

    // Six slots of gap at the fixture's thirty frames a second.
    val LATE_START = 200.milliseconds

    // Middle of the range on both transfers, and off-white on every channel, so a matrix that mixes
    // channels has something to mix and a gain above one has headroom to move into.
    val DIM = floatArrayOf(28f, 34f, 22f)
    val BRIGHT = floatArrayOf(240f, 205f, 275f)
    val COLORED = floatArrayOf(120f, 180f, 75f)

    // A 4:2:0 chroma plane is one sample per two by two block, so a code there carries four
    // pixels' worth of rounding on top of the encoder's own.
    const val LUMA_TOLERANCE = 2
    const val CHROMA_TOLERANCE = 4

    const val NITS_TOLERANCE = 0.04f
    const val HLG_NITS_TOLERANCE = 0.06f

    // Below this the transfer's own quantisation dominates and a relative bound says nothing.
    const val NITS_FLOOR = 1.5f

    // The blur spreads the clip's own light into the bar, so the dimmed bar is compared against the
    // gain with room for what the spreading itself moved.
    const val DIM_HEADROOM = 1.6f
  }
}

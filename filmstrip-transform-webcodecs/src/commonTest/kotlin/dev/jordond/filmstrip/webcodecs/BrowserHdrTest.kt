@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.color.Contrast
import dev.jordond.filmstrip.effects.color.Saturation
import dev.jordond.filmstrip.effects.color.colorMatrixOf
import dev.jordond.filmstrip.effects.color.transformNits
import dev.jordond.filmstrip.effects.geometry.CropRect
import dev.jordond.filmstrip.export.AudioCodec
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.authoredHdrNits
import dev.jordond.filmstrip.media.hlgNitsFromSignal
import dev.jordond.filmstrip.media.linearDimGain
import dev.jordond.filmstrip.media.signalFromNits
import dev.jordond.filmstrip.media.tenBitCodesFromSignal
import dev.jordond.filmstrip.test.encodesHdr
import dev.jordond.filmstrip.webcodecs.internal.SourceReader
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit

/**
 * The ten-bit path end to end in a real browser: a graded clip is planned, composited through the
 * float pipeline, packed into `I420P10` and encoded as VP9 Profile 2, then demuxed back by a reader
 * that never saw the encoder and read as code values.
 *
 * Every expected figure comes off the shared functions rather than off a number written here, so a
 * change to what a transfer or a colour matrix means moves the assertion with it. The fixtures sit
 * in the middle of the range rather than at black and peak, because the ends agree under readings
 * that part company everywhere else.
 */
class BrowserHdrTest {
  @Test
  fun aCroppedGradeKeepsItsCodesAndItsTag() =
    runTest {
      if (!filmstrip().encodesHdr()) return@runTest

      val bytes = makeHdrClip(HdrTransfer.Pq) { y -> if (y < HALF) DIM else BRIGHT }
      val success = exportOf(cropped(bytes), HdrTransfer.Pq)

      val video = assertNotNull(success.info.video)
      assertEquals(HdrTransfer.Pq, video.hdrTransfer)
      assertEquals(ColorSpace.Bt2020, video.colorSpace)
      assertEquals(CodecKind.Vp9, video.codec.kind, "the HDR ladder pins the encode to VP9")

      val output = outputOf(success)
      assertTag(output, "pq")

      val frame = assertNotNull(decodeTenBitFrames(output, HdrTransfer.Pq).firstOrNull())
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
  // and a purple fill is coloured enough to tell it from a per channel one.
  @Test
  fun aLetterboxBarCarriesTheFillsOwnSignalOnBothTransfers() =
    runTest {
      if (!filmstrip().encodesHdr()) return@runTest

      HdrTransfer.entries.forEach { transfer ->
        val success = exportOf(letterboxed(transfer, Fill.Solid(PURPLE_ARGB)), transfer)
        val frame =
          assertNotNull(
            decodeTenBitFrames(outputOf(success), transfer).lastOrNull(),
            "the letterboxed export decoded no frames on $transfer",
          )

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
      if (!filmstrip().encodesHdr()) return@runTest

      HdrTransfer.entries.forEach { transfer ->
        val bytes = makeHdrClip(transfer) { BRIGHT }
        val composition =
          EditComposition(
            tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(bytes))), start = LATE_START)),
            audio = AudioSpec.Remove,
            fill = Fill.Solid(PURPLE_ARGB),
          )
        val frames = decodeTenBitFrames(outputOf(exportOf(composition, transfer)), transfer)
        val startUs = LATE_START.toDouble(DurationUnit.MICROSECONDS)

        val gap = frames.minBy { abs(it.timestampUs - startUs / 2) }
        assertTrue(gap.timestampUs < startUs, "the $transfer file has no frame in front of ${startUs}us")
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

  // A dim is written against an encoded value and the grade holds light, so the gain is raised by
  // the display's own curve. Asserted directionally: the blur spreads the clip's own pixels into
  // the bar, so what is there is the clip dimmed rather than any one figure this test could name.
  @Test
  fun aBlurredFillDimsTheBackgroundOfAGrade() =
    runTest {
      if (!filmstrip().encodesHdr()) return@runTest

      val bright = barLightOf(Fill.Blurred(dim = 0f))
      val dimmed = barLightOf(Fill.Blurred(dim = HALF_DIM))

      assertTrue(dimmed < bright, "the dimmed bar carried $dimmed nits and the undimmed one $bright")
      // The gain lands on light, so a half dim takes far more than half of it away. A gain read
      // straight off the dim would leave the bar above this.
      assertTrue(
        dimmed < bright * linearDimGain(HALF_DIM) * DIM_HEADROOM,
        "the bar dimmed to $dimmed nits from $bright, which is short of the ${linearDimGain(HALF_DIM)} gain",
      )
    }

  // A blur averages the buffer's light, which on HLG is the luminance driven display light unpack
  // decodes to. A flat colour blurs to itself, and two row stripes of magenta and green blurred far
  // past their period land on the mean of their two lights. The per channel transfer puts that mean
  // somewhere else, which is checked to sit more than twice the tolerance away before the read is held to it.
  @Test
  fun aBlurredFillOnHlgMixesItsColoursInDisplayLight() =
    runTest {
      if (!filmstrip().encodesHdr()) return@runTest

      val flat = pillarBarCodesOf { MAGENTA }
      assertCodes(hlgBlurCodesOf(MAGENTA, MAGENTA), flat, "the flat magenta bar")

      val striped = pillarBarCodesOf { y -> if ((y / 2) % 2 == 0) MAGENTA else GREEN }
      val mixed = hlgBlurCodesOf(MAGENTA, GREEN)
      val perChannel = perChannelBlurCodesOf(MAGENTA, GREEN)
      assertTrue(
        abs(mixed[0] - perChannel[0]) > 2 * LUMA_TOLERANCE ||
          (1..2).any { abs(mixed[it] - perChannel[it]) > 2 * CHROMA_TOLERANCE },
        "the stripes mix to ${mixed.toList()} either way, so they pin nothing",
      )
      assertCodes(mixed, striped, "the striped bar, which a per channel mix puts at ${perChannel.toList()},")
    }

  // A source whose grade this backend cannot read is a different thing from a device with no HDR
  // encoder, and the ten-bit fixtures are exactly the sources it can read.
  @Test
  fun aTenBitSourceReadsItsPlanesAndAnEightBitOneDoesNot() =
    runTest {
      val graded = MediaSource.Bytes(makeHdrClip(HdrTransfer.Pq) { BRIGHT })
      val plain = MediaSource.Bytes(makeClip())

      assertTrue(readsTenBit(graded), "a VP9 Profile 2 fixture decoded to something other than $TEN_BIT")
      assertTrue(!readsTenBit(plain), "an eight-bit H.264 fixture claimed to hand out ten-bit planes")
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
    if (!filmstrip().encodesHdr()) return

    val bytes = makeHdrClip(transfer) { COLOURED }
    val composition =
      EditComposition(
        tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(bytes), effects = listOf(spec))))),
        audio = AudioSpec.Remove,
      )
    val success = exportOf(composition, transfer)

    val frame = assertNotNull(decodeTenBitFrames(outputOf(success), transfer).firstOrNull())
    val matrix = checkNotNull(colorMatrixOf(spec))
    val expected = matrix.transformNits(COLOURED[0], COLOURED[1], COLOURED[2], transfer)
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
    val success = exportOf(letterboxed(HdrTransfer.Pq, fill), HdrTransfer.Pq)
    val frame =
      assertNotNull(
        decodeTenBitFrames(outputOf(success), HdrTransfer.Pq).lastOrNull(),
        "the blurred export decoded no frames",
      )
    return frame.nitsAt(x = 0.5, y = BAR)[1]
  }

  /**
   * The ten-bit codes in the middle of the left bar of an HLG export whose blurred fill is a tall
   * clip of [row], blurred wide enough that stripes a few rows apart spread into their mean.
   */
  private suspend fun pillarBarCodesOf(row: (y: Int) -> FloatArray): IntArray {
    val filler = makeHdrClip(HdrTransfer.Hlg) { BRIGHT }
    val tall = makeHdrClip(HdrTransfer.Hlg, width = HALF, frames = SHORT, row = row)
    val composition =
      EditComposition(
        tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(filler)), Clip(MediaSource.Bytes(tall))))),
        audio = AudioSpec.Remove,
        fill = Fill.Blurred(radius = WIDE_BLUR),
      )
    val frame =
      assertNotNull(
        decodeTenBitFrames(outputOf(exportOf(composition, HdrTransfer.Hlg)), HdrTransfer.Hlg).lastOrNull(),
        "the pillarboxed export decoded no frames",
      )
    val (cb, cr) = frame.chromaAt(x = PILLAR, y = 0.5)
    return intArrayOf(frame.lumaAt(x = PILLAR, y = 0.5), cb, cr)
  }

  /**
   * The codes an even blur of picture pixels [first] and [second] lands on when it averages the
   * luminance driven display light unpack decodes them to.
   */
  private fun hlgBlurCodesOf(
    first: FloatArray,
    second: FloatArray,
  ): IntArray {
    val a = hlgBufferNitsOf(first)
    val b = hlgBufferNitsOf(second)
    return tenBitCodesFromSignal(HdrTransfer.Hlg.signalFromNits(FloatArray(3) { (a[it] + b[it]) / 2f }))
  }

  /**
   * The same blur averaged in the light a per channel opto-optical transfer decodes to, which is
   * where the buffer's light sat before unpack ran the luminance driven one.
   */
  private fun perChannelBlurCodesOf(
    first: FloatArray,
    second: FloatArray,
  ): IntArray {
    val a = HdrTransfer.Hlg.pictureSignalOf(first)
    val b = HdrTransfer.Hlg.pictureSignalOf(second)
    val mean =
      FloatArray(3) {
        (HdrTransfer.Hlg.displayNitsFromSignal(a[it]) + HdrTransfer.Hlg.displayNitsFromSignal(b[it])) / 2f
      }
    return tenBitCodesFromSignal(HdrTransfer.Hlg.pictureSignalOf(mean))
  }

  private fun hlgBufferNitsOf(nits: FloatArray): FloatArray = hlgNitsFromSignal(HdrTransfer.Hlg.pictureSignalOf(nits))

  private fun assertCodes(
    expected: IntArray,
    read: IntArray,
    what: String,
  ) {
    assertNear(expected[0], read[0], LUMA_TOLERANCE, "$what luma")
    assertNear(expected[1], read[1], CHROMA_TOLERANCE, "$what Cb")
    assertNear(expected[2], read[2], CHROMA_TOLERANCE, "$what Cr")
  }

  private fun cropped(bytes: ByteArray): EditComposition =
    EditComposition(
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
  ): EditComposition {
    val filler = makeHdrClip(transfer) { BRIGHT }
    val wide = makeHdrClip(transfer, height = HALF, frames = SHORT) { COLOURED }
    return EditComposition(
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
      assertEquals("bt2020", colorSpace.primaries?.toString())
      assertEquals(transfer, colorSpace.transfer?.toString())
      assertEquals("bt2020-ncl", colorSpace.matrix?.toString())
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

  private fun filmstrip(): Filmstrip = Filmstrip { webCodecsBackend() }

  private suspend fun exportOf(
    composition: EditComposition,
    transfer: HdrTransfer,
  ): ExportStatus.Success {
    val filmstrip = filmstrip()
    val spec = ExportSpec(audioCodec = AudioCodec.None, hdr = HdrMode.KeepHdr)
    val statuses = filmstrip.export(planOf(filmstrip, composition, spec), MediaSink.Uri("")).toList()
    val failure = statuses.filterIsInstance<ExportStatus.Failure>().firstOrNull()
    if (failure != null) throw AssertionError("the $transfer export failed: ${failure.error.message}")
    return assertNotNull(
      statuses.filterIsInstance<ExportStatus.Success>().singleOrNull(),
      "statuses were ${statuses.map { it::class.simpleName }}",
    )
  }

  private suspend fun planOf(
    filmstrip: Filmstrip,
    composition: EditComposition,
    spec: ExportSpec,
  ): ExportPlan =
    when (val verdict = filmstrip.plan(composition, spec)) {
      is Verdict.Capable -> verdict.plan
      is Verdict.Degraded -> verdict.plan
      is Verdict.Incapable -> throw AssertionError("the plan was refused: ${verdict.reasons.map { it.message }}")
    }

  private fun outputOf(success: ExportStatus.Success): MediaSource =
    MediaSource.Uri(assertIs<MediaSink.Uri>(success.output).uri)

  private fun assertNear(
    expected: Int,
    actual: Int,
    tolerance: Int,
    what: String,
  ) {
    assertTrue(
      abs(expected - actual) <= tolerance,
      "$what was $actual, and the shared functions say $expected",
    )
  }

  private fun assertRelative(
    expected: Float,
    actual: Float,
    tolerance: Float,
    what: String,
  ) {
    val allowed = maxOf(abs(expected) * tolerance, NITS_FLOOR)
    assertTrue(abs(expected - actual) <= allowed, "$what: expected $expected but was $actual")
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
    val COLOURED = floatArrayOf(120f, 180f, 75f)

    // Saturated and far apart in hue, so a mix of the two in display light and one per channel
    // land several codes apart.
    val MAGENTA = floatArrayOf(250f, 20f, 250f)
    val GREEN = floatArrayOf(20f, 250f, 20f)

    // A sigma of six and a half pixels on the square output, which spreads four row stripes into
    // their mean while the middle of the frame stays five sigma clear of its top and bottom.
    const val WIDE_BLUR = 0.1f
    const val PILLAR = 0.1

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

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
import dev.jordond.filmstrip.webcodecs.BrowserHdrMediaTest.Companion.CHROMA_TOLERANCE
import dev.jordond.filmstrip.webcodecs.BrowserHdrMediaTest.Companion.LUMA_TOLERANCE
import dev.jordond.filmstrip.webcodecs.internal.SourceReader
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
 * A blurred fill on a kept grade in a real browser, read against the shared plan on the SDR signal. The bars are
 * decoded from the ten-bit export and compared with blurPlane run over what the source decodes to.
 */
class BrowserHdrFillBlurMediaTest {
  // The bars read against blurPlane run by the fill's shared plan on the SDR signal of the cover crop. The tall clip is
  // as wide as the output, so its cover is a crop of its own rows, and the crop cuts through rows of another grey.
  @Test
  fun aBlurredFillOnAKeptGradeLandsOnTheSharedPlanOnTheSdrSignal() =
    runTest {
      if (!webCodecsFilmstrip().encodesHdr()) return@runTest

      HdrTransfer.entries.forEach { assertFillBlurredOnSignal(it) }
    }

  private suspend fun assertFillBlurredOnSignal(transfer: HdrTransfer) {
    val filler = makeHdrClip(transfer, width = COVER_WIDTH, height = COVER_HEIGHT) { GREY }
    val tall =
      makeHdrClip(transfer, width = COVER_WIDTH, height = TALL_HEIGHT) { y -> FloatArray(3) { coverNitsAt(y) } }
    val fill = Fill.Blurred(radius = COVER_RADIUS, dim = 0f)
    val composition =
      Composition(
        tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(filler)), Clip(MediaSource.Bytes(tall))))),
        audio = AudioSpec.Remove,
        fill = fill,
      )
    val frame =
      decodeTenBitFrames(outputOf(hdrExportOf(composition, transfer)), transfer)
        .lastOrNull()
        .shouldNotBeNull()
    frame.height shouldBe COVER_HEIGHT

    val plan = fill.blurPlan(Size(COVER_WIDTH, COVER_HEIGHT))
    val sigma = plan.sigmaPx / plan.width
    val decoded = decodeTenBitFrames(MediaSource.Bytes(tall), transfer).first()
    val sourceNits = (0 until TALL_HEIGHT).map { decoded.nitsAt(x = 0.5, y = (it + 0.5) / TALL_HEIGHT)[1].toDouble() }
    val rows = (0 until COVER_HEIGHT).map { sourceNits[it + CROP_TOP].toFloat() }
    val cover = DoubleArray(COVER_WIDTH * COVER_HEIGHT) { rows[it / COVER_WIDTH].toDouble() }
    val opaque = DoubleArray(cover.size) { 1.0 }
    val onSignal =
      blurPlane(DoubleArray(cover.size) { sdrSignalFromNits(cover[it]) }, opaque, COVER_WIDTH, COVER_HEIGHT, sigma)
    val onCodes =
      blurPlane(
        DoubleArray(cover.size) {
          lumaCodeOf(transfer, cover[it]).toDouble()
        },
        opaque,
        COVER_WIDTH,
        COVER_HEIGHT,
        sigma,
      )
    val whole = (0 until TALL_HEIGHT).map { lumaCodeOf(transfer, sourceNits[it]).toDouble() }
    val heldAtClip =
      blurPlane(
        DoubleArray(COVER_WIDTH * TALL_HEIGHT) { whole[it / COVER_WIDTH] },
        DoubleArray(COVER_WIDTH * TALL_HEIGHT) { 1.0 },
        COVER_WIDTH,
        TALL_HEIGHT,
        sigma,
      )

    var worst = 0
    var worstRow = 0
    var apart = 0.0
    var edge = 0.0
    for (row in 0 until COVER_HEIGHT) {
      val expected = lumaCodeOf(transfer, nitsFromSdrSignal(onSignal[row * COVER_WIDTH]))
      val off = abs(frame.lumaAt(x = BAR_AT, y = (row + 0.5) / COVER_HEIGHT) - expected)
      if (off > worst) {
        worst = off
        worstRow = row
      }
      apart = maxOf(apart, abs(onCodes[row * COVER_WIDTH] - expected))
      if (row == 0 ||
        row == COVER_HEIGHT - 1
      ) {
        edge = maxOf(edge, abs(heldAtClip[(row + CROP_TOP) * COVER_WIDTH] - expected))
      }
    }
    withClue(
      "$transfer: a blur of the codes lands at most $apart codes from the sdr signal's, too close to tell apart",
    ) {
      (apart > 2 * LUMA_TOLERANCE) shouldBe true
    }
    withClue("a blur held at the clip's edge lands $edge codes from one held at the crop's, too close to tell") {
      (edge > 2 * LUMA_TOLERANCE) shouldBe true
    }
    withClue("$transfer: the bar is $worst codes off the shared plan on the sdr signal at row $worstRow") {
      (worst <= LUMA_TOLERANCE) shouldBe true
    }
  }

  // Saturated and grey stripes, one row each and eighteen rows each, read channel by channel against blurPlane on the
  // SDR signal of what the stripes decode to, which a four two zero chroma plane has already mixed across a one row
  // stripe. On HLG the signal is each channel's scene light, and the bar goes back through the luminance driven
  // opto-optical transfer the way unpack decodes it.
  @Test
  fun aBlurredFillOfSaturatedStripesLandsOnTheSharedPlanOnTheSdrSignal() =
    runTest {
      if (!webCodecsFilmstrip().encodesHdr()) return@runTest

      for (transfer in HdrTransfer.entries) {
        for (run in listOf(1, STRIPE_RUN)) assertStripedFillBlurredOnSignal(transfer, run)
      }
    }

  private suspend fun assertStripedFillBlurredOnSignal(
    transfer: HdrTransfer,
    run: Int,
  ) {
    val filler = makeHdrClip(transfer, width = COVER_WIDTH, height = COVER_HEIGHT) { GREY }
    val tall =
      makeHdrClip(transfer, width = COVER_WIDTH, height = TALL_HEIGHT) { y ->
        if ((y / run) % 2 ==
          0
        ) {
          SATURATED
        } else {
          GREY
        }
      }
    val fill = Fill.Blurred(radius = COVER_RADIUS, dim = 0f)
    val composition =
      Composition(
        tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(filler)), Clip(MediaSource.Bytes(tall))))),
        audio = AudioSpec.Remove,
        fill = fill,
      )
    val frame =
      decodeTenBitFrames(outputOf(hdrExportOf(composition, transfer)), transfer)
        .lastOrNull()
        .shouldNotBeNull()
    val source = decodeTenBitFrames(MediaSource.Bytes(tall), transfer).first()

    val plan = fill.blurPlan(Size(COVER_WIDTH, COVER_HEIGHT))
    val sigma = plan.sigmaPx / plan.width
    val rowSignals =
      (0 until COVER_HEIGHT).map { row ->
        val codes = source.codesAt(x = 0.5, y = (row + CROP_TOP + 0.5) / TALL_HEIGHT)
        sdrSignalOf(transfer, signalFromTenBitCodes(codes[0], codes[1], codes[2]))
      }
    val opaque = DoubleArray(COVER_WIDTH * COVER_HEIGHT) { 1.0 }
    val blurred =
      List(3) { channel ->
        blurPlane(
          DoubleArray(opaque.size) { rowSignals[it / COVER_WIDTH][channel] },
          opaque,
          COVER_WIDTH,
          COVER_HEIGHT,
          sigma,
        )
      }

    var worstLuma = 0
    var worstChroma = 0
    var spread = 0
    for (row in 0 until COVER_HEIGHT) {
      val signal = DoubleArray(3) { blurred[it][row * COVER_WIDTH] }
      val expected = tenBitCodesFromSignal(transfer.signalFromNits(nitsFromSdrSignals(transfer, signal)))
      val y = (row + 0.5) / COVER_HEIGHT
      val (cb, cr) = frame.chromaAt(x = BAR_AT, y = y)
      worstLuma = maxOf(worstLuma, abs(frame.lumaAt(x = BAR_AT, y = y) - expected[0]))
      worstChroma = maxOf(worstChroma, abs(cb - expected[1]), abs(cr - expected[2]))
      val sharp = source.codesAt(x = 0.5, y = (row + CROP_TOP + 0.5) / TALL_HEIGHT)
      spread = maxOf(spread, abs(sharp[0] - expected[0]))
    }
    withClue("$transfer, runs of $run: the reference moves the stripes by $spread codes, too close to a sharp frame") {
      (spread > 2 * LUMA_TOLERANCE) shouldBe true
    }
    withClue("$transfer, runs of $run: the bar is $worstLuma luma codes off the shared plan on the sdr signal") {
      (worstLuma <= LUMA_TOLERANCE) shouldBe true
    }
    withClue("$transfer, runs of $run: the bar is $worstChroma chroma codes off the shared plan on the sdr signal") {
      (worstChroma <= CHROMA_TOLERANCE) shouldBe true
    }
  }

  // The SDR signal of each channel of a picture signal, which on HLG is the signal of its scene light.
  private fun sdrSignalOf(
    transfer: HdrTransfer,
    signal: FloatArray,
  ): DoubleArray =
    DoubleArray(3) {
      when (transfer) {
        HdrTransfer.Pq -> sdrSignalFromNits(transfer.displayNitsFromSignal(signal[it]).toDouble())
        HdrTransfer.Hlg -> sdrSignalFromHlgScene(sceneFromHlgSignal(signal[it].toDouble()))
      }
    }

  private fun nitsFromSdrSignals(
    transfer: HdrTransfer,
    signal: DoubleArray,
  ): FloatArray {
    val nits = DoubleArray(3) { nitsFromSdrSignal(signal[it]) }
    val display =
      when (transfer) {
        HdrTransfer.Pq -> nits
        HdrTransfer.Hlg -> hlgNitsFromScene(DoubleArray(3) { hlgSceneFromDisplayNits(nits[it]) })
      }
    return FloatArray(3) { display[it].toFloat() }
  }

  // A dim grey on the rows the crop cuts, a light band across its middle and mid grey beside it.
  private fun coverNitsAt(y: Int): Float =
    when {
      y < CROP_TOP || y >= CROP_TOP + COVER_HEIGHT -> DIM_GREY
      y in COVER_HEIGHT * 5 / 12 + CROP_TOP until COVER_HEIGHT * 7 / 12 + CROP_TOP -> LIGHT_GREY
      else -> MID_GREY
    }

  private fun lumaCodeOf(
    transfer: HdrTransfer,
    nits: Double,
  ): Int = tenBitCodesFromSignal(transfer.pictureSignalOf(FloatArray(3) { nits.toFloat() }))[0]

  private companion object {
    const val COVER_WIDTH = 128
    const val COVER_HEIGHT = 72
    const val TALL_HEIGHT = 144
    const val CROP_TOP = 36
    const val BAR_AT = 0.1

    // A sigma of 10.8 output pixels, which the plan boxes down by 2 onto whole pixels.
    const val COVER_RADIUS = 0.15f
    val GREY = floatArrayOf(60f, 60f, 60f)
    const val DIM_GREY = 8f
    const val MID_GREY = 40f
    const val LIGHT_GREY = 150f
    const val STRIPE_RUN = 18
    val SATURATED = floatArrayOf(250f, 20f, 250f)
  }
}

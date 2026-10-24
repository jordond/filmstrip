package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.geometry.crop
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.media.authoredHdrNits
import dev.jordond.filmstrip.media.signalFromNits
import dev.jordond.filmstrip.media.tenBitCodesFromSignal
import dev.jordond.filmstrip.test.HdrOverlayCells
import dev.jordond.filmstrip.test.encodesHdr
import dev.jordond.filmstrip.test.isSimulator
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import kotlin.experimental.ExperimentalNativeApi
import kotlin.math.abs
import kotlin.native.OsFamily
import kotlin.native.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes

/**
 * The letterbox fill on an export that keeps its grade, read back out of the written file.
 *
 * A square crop of the 16:9 HDR fixtures leaves a bar above and below the picture that only the fill paints. The bar is
 * held to [signalFromNits] of [authoredHdrNits], the signal every backend writes there, so a white fill lands at
 * reference white rather than at the format's peak.
 *
 * Skipped when the fixtures are absent, as in [AppleHdrTest].
 */
@OptIn(ExperimentalForeignApi::class)
class AppleHdrFillTest {
  private val fixtures = NSProcessInfo.processInfo.environment[FIXTURES] as? String

  private val filmstrip = Filmstrip { avFoundationBackend() }

  @Test
  fun `a white fill on a kept grade is written at graphics white not at the panel peak`() =
    runTest(timeout = TIMEOUT) {
      if (!withContext(Dispatchers.Default) { filmstrip.encodesHdr() }) return@runTest

      HdrTransfer.entries.forEach { transfer ->
        val frame = exportedFrame(transfer, HdrOverlayCells.WHITE) ?: return@forEach
        assertCarriesSignal(frame, transfer, HdrOverlayCells.WHITE)
        assertCarriesLight(frame, transfer, HdrOverlayCells.WHITE, -NITS_DRIFT..NITS_DRIFT)
      }
    }

  // White is the end of the range, where reading the authored fraction as light and reading it
  // through the sRGB curve agree. A colour in the middle is where the two part. The signal check is
  // what tells a right fill from a wrong one on both transfers. On HLG the light check watches Core
  // Image's own decode, which reads the bar bright by a pinned amount.
  @Test
  fun `a mid-range fill on a kept grade carries its own signal on both transfers`() =
    runTest(timeout = TIMEOUT) {
      if (!withContext(Dispatchers.Default) { filmstrip.encodesHdr() }) return@runTest

      HdrTransfer.entries.forEach { transfer ->
        val frame = exportedFrame(transfer, MID_BLUE) ?: return@forEach
        assertCarriesSignal(frame, transfer, MID_BLUE)
        val drift =
          when (transfer) {
            HdrTransfer.Pq -> -NITS_DRIFT..NITS_DRIFT
            HdrTransfer.Hlg -> coreImageHlgDrift(lightOff(frame, MID_BLUE))
          }
        assertCarriesLight(frame, transfer, MID_BLUE, drift)
      }
    }

  private fun assertCarriesSignal(
    frame: HdrFrameProbe,
    transfer: HdrTransfer,
    color: Int,
  ) {
    val expected = tenBitCodesFromSignal(transfer.signalFromNits(authoredHdrNits(color))).toList()
    val read = frame.codesAt(CENTRE, BAR)

    assertTrue(
      expected.indices.all { abs(read[it] - expected[it]) <= CODE_TOLERANCE },
      "the $transfer bar carried codes $read and the shared signal asks for $expected",
    )
  }

  /**
   * Holds each channel of the bar's light to the shared fill.
   *
   * The fraction a channel reads off by, from [lightOff], has to sit inside [drift].
   */
  private fun assertCarriesLight(
    frame: HdrFrameProbe,
    transfer: HdrTransfer,
    color: Int,
    drift: ClosedFloatingPointRange<Float>,
  ) {
    val off = lightOff(frame, color)

    assertTrue(
      off.all { it in drift },
      "the $transfer bar read ${frame.nitsAt(CENTRE, BAR)} nits and the shared fill asks for " +
        "${authoredHdrNits(color).toList()}, off by $off where $drift is allowed",
    )
  }

  // How far each channel of the bar's light reads from the shared fill, as a fraction of it.
  private fun lightOff(
    frame: HdrFrameProbe,
    color: Int,
  ): List<Float> {
    val expected = authoredHdrNits(color)
    val read = frame.nitsAt(CENTRE, BAR)
    return List(3) { (read[it] - expected[it]) / expected[it] }
  }

  /**
   * The range Core Image's HLG decode of a saturated bar reads bright by, on the platform under test.
   *
   * The simulator is told apart by the device name it puts in the environment. On hardware the range is unmeasured, so
   * this fails with the [off] the bar was read at.
   */
  @OptIn(ExperimentalNativeApi::class)
  private fun coreImageHlgDrift(off: List<Float>): ClosedFloatingPointRange<Float> =
    when {
      Platform.osFamily == OsFamily.MACOSX -> {
        MACOS_CORE_IMAGE_HLG_DRIFT
      }
      isSimulator() -> {
        IOS_SIMULATOR_CORE_IMAGE_HLG_DRIFT
      }
      else -> {
        fail(
          "Core Image's HLG decode read the bar off by $off on ${Platform.osFamily} hardware, where its range is " +
            "unmeasured. Record this beside IOS_SIMULATOR_CORE_IMAGE_HLG_DRIFT as the device's own range",
        )
      }
    }

  private suspend fun exportedFrame(
    transfer: HdrTransfer,
    color: Int,
  ): HdrFrameProbe? {
    val source = fixture(transfer) ?: return null
    val composition =
      compositionOf {
        clip(MediaSource.of(source))
        fill(Fill.Solid(color))
        effects { crop(AspectRatio.Square, Fit.Contain) }
      }
    val spec = ExportSpec(targetHeight = 720, hdr = HdrMode.KeepHdr)
    val plan =
      when (val verdict = filmstrip.plan(composition, spec)) {
        is Verdict.Capable -> verdict.plan
        is Verdict.Degraded -> verdict.plan
        is Verdict.Incapable -> error("refused: ${verdict.reasons.joinToString { it.message }}")
      }

    val finished = withContext(Dispatchers.Default) { filmstrip.export(plan, MediaSink.Temporary).toList() }.last()
    if (finished is ExportStatus.Failure) error("export failed: ${finished.error.message}")
    val path = assertIs<MediaSink.Path>(assertIs<ExportStatus.Success>(finished).output).path

    try {
      val written = assertIs<ProbeResult.Success>(filmstrip.probe(MediaSource.of(path))).info.video
      assertEquals(transfer, written?.hdrTransfer, "a $transfer grade was written as ${written?.hdrTransfer}")

      return hdrFrameOf(path) ?: error("could not decode a frame of the $transfer export")
    } finally {
      NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }
  }

  private fun fixture(transfer: HdrTransfer): String? {
    val directory = fixtures ?: return null
    val clip =
      when (transfer) {
        HdrTransfer.Pq -> CLIP
        HdrTransfer.Hlg -> HLG_CLIP
      }
    val path = "$directory/$clip"
    return path.takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }
  }

  internal companion object {
    val TIMEOUT = 5.minutes

    const val FIXTURES = "FILMSTRIP_FIXTURES"
    const val CLIP = "apple_export_hdr.mp4"
    const val HLG_CLIP = "apple_export_hdr_hlg.mp4"

    // Inside the bar a square crop of a 16:9 frame leaves along one edge, well clear of the picture.
    const val BAR = 0.05f
    const val CENTRE = 0.5f

    const val MID_BLUE = 0xFF6699CC.toInt()

    // What a hardware encode leaves on a flat colour, a code or two either way.
    const val CODE_TOLERANCE = 2

    // A code of rounding near reference white moves light by about a percent, with room for the
    // encoder on top of that.
    const val NITS_DRIFT = 0.04f

    // Core Image's own limit. Its HLG decode reads a saturated bar bright on every channel while the codes match the
    // shared signal to half a code, and a white bar reads true. Measured 7.47% to 7.65% on macOS and 6.58% to 7.25%
    // on the iOS simulator. Pinned from both ends, so a change to the decode shows up whichever way it moves. An
    // iPhone has no range here yet and fails with its reading until one is measured on hardware.
    val MACOS_CORE_IMAGE_HLG_DRIFT = 0.073f..0.078f
    val IOS_SIMULATOR_CORE_IMAGE_HLG_DRIFT = 0.064f..0.074f
  }
}

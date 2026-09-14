package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.CapabilitiesResult
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
import dev.jordond.filmstrip.transform.internal.hdrFillNits
import dev.jordond.filmstrip.transform.internal.signalFromNits
import dev.jordond.filmstrip.transform.internal.tenBitCodesFromSignal
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The letterbox fill on an export that keeps its grade, read back out of the written file.
 *
 * A square crop of the 16:9 HDR fixtures leaves a bar above and below the picture that only the fill paints. The bar is
 * held to [signalFromNits] of [hdrFillNits], the signal every backend writes there, so a white fill lands at reference
 * white rather than at the format's peak.
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
      if (!encodesHdr()) return@runTest

      HdrTransfer.entries.forEach { transfer ->
        val frame = exportedFrame(transfer, WHITE) ?: return@forEach
        assertCarriesSignal(frame, transfer, WHITE)
        assertCarriesLight(frame, transfer, WHITE)
      }
    }

  // White is the end of the range, where reading the authored fraction as light and reading it
  // through the sRGB curve agree. A colour in the middle is where the two part.
  @Test
  fun `a mid-range fill on a kept grade carries its own signal on both transfers`() =
    runTest(timeout = TIMEOUT) {
      if (!encodesHdr()) return@runTest

      HdrTransfer.entries.forEach { transfer ->
        val frame = exportedFrame(transfer, MID_BLUE) ?: return@forEach
        assertCarriesSignal(frame, transfer, MID_BLUE)
        // On HLG, Core Image reads this bar 7.6% bright on every channel while its codes match the
        // shared signal to half a code, and a white bar reads true. Light is compared on PQ alone.
        if (transfer == HdrTransfer.Pq) assertCarriesLight(frame, transfer, MID_BLUE)
      }
    }

  private fun assertCarriesSignal(
    frame: HdrFrameProbe,
    transfer: HdrTransfer,
    color: Int,
  ) {
    val expected = tenBitCodesFromSignal(transfer.signalFromNits(hdrFillNits(color))).toList()
    val read = frame.codesAt(CENTRE, BAR)

    assertTrue(
      expected.indices.all { abs(read[it] - expected[it]) <= CODE_TOLERANCE },
      "the $transfer bar carried codes $read and the shared signal asks for $expected",
    )
  }

  private fun assertCarriesLight(
    frame: HdrFrameProbe,
    transfer: HdrTransfer,
    color: Int,
  ) {
    val expected = hdrFillNits(color)
    val read = frame.nitsAt(CENTRE, BAR)

    repeat(3) { channel ->
      assertTrue(
        abs(read[channel] - expected[channel]) <= expected[channel] * NITS_DRIFT,
        "the $transfer bar read $read nits and the shared fill asks for ${expected.toList()}",
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

  private suspend fun encodesHdr(): Boolean =
    withContext(Dispatchers.Default) {
      (filmstrip.capabilities() as? CapabilitiesResult.Success)?.capabilities?.supportsHdrEncoding == true
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

  private companion object {
    val TIMEOUT = 5.minutes

    const val FIXTURES = "FILMSTRIP_FIXTURES"
    const val CLIP = "apple_export_hdr.mp4"
    const val HLG_CLIP = "apple_export_hdr_hlg.mp4"

    // Inside the bar a square crop of a 16:9 frame leaves along one edge, well clear of the picture.
    const val BAR = 0.05f
    const val CENTRE = 0.5f

    const val WHITE = 0xFFFFFFFF.toInt()
    const val MID_BLUE = 0xFF6699CC.toInt()

    // What a hardware encode leaves on a flat colour, a code or two either way.
    const val CODE_TOLERANCE = 2

    // A code of rounding near reference white moves light by about a percent, with room for the
    // encoder on top of that.
    const val NITS_DRIFT = 0.04f
  }
}

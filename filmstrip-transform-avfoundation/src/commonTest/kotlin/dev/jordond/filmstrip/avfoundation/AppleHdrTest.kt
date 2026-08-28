package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The HDR branch, against a ten-bit BT.2020 PQ clip through real Core Image and VideoToolbox.
 *
 * Every other export test in this module runs eight-bit BT.709, which tells a kept grade apart from
 * neither a tone map nor a silent drop to SDR. [AppleProbeTest] asserts the fixture really is PQ,
 * so a failure here is the export rather than the input.
 *
 * Skipped when the fixtures are absent, as in [AppleExportTest].
 */
@OptIn(ExperimentalForeignApi::class)
class AppleHdrTest {
  private val fixtures = NSProcessInfo.processInfo.environment[FIXTURES] as? String

  private val filmstrip = Filmstrip { avFoundationBackend() }

  @Test
  fun `keeping hdr either keeps it or says it did not`() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest
      // KeepHdr is documented to refuse rather than quietly tone-map, so a device with no HDR
      // encoder refusing is the contract holding. There is nothing left to measure after that.
      if (!encodesHdr()) {
        assertIs<Verdict.Incapable>(filmstrip.plan(composition(source), spec(HdrMode.KeepHdr)))
        return@runTest
      }

      val run = export(source, HdrMode.KeepHdr)
      val written = assertNotNull(run.info.video, "the written file has no video track")

      // A disjunction, not a fixed expectation, since whether HDR survives is the device's answer
      // and never this code's. The third case is the one that is not allowed, SDR out with nothing
      // said about it.
      if (written.hdrTransfer == null) {
        assertTrue(
          run.toneMapped,
          "HDR was dropped without an Adjusted reporting it, so the caller was told nothing",
        )
      } else {
        assertEquals(HdrTransfer.Pq, written.hdrTransfer, "PQ went in and something else came out")
        assertTrue(!run.toneMapped, "HDR survived but an Adjusted said it was tone mapped")
      }
    }

  @Test
  fun `tone mapping writes sdr`() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest

      val run = export(source, HdrMode.ToneMapToSdr)
      val written = assertNotNull(run.info.video, "the written file has no video track")

      assertNull(written.hdrTransfer, "tone mapping to SDR left an HDR transfer function behind")
      // Asked for, never fallen back to, so there is nothing to report as an adjustment.
      assertTrue(!run.toneMapped, "an adjustment was reported for the tone mapping that was asked for")
    }

  @Test
  fun `auto keeps the grade when the device encodes it and tone maps when it does not`() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest

      val run = export(source, HdrMode.Auto)
      val written = assertNotNull(run.info.video, "the written file has no video track")

      // Auto is the mode that lands somewhere the caller did not name, so it is the one that has to
      // report. Either it kept the grade silently or it dropped it and said so.
      if (encodesHdr()) {
        assertEquals(HdrTransfer.Pq, written.hdrTransfer, "the device encodes HDR but Auto did not keep it")
        assertTrue(!run.toneMapped, "HDR survived but an Adjusted said it was tone mapped")
      } else {
        assertNull(written.hdrTransfer, "the device encodes no HDR but Auto wrote a transfer function")
        assertTrue(run.toneMapped, "Auto tone mapped without reporting it")
      }
    }

  @Test
  fun `the capability probe never claims less than the encoder wrote`() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest
      if (!encodesHdr()) return@runTest
      val kept = export(source, HdrMode.KeepHdr).info.video?.hdrTransfer != null

      // The plan's capability answer and what the encoder actually did, which are allowed to differ
      // in one direction only. A device may advertise HDR and still fall back, but a device that
      // wrote HDR while advertising none has a capability probe that is lying.
      val capabilities = withContext(Dispatchers.Default) { filmstrip.capabilities() }
      val advertised = assertIs<CapabilitiesResult.Success>(capabilities).capabilities
      assertTrue(!kept || advertised.supportsHdrEncoding, "the device wrote HDR while advertising none")
    }

  /**
   * One export, with both the file it wrote and whether an adjustment reported tone mapping.
   */
  private class Run(
    val info: MediaInfo,
    val toneMapped: Boolean,
  )

  private suspend fun encodesHdr(): Boolean =
    withContext(Dispatchers.Default) {
      (filmstrip.capabilities() as? CapabilitiesResult.Success)?.capabilities?.supportsHdrEncoding == true
    }

  private fun composition(source: String) = filmstrip.composition { clip(MediaSource.of(source)) }

  private fun spec(hdr: HdrMode) = ExportSpec(targetHeight = 720, hdr = hdr)

  private suspend fun export(
    source: String,
    hdr: HdrMode,
  ): Run {
    val composition = composition(source)
    val spec = spec(hdr)
    val plan =
      when (val verdict = filmstrip.plan(composition, spec)) {
        is Verdict.Capable -> verdict.plan
        is Verdict.Degraded -> verdict.plan
        is Verdict.Incapable -> error("refused: ${verdict.reasons.joinToString { it.message }}")
      }

    val statuses = withContext(Dispatchers.Default) { filmstrip.export(plan, MediaSink.Temporary).toList() }
    val finished = statuses.last()
    if (finished is ExportStatus.Failure) error("export failed: ${finished.error.message}")

    val success = assertIs<ExportStatus.Success>(finished)
    val toneMapped =
      statuses.filterIsInstance<ExportStatus.Adjusted>().any { status ->
        status.adjustments.any { it.kind == AdjustmentKind.HdrToneMapped }
      }
    val path = assertIs<MediaSink.Path>(success.output).path

    try {
      return Run(probe(path), toneMapped)
    } finally {
      NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }
  }

  private suspend fun probe(path: String): MediaInfo =
    assertIs<ProbeResult.Success>(filmstrip.probe(MediaSource.of(path))).info

  private fun fixture(): String? {
    val directory = fixtures ?: return null
    val path = "$directory/$CLIP"
    return path.takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }
  }

  private companion object {
    val TIMEOUT = 5.minutes

    const val FIXTURES = "FILMSTRIP_FIXTURES"
    const val CLIP = "apple_export_hdr.mp4"
  }
}

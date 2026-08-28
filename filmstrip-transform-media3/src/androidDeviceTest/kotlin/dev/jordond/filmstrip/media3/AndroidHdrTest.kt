package dev.jordond.filmstrip.media3

import androidx.test.platform.app.InstrumentationRegistry
import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The HDR branch, against a ten-bit BT.2020 PQ clip on real hardware.
 *
 * Every other test in this module runs eight-bit BT.709, which tells `HDR_MODE_KEEP_HDR` apart
 * from neither tone mapping nor a silent drop to SDR. The claim that matters is the one
 * [keepingHdrEitherKeepsItOrSaysItDidNot] makes, that an export never quietly loses the transfer
 * function.
 *
 * Skipped when the fixture is absent, as in [AndroidExportTest].
 */
class AndroidHdrTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val filmstrip = Filmstrip(context) { media3Backend() }

  @Test
  fun readsTheFixtureBackAsHdr() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest

      val video = assertNotNull(probe(source).video, "the fixture has no video track")

      // The prober, not ffprobe. A fixture the library cannot see as HDR would make every other
      // test here pass for the wrong reason.
      assertEquals(HdrTransfer.Pq, video.hdrTransfer, "the fixture is not PQ")
    }

  @Test
  fun keepingHdrEitherKeepsItOrSaysItDidNot() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest
      // KeepHdr is documented to refuse instead of quietly tone-mapping, so a device with no HDR
      // encoder refusing is the contract holding. There is nothing left to measure after that.
      if (!encodesHdr()) {
        assertIs<Verdict.Incapable>(filmstrip.plan(composition(source), spec(HdrMode.KeepHdr)))
        return@runTest
      }

      val run = export(source, HdrMode.KeepHdr)
      val written = assertNotNull(run.info.video, "the written file has no video track")

      // A disjunction, not a fixed expectation, since whether HDR survives is the device's answer
      // and never this code's. The third case is the one that is not allowed, SDR out with
      // nothing said about it.
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
  fun toneMappingWritesSdr() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest
      // Tone mapping still has to decode the HDR clip, which a device without a ten-bit decoder
      // cannot do at all. Every emulator image is one of those, so this says so rather than
      // passing quietly and reading as covered.
      if (!decodesHdr()) {
        println("skipping the tone map on a device with no ten-bit HEVC decoder, which every emulator is")
        return@runTest
      }

      val run = export(source, HdrMode.ToneMapToSdr)
      val written = assertNotNull(run.info.video, "the written file has no video track")

      assertNull(written.hdrTransfer, "tone mapping to SDR left an HDR transfer function behind")
      // Asked for, never fallen back to, so there is nothing to report as an adjustment.
      assertTrue(!run.toneMapped, "an adjustment was reported for the tone mapping that was asked for")
    }

  @Test
  fun reportsWhetherTheDeviceEncodesHdr() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest
      if (!encodesHdr()) {
        println("skipping the encoder claim on a device that advertises no HDR encoder")
        return@runTest
      }
      val kept = export(source, HdrMode.KeepHdr).info.video?.hdrTransfer != null

      // The plan's capability answer and what the encoder actually did, which are allowed to differ
      // in one direction only. A device may advertise HDR and still fall back, but a device that
      // wrote HDR while advertising none has a capability probe that is lying.
      val capabilities = filmstrip.capabilities()
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
    (filmstrip.capabilities() as? CapabilitiesResult.Success)?.capabilities?.supportsHdrEncoding == true

  private fun decodesHdr(): Boolean = decodesTenBitHevc()

  private fun composition(source: MediaSource) = EditComposition(listOf(Track(listOf(Clip(source)))))

  private fun spec(hdr: HdrMode) = ExportSpec(targetHeight = 720, hdr = hdr)

  private suspend fun export(
    source: MediaSource,
    hdr: HdrMode,
  ): Run {
    val composition = composition(source)
    val spec = spec(hdr)
    val plan =
      when (val verdict = filmstrip.plan(composition, spec)) {
        is Verdict.Capable -> verdict.plan
        is Verdict.Degraded -> verdict.plan
        is Verdict.Incapable -> throw AssertionError("refused: ${verdict.reasons.map { it.message }}")
      }

    val statuses = withContext(Dispatchers.Default) { filmstrip.export(plan, MediaSink.temporary()).toList() }
    val finished = statuses.last()
    if (finished is ExportStatus.Failure) throw AssertionError("export failed: ${finished.error.message}")

    val success = assertIs<ExportStatus.Success>(finished)
    val toneMapped =
      statuses.filterIsInstance<ExportStatus.Adjusted>().any { status ->
        status.adjustments.any { it.kind == AdjustmentKind.HdrToneMapped }
      }
    return Run(probe(success.output.asSource()), toneMapped)
  }

  private suspend fun probe(source: MediaSource): MediaInfo =
    assertIs<ProbeResult.Success>(filmstrip.probe(source)).info

  private fun fixture(): MediaSource? {
    val stream = javaClass.classLoader?.getResourceAsStream(CLIP) ?: return null
    val file = File(context.cacheDir, CLIP)
    stream.use { input -> file.outputStream().use(input::copyTo) }
    return MediaSource.of(file.path)
  }

  private fun MediaSink.asSource(): MediaSource =
    when (this) {
      is MediaSink.Path -> MediaSource.of(path)
      is MediaSink.Uri -> MediaSource.ofUri(uri)
      is MediaSink.Temporary -> throw AssertionError("Temporary resolves to a real path on success")
    }

  private companion object {
    val TIMEOUT = 5.minutes

    const val CLIP = "android_export_hdr.mp4"
  }
}

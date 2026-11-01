package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.compositionOf
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
 * A case fails when its fixture is missing, as in [AppleExportTest].
 */
@OptIn(ExperimentalForeignApi::class)
class AppleHdrTest {
  private val filmstrip = Filmstrip { avFoundationBackend() }

  @Test
  fun `keeping hdr either keeps it or says it did not`() =
    runTest(timeout = TIMEOUT) {
      val source = hdrFixture(HdrTransfer.Pq)
      val run = export(source, HdrMode.KeepHdr)
      val written = assertNotNull(run.info.video, "the written file has no video track")

      // The encoder tests pin each target's HDR answer, so this only holds the export to its report,
      // PQ kept or tone mapping reported. SDR out with nothing said about it is the case that fails.
      if (written.hdrTransfer == null) {
        assertTrue(
          run.toneMapped,
          "HDR was dropped without an adjustment reporting it, so the caller was told nothing",
        )
      } else {
        assertEquals(HdrTransfer.Pq, written.hdrTransfer, "PQ went in and something else came out")
        assertTrue(!run.toneMapped, "HDR survived but an adjustment said it was tone mapped")
      }
    }

  @Test
  fun `tone mapping writes sdr`() =
    runTest(timeout = TIMEOUT) {
      val source = hdrFixture(HdrTransfer.Pq)

      val run = export(source, HdrMode.ToneMapToSdr)
      val written = assertNotNull(run.info.video, "the written file has no video track")

      assertNull(written.hdrTransfer, "tone mapping to SDR left an HDR transfer function behind")
      // Asked for, never fallen back to, so there is nothing to report as an adjustment.
      assertTrue(!run.toneMapped, "an adjustment was reported for the tone mapping that was asked for")
    }

  @Test
  fun `auto keeps the grade the device encodes`() =
    runTest(timeout = TIMEOUT) {
      val source = hdrFixture(HdrTransfer.Pq)

      val run = export(source, HdrMode.Auto)
      val written = assertNotNull(run.info.video, "the written file has no video track")

      // Every Apple target advertises an HDR encoder, which the encoder tests pin, so Auto keeps the grade silently.
      assertEquals(HdrTransfer.Pq, written.hdrTransfer, "the device encodes HDR but Auto did not keep it")
      assertTrue(!run.toneMapped, "HDR survived but an adjustment said it was tone mapped")
    }

  /**
   * One export, with both the file it wrote and whether an adjustment reported tone mapping.
   */
  private class Run(
    val info: MediaInfo,
    val toneMapped: Boolean,
  )

  private fun composition(source: String) = compositionOf { clip(MediaSource.of(source)) }

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
    // Read off Success rather than ExportStatus.Adjusted. Adjusted belongs to the overload that
    // plans as well, and this runs a plan the test already holds, so nothing here ever emits one
    // and a test that looked there would read every export as having reported nothing.
    val toneMapped = success.adjustments.any { it.kind == AdjustmentKind.HdrToneMapped }
    val path = assertIs<MediaSink.Path>(success.output).path

    try {
      return Run(probe(path), toneMapped)
    } finally {
      NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }
  }

  private suspend fun probe(path: String): MediaInfo =
    assertIs<ProbeResult.Success>(filmstrip.probe(MediaSource.of(path))).info

  private companion object {
    val TIMEOUT = 5.minutes
  }
}

package dev.jordond.filmstrip.media3

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.HDR_CLIP
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import dev.jordond.filmstrip.test.assumeDecodesTenBitHevc
import dev.jordond.filmstrip.test.assumeEncodesHdr
import dev.jordond.filmstrip.test.encodesHdr
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * The HDR branch, against a ten-bit BT.2020 PQ clip on real hardware.
 *
 * Every other test in this module runs eight-bit BT.709, which tells `HDR_MODE_KEEP_HDR` apart
 * from neither tone mapping nor a silent drop to SDR. The claim that matters is the one
 * [keepingHdrEitherKeepsItOrSaysItDidNot] makes, that an export never quietly loses the transfer
 * function.
 *
 * Skipped when the fixture is absent, as in [AndroidExportMediaTest].
 */
class AndroidHdrMediaTest {
  private val probe = Media3ExportProbe()

  @Test
  fun readsTheFixtureBackAsHdr() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture(HDR_CLIP)

      val video = withClue("the fixture has no video track") { probe.infoOf(source).video.shouldNotBeNull() }

      // The prober, not ffprobe. A fixture the library cannot see as HDR would make every other
      // test here pass for the wrong reason.
      withClue("the fixture is not PQ") { video.hdrTransfer shouldBe HdrTransfer.Pq }
    }

  @Test
  fun keepingHdrEitherKeepsItOrSaysItDidNot() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture(HDR_CLIP)
      // KeepHdr is documented to refuse instead of quietly tone-mapping, so a device with no HDR
      // encoder refusing is the contract holding. There is nothing left to measure after that.
      if (!probe.filmstrip.encodesHdr()) {
        probe.filmstrip.plan(composition(source), spec(HdrMode.KeepHdr)).shouldBeInstanceOf<Verdict.Incapable>()
        return@runTest
      }

      val run = export(source, HdrMode.KeepHdr)
      val written = withClue("the written file has no video track") { run.info.video.shouldNotBeNull() }

      // A disjunction, not a fixed expectation, since whether HDR survives is the device's answer
      // and never this code's. The third case is the one that is not allowed, SDR out with
      // nothing said about it.
      if (written.hdrTransfer == null) {
        withClue("HDR was dropped without an adjustment reporting it, so the caller was told nothing") {
          run.toneMapped shouldBe true
        }
      } else {
        withClue("PQ went in and something else came out") { written.hdrTransfer shouldBe HdrTransfer.Pq }
        withClue("HDR survived but an adjustment said it was tone mapped") { !run.toneMapped shouldBe true }
      }
    }

  @Test
  fun toneMappingWritesSdr() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture(HDR_CLIP)
      // Tone mapping still has to decode the HDR clip, which a device without a ten-bit decoder
      // cannot do at all. Every emulator image is one of those, so this reports skipped rather than
      // passing quietly and reading as covered.
      assumeDecodesTenBitHevc()

      val run = export(source, HdrMode.ToneMapToSdr)
      val written = withClue("the written file has no video track") { run.info.video.shouldNotBeNull() }

      withClue("tone mapping to SDR left an HDR transfer function behind") { written.hdrTransfer.shouldBeNull() }
      // Asked for, never fallen back to, so there is nothing to report as an adjustment.
      withClue("an adjustment was reported for the tone mapping that was asked for") { !run.toneMapped shouldBe true }
    }

  @Test
  fun reportsWhetherTheDeviceEncodesHdr() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture(HDR_CLIP)
      probe.filmstrip.assumeEncodesHdr()
      val kept = export(source, HdrMode.KeepHdr).info.video?.hdrTransfer != null

      // The plan's capability answer and what the encoder actually did, which are allowed to differ
      // in one direction only. A device may advertise HDR and still fall back, but a device that
      // wrote HDR while advertising none has a capability probe that is lying.
      val capabilities = probe.filmstrip.capabilities()
      val advertised = capabilities.shouldBeInstanceOf<CapabilitiesResult.Success>().capabilities
      withClue("the device wrote HDR while advertising none") {
        (!kept || advertised.supportsHdrEncoding) shouldBe true
      }
    }

  /**
   * One export, with both the file it wrote and whether an adjustment reported tone mapping.
   */
  private class Run(
    val info: MediaInfo,
    val toneMapped: Boolean,
  )

  private fun composition(source: MediaSource) = Composition(listOf(Track(listOf(Clip(source)))))

  // Below the fixture's own 720, since a height at or above it keeps the frame and would let a kept grade copy across.
  private fun spec(hdr: HdrMode) = ExportSpec(heightPx = 360, hdr = hdr)

  private suspend fun export(
    source: MediaSource,
    hdr: HdrMode,
  ): Run {
    val success = probe.finish(probe.plan(composition(source), spec(hdr)))
    // Read off Success rather than ExportStatus.Adjusted. Adjusted belongs to the overload that
    // plans as well, and this runs a plan the test already holds, so nothing here ever emits one
    // and a test that looked there would read every export as having reported nothing.
    val toneMapped = success.adjustments.any { it.kind == AdjustmentKind.HdrToneMapped }
    return Run(probe.infoOf(success.output.asSource()), toneMapped)
  }

  private fun MediaSink.asSource(): MediaSource =
    when (this) {
      is MediaSink.Path -> MediaSource.of(path)
      is MediaSink.Uri -> MediaSource.ofUri(uri)
      is MediaSink.Temporary -> throw AssertionError("Temporary resolves to a real path on success")
    }
}

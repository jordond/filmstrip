package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.avfoundation.internal.export.CORE_IMAGE_TONE_CURVE
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effect.DegradationReason
import dev.jordond.filmstrip.export.Adjustment
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
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import kotlin.test.Test
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
      val written = run.info.video.shouldNotBeNull()

      // The encoder tests pin each target's HDR answer, so this only holds the export to its report,
      // PQ kept or tone mapping reported. SDR out with nothing said about it is the case that fails.
      if (written.hdrTransfer == null) {
        withClue("HDR was dropped without an adjustment reporting it, so the caller was told nothing") {
          run.toneMapped.shouldBeTrue()
        }
        withClue("Core Image tone mapped without saying its curve is its own") {
          run.toneCurve shouldBe curve(HdrMode.KeepHdr)
        }
      } else {
        written.hdrTransfer shouldBe HdrTransfer.Pq
        withClue("HDR survived but an adjustment said it was tone mapped") { run.toneMapped.shouldBeFalse() }
        withClue("HDR survived but the tone curve was reported") { run.toneCurve.shouldBeNull() }
      }
    }

  @Test
  fun `tone mapping writes sdr`() =
    runTest(timeout = TIMEOUT) {
      val source = hdrFixture(HdrTransfer.Pq)

      val run = export(source, HdrMode.ToneMapToSdr)
      val written = run.info.video.shouldNotBeNull()

      withClue("tone mapping to SDR left an HDR transfer function behind") { written.hdrTransfer.shouldBeNull() }
      // Asked for, never fallen back to, so there is nothing to report as an adjustment.
      withClue("an adjustment was reported for the tone mapping that was asked for") {
        run.toneMapped.shouldBeFalse()
      }
      // The curve is Core Image's whatever asked for the tone map, so it is reported here too.
      withClue("Core Image tone mapped without saying its curve is its own") {
        run.toneCurve shouldBe curve(HdrMode.ToneMapToSdr)
      }
    }

  // Core Image's curve departs from the shared one, so a strict export refuses even the tone map it asked for.
  @Test
  fun `a strict tone map refuses core image's own curve`() =
    runTest(timeout = TIMEOUT) {
      val spec = ExportSpec(heightPx = 720, hdr = HdrMode.ToneMapToSdr, strict = true)

      val verdict = filmstrip.plan(composition(hdrFixture(HdrTransfer.Pq)), spec)

      val reason = verdict.shouldBeInstanceOf<Verdict.Incapable>().reasons.single()
      reason.shouldBeInstanceOf<FilmstripError.InvalidComposition>().message shouldBe CORE_IMAGE_TONE_CURVE.message
    }

  @Test
  fun `auto keeps the grade the device encodes`() =
    runTest(timeout = TIMEOUT) {
      val source = hdrFixture(HdrTransfer.Pq)

      val run = export(source, HdrMode.Auto)
      val written = run.info.video.shouldNotBeNull()

      // Every Apple target advertises an HDR encoder, which the encoder tests pin, so Auto keeps the grade silently.
      withClue("the device encodes HDR but Auto did not keep it") { written.hdrTransfer shouldBe HdrTransfer.Pq }
      withClue("HDR survived but an adjustment said it was tone mapped") { run.toneMapped.shouldBeFalse() }
      withClue("HDR survived but the tone curve was reported") { run.toneCurve.shouldBeNull() }
    }

  /**
   * One export, with the file it wrote, whether an adjustment reported tone mapping, and the adjustment that names
   * Core Image's tone curve, if one did.
   */
  private class Run(
    val info: MediaInfo,
    val toneMapped: Boolean,
    val toneCurve: Adjustment?,
  )

  private fun composition(source: String) = compositionOf { clip(MediaSource.of(source)) }

  private fun spec(hdr: HdrMode) = ExportSpec(heightPx = 720, hdr = hdr)

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

    val success = finished.shouldBeInstanceOf<ExportStatus.Success>()
    // Read off Success rather than ExportStatus.Adjusted. Adjusted belongs to the overload that
    // plans as well, and this runs a plan the test already holds, so nothing here ever emits one
    // and a test that looked there would read every export as having reported nothing.
    val toneMapped = success.adjustments.any { it.kind == AdjustmentKind.HdrToneMapped }
    val toneCurve = success.adjustments.singleOrNull { it.message == CORE_IMAGE_TONE_CURVE.message }
    val path = success.output.shouldBeInstanceOf<MediaSink.Path>().path

    try {
      return Run(probe(path), toneMapped, toneCurve)
    } finally {
      NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }
  }

  private suspend fun probe(path: String): MediaInfo =
    filmstrip.probe(MediaSource.of(path)).shouldBeInstanceOf<ProbeResult.Success>().info

  private companion object {
    val TIMEOUT = 5.minutes

    // The adjustment naming Core Image's tone curve, reported under the mode that reached the tone map.
    fun curve(mode: HdrMode) =
      Adjustment(
        AdjustmentKind.EffectApproximated,
        mode.name,
        DegradationReason.ApproximateAlgorithm.name,
        CORE_IMAGE_TONE_CURVE.message,
      )
  }
}

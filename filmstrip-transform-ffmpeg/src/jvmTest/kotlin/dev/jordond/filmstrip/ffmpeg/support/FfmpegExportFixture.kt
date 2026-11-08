package dev.jordond.filmstrip.ffmpeg.support

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.color.brightness
import dev.jordond.filmstrip.effects.geometry.crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayAnimation
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.FfmpegConfig
import dev.jordond.filmstrip.ffmpeg.ffmpegBackend
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegPlanner
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegRuntime
import dev.jordond.filmstrip.ffmpeg.internal.lowering.Invocation
import dev.jordond.filmstrip.ffmpeg.internal.lowering.formatSeconds
import dev.jordond.filmstrip.ffmpeg.internal.process.ProcessRunner
import dev.jordond.filmstrip.ffmpeg.internal.process.ToolchainLocator
import dev.jordond.filmstrip.ffmpeg.internal.process.ToolchainResult
import dev.jordond.filmstrip.ffmpeg.internal.process.readablePath
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.HALF
import dev.jordond.filmstrip.ffmpeg.support.OverlayConstants.BAR_FILL
import dev.jordond.filmstrip.ffmpeg.support.OverlayConstants.OVERLAY_COLOR
import dev.jordond.filmstrip.ffmpeg.support.OverlayConstants.OVERLAY_SIZE
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.transform.internal.LoweredExport
import dev.jordond.filmstrip.transform.internal.copy.copyOpenings
import dev.jordond.filmstrip.transform.internal.negotiation.Ask
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import java.io.File
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * What every end-to-end export suite shares: the fixture clips, a [Filmstrip] on the ffmpeg backend, and the helpers
 * that plan an edit and export it. A suite holds one as a field, so each test instance gets its own.
 *
 * The clips are the published fixtures the jvmTest task downloads first, and [available] says whether they are there.
 * The task also runs [suiteToolchainProblem] before any test, so a host without a usable ffmpeg fails the build instead
 * of reaching a test here.
 */
internal class FfmpegExportFixture {
  internal val fixtures = File(System.getProperty("filmstrip.fixtures").orEmpty())
  internal val landscape = File(fixtures, "export_landscape.mp4")
  internal val portrait = File(fixtures, "export_portrait.mp4")
  internal val long = File(fixtures, "export_long.mp4")
  internal val bed = File(fixtures, "tone_bed_880.mp4")
  internal val hdr = File(fixtures, "export_hdr.mp4")
  internal val hdrHlg = File(fixtures, "export_hdr_hlg.mp4")

  internal val filmstrip = Filmstrip { ffmpegBackend() }

  /**
   * The plan for [composition], with a frame the encoder had to align allowed through.
   *
   * Scaling the long fixture to the spec's target height lands between two widths the encoder
   * accepts, which the planner reports as an adjustment and which none of these tests measure.
   */
  internal suspend fun plannedRun(
    composition: EditComposition,
    spec: ExportSpec,
  ): ExportPlan =
    when (val verdict = filmstrip.plan(composition, spec)) {
      is Verdict.Capable -> {
        verdict.plan
      }
      is Verdict.Degraded -> {
        verdict.plan.also {
          val frameOnly =
            verdict.adjustments.isNotEmpty() &&
              verdict.adjustments.all { adjustment -> adjustment.kind == AdjustmentKind.ResolutionClamped }
          withClue("the plan degraded for more than the frame: ${verdict.adjustments.map { it.message }}") {
            frameOnly.shouldBeTrue()
          }
        }
      }
      is Verdict.Incapable -> {
        error("the edit cannot run: ${verdict.reasons}")
      }
    }

  /**
   * Runs [plan] into [output] and hands back what the file it wrote says about itself.
   */
  internal suspend fun exported(
    plan: ExportPlan,
    output: File,
  ): MediaInfo {
    val finished = filmstrip.export(plan, MediaSink.of(output.absolutePath)).toList().last()
    if (finished is ExportStatus.Failure) error(finished.error.message)
    return finished.shouldBeInstanceOf<ExportStatus.Success>().info
  }

  /**
   * The tracks the planner resolved for [composition], so a reading is measured against the gain the
   * export ran rather than against a number written here.
   */
  internal suspend fun plannedTracks(
    composition: EditComposition,
    spec: ExportSpec,
    sources: List<MediaSource>,
  ): List<ResolvedTrack> =
    plannedLowering(composition, spec, sources)
      .export.composition
      .shouldNotBeNull()
      .tracks

  /**
   * What the planner lowered [composition] to, so a count is read off the composition it negotiated and the graph it
   * wrote rather than off a number worked out here.
   */
  internal suspend fun plannedLowering(
    composition: EditComposition,
    spec: ExportSpec,
    sources: List<MediaSource>,
  ): LoweredExport<Invocation> {
    val located = ToolchainLocator(FfmpegConfig(), ProcessRunner()).toolchain()
    val toolchain = located.shouldBeInstanceOf<ToolchainResult.Available>().toolchain
    val device = filmstrip.capabilities().shouldBeInstanceOf<CapabilitiesResult.Success>().capabilities
    val infos = sources.associateWith { filmstrip.probe(it).shouldBeInstanceOf<ProbeResult.Success>().info }
    val runtime = FfmpegRuntime.of(FfmpegConfig())
    val openings =
      copyOpenings(composition) { source, cut ->
        runtime.syncSampleAtOrBefore(toolchain, readablePath(source).shouldNotBeNull(), cut)
      }
    return FfmpegPlanner(toolchain, listOf(BuiltInEffectResolver()))
      .lower(composition, Ask(spec, device, infos, openings))
  }

  internal fun available(): Boolean = landscape.isFile && portrait.isFile

  /**
   * The decoded pixels of the one frame presented at [at], as RGB.
   *
   * Two decodes of the same picture agree byte for byte, so this is what says an output opened on
   * one source frame rather than another. Verified independently of the code under test.
   */
  internal fun frameAt(
    path: String,
    at: Duration,
  ): ByteArray {
    val process =
      ProcessBuilder(
        hostFfmpeg,
        "-hide_banner",
        "-loglevel",
        "error",
        "-ss",
        formatSeconds(at.toDouble(DurationUnit.SECONDS)),
        "-i",
        path,
        "-frames:v",
        "1",
        "-f",
        "rawvideo",
        "-pix_fmt",
        "rgb24",
        "-",
      ).start()
    val frame = process.inputStream.readBytes()
    process.waitFor()
    withClue("ffmpeg decoded no frame at $at of $path") { frame.isNotEmpty().shouldBeTrue() }
    return frame
  }

  /**
   * One export of the landscape fixture, with [overlay] drawn on its only clip or with nothing at
   * all.
   *
   * The plan comes back with it, because the output frame and the composition's length are what the
   * shared overlay sampler is asked against and reading them off the plan is what keeps a test from
   * restating a fixture's dimensions.
   */
  internal suspend fun exportedWatermark(
    name: String,
    overlay: ImageOverlay?,
    clips: Int = 1,
    onClip: Boolean = false,
    spec: ExportSpec = ExportSpec(),
  ): ExportedOverlay {
    val output = File.createTempFile(name, ".mp4").also { it.delete() }
    val composition =
      compositionOf {
        repeat(clips) { index ->
          clip(MediaSource.of(landscape.absolutePath)) {
            if (onClip && index == clips - 1) overlay?.let { mark -> effects { add(mark) } }
          }
        }
        if (!onClip) overlay?.let { mark -> effects { add(mark) } }
      }

    val plan =
      when (val verdict = filmstrip.plan(composition, spec)) {
        is Verdict.Capable -> verdict.plan
        is Verdict.Degraded -> verdict.plan
        is Verdict.Incapable -> error(verdict.reasons.joinToString { it.message })
      }
    val finished = filmstrip.export(plan, MediaSink.of(output.absolutePath)).toList().last()
    if (finished is ExportStatus.Failure) error(finished.error.message)
    finished.shouldBeInstanceOf<ExportStatus.Success>()

    return ExportedOverlay(output, plan)
  }

  /**
   * One export of the landscape fixture contained in a portrait frame over a [fill] fill, with [overlay] on the
   * composition or with nothing at all.
   *
   * [underFill] adds a brightness ahead of the overlay, which holds the fill back until it has run. [lead] starts the
   * primary that far in, leaving the fill to every slot ahead of it.
   */
  internal suspend fun exportedOverFill(
    name: String,
    overlay: ImageOverlay?,
    underFill: Boolean = false,
    lead: Duration = Duration.ZERO,
    fill: Int = BAR_FILL,
  ): ExportedOverlay {
    val output = File.createTempFile(name, ".mp4").also { it.delete() }
    val composition =
      compositionOf {
        track {
          clip(MediaSource.of(landscape.absolutePath))
          if (lead > Duration.ZERO) startAt(lead)
        }
        effects {
          crop(AspectRatio.Portrait, fit = Fit.Contain)
          if (underFill) brightness(HALF)
          overlay?.let { add(it) }
        }
        fill(Fill.Solid(fill))
      }

    val plan =
      when (val verdict = filmstrip.plan(composition, ExportSpec())) {
        is Verdict.Capable -> verdict.plan
        is Verdict.Degraded -> verdict.plan
        is Verdict.Incapable -> error(verdict.reasons.joinToString { it.message })
      }
    val finished = filmstrip.export(plan, MediaSink.of(output.absolutePath)).toList().last()
    if (finished is ExportStatus.Failure) error(finished.error.message)
    finished.shouldBeInstanceOf<ExportStatus.Success>()

    return ExportedOverlay(output, plan)
  }

  // A flat square in a color no bar of the testsrc fixture carries, so the blend under it is well
  // conditioned in every channel wherever the overlay lands.
  internal fun watermark(
    corner: Corner = Corner.TopStart,
    opacity: Float = 1f,
    animation: OverlayAnimation? = null,
    image: Size = OVERLAY_SIZE,
  ): ImageOverlay =
    ImageOverlay(
      image = ImageSource.ofBytes(watermarkPng(image)),
      corner = corner,
      opacity = opacity,
      animation = animation,
    )

  internal val watermarkPngs = mutableMapOf<Size, ByteArray>()

  internal fun watermarkPng(size: Size): ByteArray = watermarkPngs.getOrPut(size) { pngOf(OVERLAY_COLOR, size) }
}

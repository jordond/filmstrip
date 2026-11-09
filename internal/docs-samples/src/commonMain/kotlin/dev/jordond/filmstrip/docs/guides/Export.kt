package dev.jordond.filmstrip.docs.guides

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.codec.Bitrate
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.geometry.scale
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.export.VideoQuality
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource

internal fun spec(): ExportSpec {
  // region spec
  val spec =
    ExportSpec(
      heightPx = 720,
      bitrate = Bitrate.mbps(2),
      videoCodec = VideoCodec.H264,
      frameRate = 30,
      hdr = HdrMode.ToneMapToSdr,
    )
  // endregion
  return spec
}

internal fun quality(): ExportSpec {
  // region quality
  val draft = ExportSpec(heightPx = 480, quality = VideoQuality.Low)
  // endregion
  return draft
}

internal fun exactHeight(): Composition {
  // region exact-height
  val composition =
    compositionOf {
      clip(MediaSource.of("/path/to/720p.mp4"))
      effects { scale(1080) }
    }
  // endregion
  return composition
}

internal suspend fun planFirst(
  filmstrip: Filmstrip,
  composition: Composition,
): ExportPlan? {
  // region plan
  val plan: ExportPlan? =
    when (val verdict = filmstrip.plan(composition, ExportSpec.Upload)) {
      is Verdict.Capable -> {
        verdict.plan
      }
      is Verdict.Degraded -> {
        verdict.adjustments.forEach { println("Changed: ${it.message}") }
        verdict.plan
      }
      is Verdict.Incapable -> {
        verdict.reasons.forEach { println("Can't export: ${it.message}") }
        verdict.withoutUnsupported?.plan
      }
    }
  // endregion
  return plan
}

internal fun inertNotices(plan: ExportPlan) {
  // region inert-notices
  plan.inertNotices.forEach { notice ->
    println("Left out ${notice.subject}: ${notice.message}")
  }
  // endregion
}

internal suspend fun runPlan(
  filmstrip: Filmstrip,
  plan: ExportPlan,
) {
  // region run
  filmstrip.export(plan, MediaSink.temporary()).collect { status ->
    when (status) {
      ExportStatus.Started -> println("Started")
      is ExportStatus.Adjusted -> println("Adjusted: ${status.adjustments}")
      is ExportStatus.Progress -> println("${(status.fraction * 100).toInt()}%")
      is ExportStatus.Success -> println("Wrote ${status.output}, ${status.info.duration}")
      is ExportStatus.Failure -> println("Failed: ${status.error.message}")
    }
  }
  // endregion
}

internal fun describeFailure(failure: ExportStatus.Failure): String {
  // region failure
  val reason =
    when (val error = failure.error) {
      is FilmstripError.BackendMissing -> "Add ${error.artifact} to your dependencies"
      is FilmstripError.ToolchainMissing -> "Install ffmpeg: ${error.message}"
      is FilmstripError.InsufficientStorage -> "Free up some space and try again"
      is FilmstripError.TimedOut -> "The ${error.operation} took longer than ${error.deadline}, try again"
      else -> error.message
    }
  // endregion
  return reason
}

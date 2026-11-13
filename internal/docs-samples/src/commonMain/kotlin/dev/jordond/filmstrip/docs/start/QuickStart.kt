package dev.jordond.filmstrip.docs.start

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource

internal suspend fun quickStart(path: String) {
  // region quick-start
  val filmstrip = Filmstrip()

  val composition =
    compositionOf {
      clip(MediaSource.of(path))
    }

  val plan =
    when (val verdict = filmstrip.plan(composition, ExportSpec.Upload)) {
      is Verdict.Capable -> verdict.plan
      is Verdict.Degraded -> verdict.plan
      is Verdict.Incapable -> return println("Can't export on this device: ${verdict.reasons}")
    }

  filmstrip.export(plan, MediaSink.temporary()).collect { status ->
    when (status) {
      is ExportStatus.Progress -> println("${(status.fraction * 100).toInt()}%")
      is ExportStatus.Success -> println("Wrote ${status.output}")
      is ExportStatus.Failure -> println("Failed: ${status.reasons}")
      else -> Unit
    }
  }
  // endregion
}

package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.media.MediaSink
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext

/**
 * Plans [composition] against [spec] and exports it to [output], off the test's own dispatcher.
 *
 * Fails the calling test when the plan is incapable or the export finishes with anything but a success, and hands back
 * that success.
 */
internal suspend fun Filmstrip.exportOrFail(
  composition: Composition,
  spec: ExportSpec,
  output: String,
): ExportStatus.Success =
  withContext(Dispatchers.Default) {
    when (val verdict = plan(composition, spec)) {
      is Verdict.Incapable -> error(verdict.reasons.joinToString { it.message })
      is Verdict.Capable, is Verdict.Degraded -> Unit
    }

    val statuses = export(composition, spec, MediaSink.of(output)).toList()
    when (val finished = statuses.last()) {
      is ExportStatus.Failure -> error(finished.error.message)
      else -> finished.shouldBeInstanceOf<ExportStatus.Success>()
    }
  }

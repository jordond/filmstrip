package dev.jordond.filmstrip.sample

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.compose.overlay.OverlayBaker
import dev.jordond.filmstrip.compose.overlay.planWithOverlays
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.export.Adjustment
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The plan and the export of the edit, and what came back from each.
 *
 * @param release Hands back an export's output once the session has moved past it.
 * @param composition Builds the edit to plan, or null while there is nothing to export.
 * @param spec The export settings a plan is asked for.
 * @param overlays Bakes the story elements into overlays on the output frame it is given, empty when there are none.
 * @param onStart Runs as an export begins, before anything is written.
 * @param onSucceeded Runs once an export has finished and been probed.
 */
@Stable
public class ExportSession internal constructor(
  private val filmstrip: Filmstrip,
  private val scope: CoroutineScope,
  private val recorder: DiagnosticsRecorder,
  private val release: (MediaSink) -> Unit,
  private val composition: () -> Composition?,
  private val spec: () -> ExportSpec,
  private val overlays: OverlayBaker,
  private val onStart: () -> Unit,
  private val onSucceeded: () -> Unit,
) {
  var planning: Boolean by mutableStateOf(false)
    private set
  var verdict: Verdict? by mutableStateOf(null)
    private set

  var exporting: Boolean by mutableStateOf(false)
    private set
  var exportProgress: ExportStatus.Progress? by mutableStateOf(null)
    private set
  var exportAdjustments: List<Adjustment> by mutableStateOf(emptyList())
    private set
  var exported: ExportStatus.Success? by mutableStateOf(null)
    internal set
  var exportedInfo: ProbeResult? by mutableStateOf(null)
    private set
  var exportFailure: FilmstripError? by mutableStateOf(null)
    private set

  /**
   * Why the last plan could not be asked for, which is a story element that failed to bake.
   */
  var planFailure: String? by mutableStateOf(null)
    private set

  private var exportJob: Job? = null
  private var exportGeneration = 0

  fun plan() {
    if (planning) return
    val composition = composition() ?: return
    verdict = null
    planFailure = null
    planning = true
    scope.launch {
      try {
        val result = filmstrip.planWithOverlays(composition, spec(), overlays)
        verdict = result
        recorder.record("plan.done", "verdict" to (result::class.simpleName ?: "unknown"))
      } catch (failure: IllegalStateException) {
        // A bake fails this way when the stage is gone or was never drawn.
        planFailure = failure.message ?: "A story element did not bake."
        recorder.record("plan.failed", "error" to planFailure.orEmpty())
      } finally {
        planning = false
      }
    }
  }

  fun export(plan: ExportPlan) {
    exportJob?.cancel()
    clearExported()
    exportFailure = null
    exportProgress = null
    // Running a plan emits no ExportStatus.Adjusted, since the verdict this plan came from already
    // carried the list. Reading it off the verdict is what puts the tone map in front of whoever
    // pressed export rather than only in the diagnostics report.
    exportAdjustments = (verdict as? Verdict.Degraded)?.adjustments.orEmpty()
    onStart()

    // The flag is flipped here rather than inside the coroutine, and cleared only by the run that
    // still owns it. cancel() above does not wait, so a superseded run's finally lands after the
    // replacement has started and would otherwise report the new export as finished.
    val generation = ++exportGeneration
    exporting = true
    exportJob =
      scope.launch {
        try {
          filmstrip.export(plan, exportSink()).collect { status ->
            when (status) {
              is ExportStatus.Started -> {
                recorder.record("export.started", "path" to plan.path.name)
              }
              is ExportStatus.Adjusted -> {
                exportAdjustments = status.adjustments
                recorder.record(
                  label = "export.adjusted",
                  detail =
                    status.adjustments
                      .groupBy({ it.kind.name }, { "${it.requested} -> ${it.resolved}" })
                      .mapValues { (_, values) -> values.joinToString(" | ") },
                )
              }
              is ExportStatus.Progress -> {
                exportProgress = status
              }
              is ExportStatus.Success -> {
                exported = status
                // What the backend gave up on once it had the real encoders is only on Success, so
                // this is the list rather than an addition to it.
                exportAdjustments = status.adjustments
                saveExport(status.output)
                exportedInfo = filmstrip.probe(status.output.asSource())
                recorder.record("export.succeeded", "output" to status.info.toString())
                onSucceeded()
              }
              is ExportStatus.Failure -> {
                exportFailure = status.error
                recorder.record(
                  label = "export.failed",
                  detail =
                    mapOf(
                      "error" to (status.error::class.simpleName ?: "unknown"),
                      "message" to status.error.message,
                    ),
                )
              }
            }
          }
        } finally {
          if (generation == exportGeneration) exporting = false
        }
      }
  }

  fun cancelExport() {
    exportJob?.cancel()
  }

  private fun clearExported() {
    exported?.let { release(it.output) }
    exported = null
    exportedInfo = null
  }

  /**
   * Drops the plan and the export, which is what an edit or a new clip invalidates.
   */
  internal fun reset() {
    verdict = null
    planFailure = null
    clearExported()
    exportFailure = null
    exportProgress = null
    exportAdjustments = emptyList()
  }

  private fun MediaSink.asSource(): MediaSource =
    when (this) {
      is MediaSink.Path -> MediaSource.of(path)
      is MediaSink.Uri -> MediaSource.ofUri(uri)
      MediaSink.Temporary -> error("A resolved temporary sink reports a path or uri.")
    }
}

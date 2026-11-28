package dev.jordond.filmstrip.sample.stories

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.compose.overlay.OverlayBaker
import dev.jordond.filmstrip.compose.overlay.planWithOverlays
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.sample.exportRefusalOf
import dev.jordond.filmstrip.sample.exportSink
import dev.jordond.filmstrip.sample.releaseExport
import dev.jordond.filmstrip.sample.saveExport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * How a story is exported: no taller than [STORY_MAX_HEIGHT], everything else left to the backend.
 */
internal val STORY_SPEC: ExportSpec = ExportSpec(heightPx = STORY_MAX_HEIGHT)

/**
 * Where an export stands.
 */
internal sealed interface ExportPhase {
  data object Idle : ExportPhase

  data object Preparing : ExportPhase

  data class Running(
    val fraction: Float,
  ) : ExportPhase

  data class Done(
    val output: MediaSink,
  ) : ExportPhase

  data class Failed(
    val message: String,
  ) : ExportPhase
}

/**
 * Runs a story's export and reports where it stands.
 *
 * @param filmstrip Plans and exports.
 * @param scope Runs the export, and cancels it with whatever owns the scope.
 */
@Stable
internal class StoryExport(
  private val filmstrip: Filmstrip,
  private val scope: CoroutineScope,
) {
  var phase: ExportPhase by mutableStateOf(ExportPhase.Idle)
    private set

  private var job: Job? = null

  /**
   * Exports [composition] with the story [overlays] bakes drawn over it. Nothing happens while an export is already
   * running.
   *
   * @param overlays Bakes the story's elements, which is the stage's state in the app.
   */
  fun start(
    composition: Composition,
    overlays: OverlayBaker,
  ) {
    if (job?.isActive == true) return
    release()
    phase = ExportPhase.Preparing
    job =
      scope.launch {
        try {
          run(composition, overlays)
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (failure: IllegalStateException) {
          phase = ExportPhase.Failed(failure.message ?: "A story element did not bake.")
        }
      }
  }

  /**
   * Stops a running export and lets go of a finished one's file.
   */
  fun close() {
    job?.cancel()
    job = null
    release()
    phase = ExportPhase.Idle
  }

  private suspend fun run(
    composition: Composition,
    overlays: OverlayBaker,
  ) {
    exportRefusalOf(filmstrip.capabilities())?.let {
      phase = ExportPhase.Failed(it)
      return
    }
    val verdict = filmstrip.planWithOverlays(composition, STORY_SPEC, overlays)
    val plan = verdict.runnable()
    if (plan == null) {
      phase = ExportPhase.Failed(verdict.refusal())
      return
    }
    filmstrip.export(plan, exportSink()).collect { status ->
      when (status) {
        is ExportStatus.Progress -> {
          phase = ExportPhase.Running(status.fraction)
        }
        is ExportStatus.Success -> {
          saveExport(status.output)
          phase = ExportPhase.Done(status.output)
        }
        is ExportStatus.Failure -> {
          phase = ExportPhase.Failed(status.reasons.firstOrNull()?.message ?: FAILED)
        }
        else -> {
          Unit
        }
      }
    }
  }

  private fun release() {
    (phase as? ExportPhase.Done)?.let { releaseExport(it.output) }
  }
}

private fun Verdict.runnable(): ExportPlan? =
  when (this) {
    is Verdict.Capable -> plan
    is Verdict.Degraded -> plan
    is Verdict.Incapable -> withoutUnsupported?.plan
  }

private fun Verdict.refusal(): String = (this as? Verdict.Incapable)?.reasons?.firstOrNull()?.message ?: FAILED

private const val FAILED = "The export failed."

package dev.jordond.filmstrip.sample.stories

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.compose.overlay.OverlayCanvas
import dev.jordond.filmstrip.compose.overlay.OverlayCapturer
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.sample.exportRefusalOf
import dev.jordond.filmstrip.sample.exportSink
import dev.jordond.filmstrip.sample.releaseExport
import dev.jordond.filmstrip.sample.saveExport
import dev.jordond.filmstrip.sample.stories.ui.StoryElementContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * How a story is exported: no taller than [STORY_MAX_HEIGHT], everything else left to the backend.
 */
internal val STORY_SPEC: ExportSpec = ExportSpec(heightPx = STORY_MAX_HEIGHT)

/**
 * Bakes the story into overlays on [output], the drawing first and then every element from the bottom of the stack
 * to the top, so they composite in the order the stage draws them.
 *
 * Each element is drawn by [StoryElementContent], the composable the stage draws it with, and carries its window and
 * its entrance. An element wholly off the frame and an empty drawing are left out.
 *
 * @param capturer Draws the captures, with its host mounted.
 * @param canvas The canvas the elements were laid out on while editing.
 * @param output The frame the story is exported at, in pixels.
 */
internal suspend fun bakeStory(
  capturer: OverlayCapturer,
  elements: List<StoryElement>,
  strokes: List<StoryStroke>,
  canvas: OverlayCanvas,
  output: Size,
): List<ImageOverlay> =
  buildList {
    bakeDrawing(capturer, strokes, canvas, output)?.let(::add)
    elements.forEach { element ->
      capturer
        .capture(
          transform = element.transform,
          canvas = canvas,
          output = output,
          visibleDuring = element.window,
          animation = element.animation,
        ) { StoryElementContent(element) }
        ?.let(::add)
    }
  }

/**
 * Plans [composition] with the story baked onto it.
 *
 * The elements are baked onto the frame the edit exports at, which only a plan knows. So this plans once for the
 * frame, hands it to [bake], and plans again with what comes back. A story with nothing to bake keeps the first plan.
 *
 * @param bake Bakes the story on the output frame it is given.
 */
internal suspend fun planStory(
  filmstrip: Filmstrip,
  composition: Composition,
  bake: suspend (Size) -> List<EffectSpec>,
): Verdict {
  val first = filmstrip.plan(composition, STORY_SPEC)
  val output = first.runnable()?.output?.size ?: return first
  val baked = bake(output)
  if (baked.isEmpty()) return first
  return filmstrip.plan(composition.withEffects(composition.effects + baked), STORY_SPEC)
}

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
   * Exports [composition] with what [bake] makes of the story. Nothing happens while an export is already running.
   */
  fun start(
    composition: Composition,
    bake: suspend (Size) -> List<EffectSpec>,
  ) {
    if (job?.isActive == true) return
    release()
    phase = ExportPhase.Preparing
    job =
      scope.launch {
        try {
          run(composition, bake)
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
    bake: suspend (Size) -> List<EffectSpec>,
  ) {
    exportRefusalOf(filmstrip.capabilities())?.let {
      phase = ExportPhase.Failed(it)
      return
    }
    val verdict = planStory(filmstrip, composition, bake)
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

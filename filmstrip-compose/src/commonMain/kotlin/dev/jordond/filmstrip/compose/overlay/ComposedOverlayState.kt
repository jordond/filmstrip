package dev.jordond.filmstrip.compose.overlay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.platform.LocalGraphicsContext
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.geometry.Size
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * The composed overlay elements a stage is drawing, baked for an export without declaring them again.
 *
 * The stage given this state records each element it draws, and [bake] replays those recordings on the output frame.
 * The state is a plain class, so a view model or a dependency graph can own it, hand it to the stage and keep only an
 * [OverlayBaker] where it exports. It draws on one stage at a time, and once that stage leaves composition it holds
 * nothing the stage made.
 *
 * @property canvas The canvas every element on the stage is laid out on.
 */
@Stable
public class ComposedOverlayState(
  public val canvas: OverlayCanvas = OverlayCanvas(),
) : OverlayBaker {
  private val turn = Mutex()

  internal val recordings: MutableList<RecordedElement> = mutableListOf()

  internal var request: BakeRequest? by mutableStateOf(null)
    private set

  internal var hosts: Int by mutableStateOf(0)

  // Every element's draw reads this, so a bake can ask the stage for one more draw of all of them.
  internal val redraw = mutableIntStateOf(0)

  // Moves whenever an element draws or leaves, which is when a waiting bake looks at the elements again.
  internal val draws = MutableStateFlow(0)

  internal var orders: Int = 0

  /**
   * Bakes every element the stage is drawing for a frame of [output].
   *
   * The stage draws each element once more and that drawing is what gets baked, so a change made just before the call
   * is in the result. An element the stage holds but does not show is baked like any other. The pictures are encoded
   * off the main thread.
   *
   * May be called from any coroutine. Cancelling the caller cancels the bake, and bakes run one at a time.
   *
   * @param output The output frame the overlays are drawn on, in pixels. This is the export plan's output size.
   * @return The overlays in the order the stage draws them, bottom first. An element with none of itself on the frame
   * is left out, and a stage with no elements gives an empty list.
   * @throws IllegalStateException When no stage is in composition for this state, the stage leaves composition before
   * the bake finishes, or the stage is not drawn within 10 seconds.
   */
  override suspend fun bake(output: Size): List<ImageOverlay> {
    check(hosts > 0) { NO_STAGE }
    return turn.withLock {
      // The stage may have left while this bake waited its turn.
      check(hosts > 0) { NO_STAGE }
      val pending = BakeRequest(output)
      request = pending
      try {
        pending.result.awaitWhileHosted({ hosts }, STAGE_LEFT)
      } finally {
        request = null
      }
    }
  }
}

/**
 * Remembers a [ComposedOverlayState] for the life of the calling composition.
 *
 * Use it where the screen itself exports. A view model that exports builds its own state and passes it to the stage.
 *
 * @param canvas The canvas every element on the stage is laid out on.
 */
@Composable
public fun rememberComposedOverlayState(canvas: OverlayCanvas = OverlayCanvas()): ComposedOverlayState =
  remember(canvas) { ComposedOverlayState(canvas) }

/**
 * Runs this state's bakes for the stage that calls it, and marks the state as drawn by that stage.
 *
 * A stage calls it once, next to the elements it registers with [rememberRecording]. It shows nothing.
 */
@InternalFilmstripApi
@Composable
public fun ComposedOverlayState.BakeHost() {
  val state = this
  DisposableEffect(state) {
    check(state.hosts == 0) { SECOND_STAGE }
    state.hosts += 1
    onDispose { state.hosts -= 1 }
  }
  val graphics = LocalGraphicsContext.current
  state.request?.let { pending -> key(pending) { Bake(state, pending, graphics) } }
}

@Composable
private fun Bake(
  state: ComposedOverlayState,
  pending: BakeRequest,
  graphics: GraphicsContext,
) {
  DisposableEffect(pending) {
    onDispose { pending.result.completeExceptionally(IllegalStateException(STAGE_LEFT)) }
  }
  LaunchedEffect(pending) {
    try {
      pending.result.complete(state.bakeDrawn(pending.output, graphics))
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (failure: Throwable) {
      // A failure belongs to the caller of the bake, never to the composition the stage sits in.
      pending.result.completeExceptionally(failure)
    }
  }
}

// Asks every element for a draw, waits until each has drawn after the request, then replays them bottom first.
private suspend fun ComposedOverlayState.bakeDrawn(
  output: Size,
  graphics: GraphicsContext,
): List<ImageOverlay> {
  val asked = ++redraw.intValue
  // Draws stop for a stage that is off screen, so the wait is bounded.
  withTimeoutOrNull(DRAW_TIMEOUT) { draws.first { recordings.all { it.drawnSince(asked) } } }
    ?: throw IllegalStateException(STAGE_NOT_DRAWN)
  return recordings
    .sortedBy { it.order }
    .mapNotNull { element ->
      bakeRecorded(element.layer, element.size, element.transform, canvas, output, graphics)
        ?.toImageOverlay(output, element.opacity, element.visibleDuring, element.animation)
    }
}

/**
 * Waits for the result while something is hosting the work, and fails with [left] once [hosts] reads zero.
 *
 * A host that leaves before it composes the request never disposes anything to fail it, so this watches for it.
 */
internal suspend fun <T> CompletableDeferred<T>.awaitWhileHosted(
  hosts: () -> Int,
  left: String,
): T =
  coroutineScope {
    val watch =
      launch {
        snapshotFlow(hosts).first { it == 0 }
        completeExceptionally(IllegalStateException(left))
      }
    try {
      await()
    } finally {
      watch.cancel()
    }
  }

/**
 * One bake waiting on the stage, completed with the overlays in draw order.
 */
internal class BakeRequest(
  val output: Size,
) {
  val result: CompletableDeferred<List<ImageOverlay>> = CompletableDeferred()
}

internal const val NO_STAGE =
  "A ComposedOverlayState needs a stage in composition drawing its elements, and none was given this state."

internal const val STAGE_LEFT = "The stage left composition before the bake finished."

internal const val STAGE_NOT_DRAWN =
  "The stage was not drawn within 10 seconds. A stage off screen or sized to nothing is never drawn, and an " +
    "OverlayCapturer bakes content that is on no stage."

internal const val SECOND_STAGE =
  "A ComposedOverlayState draws on one stage at a time, and this one was given to a second stage."

private val DRAW_TIMEOUT = 10.seconds

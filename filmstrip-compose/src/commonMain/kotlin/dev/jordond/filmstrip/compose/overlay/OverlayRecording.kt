package dev.jordond.filmstrip.compose.overlay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.IntSize
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effects.overlay.OverlayAnimation
import kotlinx.coroutines.flow.update
import kotlin.math.roundToInt

/**
 * One element a stage is drawing for a [ComposedOverlayState], with the layer its content is recorded into.
 *
 * A stage gets one from [rememberRecording], puts [overlayDrawOrder] on the element's outermost node and
 * [overlayRecord] on the node that holds its content.
 */
@InternalFilmstripApi
public sealed interface OverlayRecording

@OptIn(InternalFilmstripApi::class)
internal class RecordedElement(
  val state: ComposedOverlayState,
  val layer: GraphicsLayer,
) : OverlayRecording {
  var transform: OverlayTransform = OverlayTransform()
  var opacity: Float = 1f
  var visibleDuring: TimeRange? = null
  var animation: OverlayAnimation? = null

  var size: IntSize = IntSize.Zero

  // The redraw request the content was last recorded after, and the one the element's place in the order was taken on.
  var recorded: Int = -1
  var ordered: Int = -1
  var order: Int = 0

  fun drawnSince(asked: Int): Boolean = recorded >= asked && ordered >= asked
}

/**
 * Registers one element with this state for as long as the call stays in composition.
 *
 * The values are what the bake puts on the element's overlay, read when the bake runs.
 *
 * @param transform Where the element sits on the output frame, how big and how far turned.
 * @param opacity Alpha applied to the baked overlay, in `0f..1f`.
 * @param visibleDuring When the baked overlay is visible, or null for the whole composition.
 * @param animation How the baked overlay is drawn at each frame of its run, or null to hold it still.
 */
@InternalFilmstripApi
@Composable
public fun ComposedOverlayState.rememberRecording(
  transform: OverlayTransform,
  opacity: Float,
  visibleDuring: TimeRange?,
  animation: OverlayAnimation?,
): OverlayRecording {
  val state = this
  val layer = rememberGraphicsLayer()
  val recording = remember(state, layer) { RecordedElement(state, layer) }
  SideEffect {
    recording.transform = transform
    recording.opacity = opacity
    recording.visibleDuring = visibleDuring
    recording.animation = animation
  }
  DisposableEffect(recording) {
    state.recordings += recording
    onDispose {
      state.recordings -= recording
      // A bake waiting on this element's draw looks again and finds it gone.
      state.draws.update { it + 1 }
    }
  }
  return recording
}

/**
 * Takes the element's place in the draw order each time a bake asks the stage to draw.
 *
 * It belongs on the element's outermost node, ahead of any layer the element is drawn through. A layer's content is
 * recorded apart from the pass that draws it, so only a node in the parent's own pass runs in the order the parent
 * draws its children. A null [recording] is an element that is never baked.
 */
@InternalFilmstripApi
public fun Modifier.overlayDrawOrder(recording: OverlayRecording?): Modifier =
  if (recording == null) {
    this
  } else {
    val element = recording.element()
    drawWithContent {
      val state = element.state
      val asked = state.redraw.intValue
      // Only the first draw after a request counts, since that pass draws every element and a later one may not.
      if (asked > element.ordered) {
        element.ordered = asked
        element.order = ++state.orders
        state.draws.update { it + 1 }
      }
      drawContent()
    }
  }

/**
 * Records the node's content into the element's layer on every draw, and draws it to the screen only when [shown].
 *
 * It belongs on the node that holds the element's content and nothing else, so that is all a bake replays. A null
 * [recording] is an element that is never baked, which is drawn straight to the screen when [shown].
 */
@InternalFilmstripApi
public fun Modifier.overlayRecord(
  recording: OverlayRecording?,
  shown: Boolean,
): Modifier =
  drawWithContent {
    val element = recording?.element()
    if (element == null) {
      if (shown) drawContent()
      return@drawWithContent
    }
    val asked = element.state.redraw.intValue
    element.layer.record { this@drawWithContent.drawContent() }
    element.size = IntSize(size.width.roundToInt(), size.height.roundToInt())
    element.recorded = asked
    element.state.draws.update { it + 1 }
    if (shown) drawLayer(element.layer)
  }

@OptIn(InternalFilmstripApi::class)
private fun OverlayRecording.element(): RecordedElement =
  when (this) {
    is RecordedElement -> this
  }

package dev.jordond.filmstrip.compose.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.compose.ui.OverlayHandleColors
import dev.jordond.filmstrip.compose.ui.OverlayHandleDefaults
import dev.jordond.filmstrip.compose.ui.VideoStage
import dev.jordond.filmstrip.compose.ui.VideoStageScope
import dev.jordond.filmstrip.effects.OverlayPlacement
import dev.jordond.filmstrip.effects.rectOn
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Size
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A draggable box over the video a [VideoStage] is showing, drawn where the overlay it stands for really lands.
 *
 * Controlled, so a drag reports the frame anchor it wants through [onFrameAnchorChange] and the box moves only when a
 * [placement] carrying that anchor comes back. A host that drops the change draws a handle that does not move.
 *
 * The box is [placement] resolved against [output] and mapped through the stage's own letterbox, so a handle sits over
 * an overlay the effect chain has already burnt into the picture rather than beside it. [placement] and [output] are
 * the pair `placedOn` produced and the frame it was given, and only the frame's proportions are read, so a stage
 * measured in view pixels answers the same as the composition's output frame.
 *
 * A drag keeps the point the overlay anchors to inside the frame, and the overlay's own centre inside it, which leaves
 * at least a quarter of the overlay over the picture at the far corners.
 *
 * [content] is the host's stand-in for the overlay, its text or its image, drawn inside the box. The handle draws the
 * wash, the outline and the corner brackets, never the payload.
 *
 * ```
 * val placement = remember(text, anchor, measured) { Text(text, anchor = anchor).placedOn(measured) }
 *
 * VideoStage(player = player, outputAspect = outputAspect) {
 *   OverlayHandle(
 *     placement = placement,
 *     onFrameAnchorChange = { anchor = it },
 *     output = outputSize,
 *   )
 * }
 * ```
 *
 * @param placement Where the overlay lands, from `placedOn`.
 * @param onFrameAnchorChange Called with the frame anchor a drag asks for, already held on the frame.
 * @param output The frame [placement] was resolved against, in pixels.
 * @param modifier Modifier for the handle, which fills the stage and places the box inside itself.
 * @param colors What the handle paints with.
 * @param handleLength How long a corner bracket's arms are drawn.
 * @param enabled Whether the handle answers a drag and draws its brackets.
 * @param content The host's stand-in for the overlay, drawn inside the box.
 */
@Composable
public fun VideoStageScope.OverlayHandle(
  placement: OverlayPlacement,
  onFrameAnchorChange: (Anchor) -> Unit,
  output: Size,
  modifier: Modifier = Modifier,
  colors: OverlayHandleColors = OverlayHandleDefaults.Palette,
  handleLength: Dp = OverlayHandleDefaults.HandleLength,
  enabled: Boolean = true,
  content: @Composable () -> Unit = { },
) {
  val stageFrame = frame
  val view = remember(placement, output, stageFrame) { stageFrame.toView(placement.rectOn(output)) }
  if (view.width <= 0f || view.height <= 0f) return

  val density = LocalDensity.current
  val handleLengthPx = with(density) { handleLength.toPx() }
  val currentView by rememberUpdatedState(view)
  val currentPlacement by rememberUpdatedState(placement)
  val currentOutput by rememberUpdatedState(output)
  val currentOnChange by rememberUpdatedState(onFrameAnchorChange)

  val contentRect = stageFrame.contentRect
  val drag =
    if (!enabled) {
      Modifier
    } else {
      // The gesture sits on the layer filling the stage rather than on the box, which moves out from under the finger
      // as the host applies each report and loses the drag with it.
      Modifier.pointerInput(contentRect) {
        var origin = Anchor.Center
        var total = Offset.Zero
        var grabbed = false

        detectDragGestures(
          onDragStart = { position ->
            grabbed = currentView.contains(position)
            origin = currentPlacement.frameAnchor
            total = Offset.Zero
          },
          onDragEnd = { grabbed = false },
          onDragCancel = { grabbed = false },
        ) { change, amount ->
          if (!grabbed || contentRect.width <= 0f || contentRect.height <= 0f) return@detectDragGestures
          change.consume()
          total += amount

          val proposed =
            Anchor(
              x = origin.x + total.x / contentRect.width,
              y = origin.y + total.y / contentRect.height,
            )
          currentOnChange(currentPlacement.heldOnFrame(proposed, currentOutput))
        }
      }
    }

  Box(modifier.fillMaxSize().then(drag)) {
    Box(
      modifier =
        Modifier
          .align(Alignment.TopStart)
          .offset { IntOffset(view.left.roundToInt(), view.top.roundToInt()) }
          .requiredSize(with(density) { view.width.toDp() }, with(density) { view.height.toDp() }),
      contentAlignment = Alignment.Center,
    ) {
      content()

      Canvas(Modifier.matchParentSize()) {
        drawRect(colors.fill)
        drawRect(colors.outline, style = Stroke(width = OUTLINE_WIDTH))
        if (enabled) drawBrackets(min(handleLengthPx, min(size.width, size.height) / BRACKET_LIMIT), colors.handle)
      }
    }
  }
}

/**
 * [proposed] held where the overlay stays on the frame.
 *
 * The point the overlay anchors to stays inside the frame and so does the overlay's own centre, so the furthest a drag
 * can push an overlay is a corner, where a quarter of it is still drawn.
 */
internal fun OverlayPlacement.heldOnFrame(
  proposed: Anchor,
  output: Size,
): Anchor {
  val width = if (output.width <= 0) 0f else size.width.toFloat() / output.width
  val height = if (output.height <= 0) 0f else size.height.toFloat() / output.height
  val toCenterX = (HALF - overlayAnchor.x) * width
  val toCenterY = (HALF - overlayAnchor.y) * height

  return Anchor(
    x = proposed.x.heldWithin(-toCenterX, 1f - toCenterX),
    y = proposed.y.heldWithin(-toCenterY, 1f - toCenterY),
  )
}

/**
 * [this] inside both `0f..1f` and [low]`..`[high], or the middle of what is left where the two do not overlap, which
 * is an overlay drawn larger than the frame it sits on.
 */
private fun Float.heldWithin(
  low: Float,
  high: Float,
): Float {
  val lower = maxOf(0f, low)
  val upper = minOf(1f, high)
  return if (lower > upper) (lower + upper) / 2f else coerceIn(lower, upper)
}

/**
 * An inward bracket of [length] at each corner of the box being drawn.
 */
private fun DrawScope.drawBrackets(
  length: Float,
  color: Color,
) {
  drawBracket(Offset.Zero, Offset(1f, 1f), length, color)
  drawBracket(Offset(size.width, 0f), Offset(-1f, 1f), length, color)
  drawBracket(Offset(size.width, size.height), Offset(-1f, -1f), length, color)
  drawBracket(Offset(0f, size.height), Offset(1f, -1f), length, color)
}

/**
 * Two arms of [length] meeting at [corner], pointing inward along [direction].
 */
private fun DrawScope.drawBracket(
  corner: Offset,
  direction: Offset,
  length: Float,
  color: Color,
) {
  drawLine(color, corner, corner + Offset(direction.x * length, 0f), strokeWidth = BRACKET_WIDTH)
  drawLine(color, corner, corner + Offset(0f, direction.y * length), strokeWidth = BRACKET_WIDTH)
}

@Preview
@Composable
private fun OverlayHandlePreview() {
  PreviewSurface(height = PreviewHandleHeight) {
    VideoStage(player = null, outputAspect = 16f / 9f) {
      OverlayHandle(
        placement =
          OverlayPlacement(
            size = Size(96, 40),
            overlayAnchor = Anchor.BottomEnd,
            frameAnchor = Anchor(0.94f, 0.9f),
          ),
        onFrameAnchorChange = { },
        output = Size(320, 180),
      )
    }
  }
}

private val PreviewHandleHeight = 180.dp
private const val OUTLINE_WIDTH = 1.5f
private const val BRACKET_WIDTH = 3f
private const val HALF = 0.5f

/**
 * How much of the box's shorter side a bracket arm may take, so a small overlay is not drawn as four crossing arms.
 */
private const val BRACKET_LIMIT = 3f

package dev.jordond.filmstrip.sample.stories

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.compose.overlay.OverlayCanvas
import dev.jordond.filmstrip.compose.overlay.OverlayCapturer
import dev.jordond.filmstrip.compose.overlay.OverlayTransform
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Size

/**
 * What a stroke is drawn with. The eraser clears what is under it, strokes drawn before it only.
 */
internal enum class StoryBrush { Pen, Marker, Glow, Eraser }

/**
 * One stroke of the drawing.
 *
 * @property points The path, each point a fraction of the frame, so a stroke lands the same on any stage size.
 * @property brush What the stroke is drawn with.
 * @property color The stroke's colour as ARGB. An eraser ignores it.
 * @property widthDp How wide the stroke is, in dp of the overlay canvas.
 */
internal data class StoryStroke(
  val points: List<Anchor>,
  val brush: StoryBrush = StoryBrush.Pen,
  val color: Int = StoryContent.WHITE,
  val widthDp: Float = DEFAULT_WIDTH_DP,
) {
  companion object {
    const val DEFAULT_WIDTH_DP: Float = 6f
  }
}

/**
 * Draws [strokes] over the whole frame, in the order they were made.
 *
 * It fills the canvas it is laid out on, so placed with [DRAWING_TRANSFORM] it covers the frame exactly, on the stage
 * and in the capture alike. The strokes are drawn into a layer of their own, so an eraser clears the strokes under it
 * and never the picture.
 */
@Composable
internal fun StoryDrawingContent(strokes: List<StoryStroke>) {
  Canvas(Modifier.fillMaxSize().graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
    strokes.forEach { drawStroke(it) }
  }
}

/**
 * Bakes [strokes] into one overlay covering [output], or null when there is nothing drawn.
 *
 * @param capturer Draws the capture, with its host mounted.
 * @param canvas The canvas the story's elements are laid out on.
 * @param output The frame the story is exported at, in pixels.
 */
internal suspend fun bakeDrawing(
  capturer: OverlayCapturer,
  strokes: List<StoryStroke>,
  canvas: OverlayCanvas,
  output: Size,
): ImageOverlay? {
  if (strokes.isEmpty()) return null
  return capturer.capture(DRAWING_TRANSFORM, canvas, output) { StoryDrawingContent(strokes) }
}

/**
 * Where the drawing sits, centred and at the canvas's own size, which is the whole frame.
 */
internal val DRAWING_TRANSFORM: OverlayTransform = OverlayTransform()

private fun DrawScope.drawStroke(stroke: StoryStroke) {
  val width = stroke.widthDp.dp.toPx()
  val color = Color(stroke.color)
  when (stroke.brush) {
    StoryBrush.Pen -> {
      drawPoints(stroke, color, width)
    }
    StoryBrush.Marker -> {
      drawPoints(stroke, color.copy(alpha = MARKER_ALPHA), width * MARKER_WIDTH, StrokeCap.Square)
    }
    StoryBrush.Glow -> {
      drawPoints(stroke, color.copy(alpha = GLOW_ALPHA), width * GLOW_WIDTH)
      drawPoints(stroke, Color.White, width * GLOW_CORE)
    }
    StoryBrush.Eraser -> {
      drawPoints(stroke, Color.Black, width * ERASER_WIDTH, blendMode = BlendMode.Clear)
    }
  }
}

private fun DrawScope.drawPoints(
  stroke: StoryStroke,
  color: Color,
  width: Float,
  cap: StrokeCap = StrokeCap.Round,
  blendMode: BlendMode = BlendMode.SrcOver,
) {
  val points = stroke.points.map { Offset(it.x * size.width, it.y * size.height) }
  val first = points.firstOrNull() ?: return
  if (points.size == 1) {
    drawCircle(color, width / 2, first, blendMode = blendMode)
    return
  }
  val path =
    Path().apply {
      moveTo(first.x, first.y)
      points.drop(1).forEach { lineTo(it.x, it.y) }
    }
  drawPath(path, color, style = Stroke(width, cap = cap, join = StrokeJoin.Round), blendMode = blendMode)
}

private const val MARKER_ALPHA = 0.55f
private const val MARKER_WIDTH = 2.2f
private const val GLOW_ALPHA = 0.4f
private const val GLOW_WIDTH = 2.6f
private const val GLOW_CORE = 0.6f
private const val ERASER_WIDTH = 2f

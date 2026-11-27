package dev.jordond.filmstrip.sample.stories.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * The editor's icons, each drawn as strokes inside the middle of whatever it is given.
 */
internal enum class StoryIcon { Sticker, Draw, Undo, Redo, Bin, Close, Check }

/**
 * Draws [icon] in [color], centred and half as wide as the drawing area.
 */
internal fun DrawScope.drawIcon(
  icon: StoryIcon,
  color: Color,
) {
  val side = size.minDimension * ICON_SHARE
  val left = (size.width - side) / 2
  val top = (size.height - side) / 2
  val stroke = Stroke(side * STROKE_SHARE, cap = StrokeCap.Round, join = StrokeJoin.Round)

  fun at(
    x: Float,
    y: Float,
  ) = Offset(left + x * side, top + y * side)

  fun path(vararg points: Pair<Float, Float>) =
    Path().apply {
      points.forEachIndexed { index, (x, y) ->
        val point = at(x, y)
        if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
      }
    }

  when (icon) {
    StoryIcon.Sticker -> {
      drawRoundRect(color, at(0f, 0f), Size(side, side), CornerRadius(side * 0.25f), stroke)
      drawPath(path(0.6f to 1f, 0.6f to 0.6f, 1f to 0.6f), color, style = stroke)
    }
    StoryIcon.Draw -> {
      drawPath(
        path(0.1f to 0.9f, 0.2f to 0.6f, 0.75f to 0.05f, 0.95f to 0.25f, 0.4f to 0.8f, 0.1f to 0.9f),
        color,
        style = stroke,
      )
    }
    StoryIcon.Undo -> {
      drawPath(path(0.35f to 0.1f, 0.05f to 0.4f, 0.35f to 0.7f), color, style = stroke)
      drawPath(
        path(0.05f to 0.4f, 0.7f to 0.4f, 0.95f to 0.65f, 0.7f to 0.9f, 0.45f to 0.9f),
        color,
        style = stroke,
      )
    }
    StoryIcon.Redo -> {
      drawPath(path(0.65f to 0.1f, 0.95f to 0.4f, 0.65f to 0.7f), color, style = stroke)
      drawPath(
        path(0.95f to 0.4f, 0.3f to 0.4f, 0.05f to 0.65f, 0.3f to 0.9f, 0.55f to 0.9f),
        color,
        style = stroke,
      )
    }
    StoryIcon.Bin -> {
      drawPath(path(0.05f to 0.2f, 0.95f to 0.2f), color, style = stroke)
      drawPath(path(0.35f to 0.2f, 0.4f to 0.02f, 0.6f to 0.02f, 0.65f to 0.2f), color, style = stroke)
      drawPath(path(0.18f to 0.2f, 0.25f to 1f, 0.75f to 1f, 0.82f to 0.2f), color, style = stroke)
    }
    StoryIcon.Close -> {
      drawPath(path(0.1f to 0.1f, 0.9f to 0.9f), color, style = stroke)
      drawPath(path(0.9f to 0.1f, 0.1f to 0.9f), color, style = stroke)
    }
    StoryIcon.Check -> {
      drawPath(path(0.05f to 0.55f, 0.35f to 0.85f, 0.95f to 0.15f), color, style = stroke)
    }
  }
}

/**
 * Draws the bin a held element is dropped on.
 */
internal fun DrawScope.drawBin(color: Color) = drawIcon(StoryIcon.Bin, color)

private const val ICON_SHARE = 0.42f
private const val STROKE_SHARE = 0.1f

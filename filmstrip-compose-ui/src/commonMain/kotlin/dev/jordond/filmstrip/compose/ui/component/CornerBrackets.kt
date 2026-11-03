package dev.jordond.filmstrip.compose.ui.component

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * An inward bracket of [length] at each corner of [box], drawn [width] pixels wide.
 *
 * Each bracket is two arms meeting at its corner, one running along the top or bottom edge and one along the side.
 */
internal fun DrawScope.drawCornerBrackets(
  box: Rect,
  length: Float,
  color: Color,
  width: Float,
) {
  drawBracket(box.topLeft, Offset(1f, 1f), length, color, width)
  drawBracket(box.topRight, Offset(-1f, 1f), length, color, width)
  drawBracket(box.bottomRight, Offset(-1f, -1f), length, color, width)
  drawBracket(box.bottomLeft, Offset(1f, -1f), length, color, width)
}

private fun DrawScope.drawBracket(
  corner: Offset,
  direction: Offset,
  length: Float,
  color: Color,
  width: Float,
) {
  drawLine(color, corner, corner + Offset(direction.x * length, 0f), strokeWidth = width)
  drawLine(color, corner, corner + Offset(0f, direction.y * length), strokeWidth = width)
}

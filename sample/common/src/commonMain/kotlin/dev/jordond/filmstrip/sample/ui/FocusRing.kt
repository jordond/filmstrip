package dev.jordond.filmstrip.sample.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val FocusRingWidth = 3.dp
private val FocusRingOffset = 3.dp

/**
 * Draws the 3dp cyan focus ring 3dp outside the element while [interaction] reports focus.
 *
 * [inset] moves the ring in or out from there, for a part that already draws past its bounds.
 */
public fun Modifier.focusRing(
  interaction: MutableInteractionSource,
  inset: Dp = 0.dp,
): Modifier =
  composed {
    val focused by interaction.collectIsFocusedAsState()
    drawWithContent {
      drawContent()
      if (focused) {
        val width = FocusRingWidth.toPx()
        val reach = (FocusRingOffset - inset).toPx() + width / 2
        drawRect(
          color = Tokens.cyan,
          topLeft = Offset(-reach, -reach),
          size = Size(size.width + reach * 2, size.height + reach * 2),
          style = Stroke(width),
        )
      }
    }
  }

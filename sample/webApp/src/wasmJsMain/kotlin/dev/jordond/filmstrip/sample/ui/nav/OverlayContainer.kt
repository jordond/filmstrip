package dev.jordond.filmstrip.sample.ui.nav

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.sample.ui.Tokens

/**
 * Gives an overlay destination the container its scene does not.
 *
 * A bottom sheet arrives with a surface and a width already around it. A dialog window is bare, so the same content
 * needs a square frame drawn here, sized to something readable rather than to the whole browser window.
 *
 * @param compact Whether the scene is a sheet, which needs nothing from here.
 * @param maxWidth How wide the frame may grow.
 * @param maxHeight How tall the frame may grow.
 * @param frame The colour and weight of the frame's edge.
 */
@Composable
public fun OverlayContainer(
  compact: Boolean,
  maxWidth: Dp = 560.dp,
  maxHeight: Dp = 640.dp,
  frame: BorderStroke = BorderStroke(1.dp, Tokens.line),
  content: @Composable () -> Unit,
) {
  if (compact) {
    content()
  } else {
    Surface(
      shape = RectangleShape,
      color = MaterialTheme.colorScheme.surface,
      contentColor = MaterialTheme.colorScheme.onSurface,
      border = frame,
      modifier = Modifier.padding(24.dp).widthIn(max = maxWidth).heightIn(max = maxHeight),
    ) {
      content()
    }
  }
}

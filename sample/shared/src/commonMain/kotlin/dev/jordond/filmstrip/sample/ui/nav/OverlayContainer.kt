package dev.jordond.filmstrip.sample.ui.nav

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Gives an overlay destination the container its scene does not.
 *
 * A bottom sheet arrives with a surface, a shape and a width already around it. A dialog window is
 * bare, so the same content needs one drawn here, sized to something readable rather than to the
 * whole desktop window.
 */
@Composable
public fun OverlayContainer(
  compact: Boolean,
  content: @Composable () -> Unit,
) {
  if (compact) {
    content()
  } else {
    Surface(
      shape = MaterialTheme.shapes.extraLarge,
      color = MaterialTheme.colorScheme.surfaceContainerHigh,
      contentColor = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.padding(24.dp).widthIn(max = 560.dp).heightIn(max = 640.dp),
    ) {
      content()
    }
  }
}

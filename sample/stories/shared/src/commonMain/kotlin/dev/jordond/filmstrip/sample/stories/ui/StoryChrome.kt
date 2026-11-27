package dev.jordond.filmstrip.sample.stories.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.jordond.filmstrip.compose.ui.OverlayHandleColors
import dev.jordond.filmstrip.compose.ui.OverlayHandleDefaults
import dev.jordond.filmstrip.sample.ui.Tokens

/**
 * A round button floating over the picture, showing [icon] or, when there is none, [label] as text.
 *
 * @param label What the button does, read out by a screen reader and found by tests.
 * @param onClick Called on a press.
 * @param modifier Modifier for the button.
 * @param icon The icon to draw, or null to write [label] instead.
 * @param enabled Whether the button answers a press. A disabled one is drawn faint.
 */
@Composable
internal fun RoundButton(
  label: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  icon: StoryIcon? = null,
  enabled: Boolean = true,
) {
  Box(
    modifier =
      modifier
        .size(Tokens.sizeTouch)
        .alpha(if (enabled) 1f else DISABLED_ALPHA)
        .clip(CircleShape)
        .background(Tokens.backdrop)
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label },
    contentAlignment = Alignment.Center,
  ) {
    if (icon == null) {
      Text(label, style = MaterialTheme.typography.labelLarge, color = Color.White)
    } else {
      Canvas(Modifier.matchParentSize()) { drawIcon(icon, Color.White) }
    }
  }
}

/**
 * The selection's palette on the stage, taken from the app's theme.
 */
@Composable
internal fun storyHandleColors(): OverlayHandleColors {
  val scheme = MaterialTheme.colorScheme
  return remember(scheme) {
    OverlayHandleDefaults.colors(
      fill = scheme.primary.copy(alpha = 0.12f),
      outline = scheme.primary.copy(alpha = 0.8f),
      handle = scheme.primary,
    )
  }
}

private const val DISABLED_ALPHA = 0.4f

package dev.jordond.filmstrip.sample.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

private val KeyLift = 2.dp
private val KeyLiftShadow = 6.dp
private val LoadKeyRing = 3.dp
private val LoadKeyShadow = 8.dp
private val LoadKeySink = 4.dp

/**
 * The three weights a [Key] comes in. Primary is the one thing a view is for, secondary sits beside it and quiet is
 * a lesser jump.
 */
public enum class KeyStyle { Primary, Secondary, Quiet }

/**
 * The chunky VCR key that does everything a button does.
 *
 * It lifts on hover over a hard red shadow, sinks when pressed and takes the cyan focus ring. A disabled key drops to
 * the muted colour on the strong line.
 */
@Composable
public fun Key(
  label: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  style: KeyStyle = KeyStyle.Primary,
  enabled: Boolean = true,
  icon: ImageVector? = null,
  minHeight: Dp = Tokens.sizeKey,
  horizontalPadding: Dp = 22.dp,
) {
  val interaction = remember { MutableInteractionSource() }
  val hovered by interaction.collectIsHoveredAsState()
  val pressed by interaction.collectIsPressedAsState()
  val colors = MaterialTheme.colorScheme
  val (fill, edge, ink) =
    when {
      !enabled -> Triple(Color.Transparent, colors.outline, colors.onSurfaceVariant)
      style == KeyStyle.Primary -> Triple(colors.primary, colors.primary, colors.onPrimary)
      style == KeyStyle.Secondary -> Triple(Color.Transparent, colors.onSurface, colors.onSurface)
      else -> Triple(Color.Transparent, colors.secondary, colors.secondary)
    }
  val lift =
    when {
      !enabled -> 0.dp
      pressed -> -KeyLift
      hovered -> KeyLift
      else -> 0.dp
    }
  val shadow = if (enabled && hovered && !pressed) KeyLiftShadow else 0.dp
  KeyHitBox(interaction, enabled, onClick, modifier.defaultMinSize(minHeight = minHeight)) {
    Row(
      modifier =
        Modifier
          .offset(x = -lift, y = -lift)
          .focusRing(interaction)
          .hardShadow(shadow, Tokens.red)
          .background(fill)
          .border(2.dp, edge)
          .padding(horizontal = horizontalPadding),
      horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (icon != null) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
      }
      Text(label, style = MaterialTheme.typography.labelLarge, color = ink, textAlign = TextAlign.Center)
    }
  }
}

/**
 * The one oversized key on an idle tube, used where the tube has nothing to show yet. It carries a play glyph and
 * gets smaller on a phone. A disabled key shows that the set is busy and stops taking presses.
 */
@Composable
public fun LoadKey(
  label: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  compact: Boolean = isCompactWidth(),
  enabled: Boolean = true,
) {
  val interaction = remember { MutableInteractionSource() }
  val pressed by interaction.collectIsPressedAsState()
  val colors = MaterialTheme.colorScheme
  val fontSize = if (compact) 34.sp else 42.sp
  val rest = if (compact) 6.dp else LoadKeyShadow
  val sink = if (pressed && enabled) LoadKeySink else 0.dp
  val fill = if (enabled) colors.primary else colors.onSurfaceVariant
  val minHeight = if (compact) 56.dp else Tokens.sizeKeyLoad
  KeyHitBox(interaction, enabled, onClick, modifier.defaultMinSize(minHeight = minHeight)) {
    Row(
      modifier =
        Modifier
          .offset(x = sink, y = sink)
          .focusRing(interaction, inset = -LoadKeyRing)
          .drawBehind {
            val ring = LoadKeyRing.toPx()
            val drop = (rest - sink).toPx()
            val outer = Size(size.width + ring * 2, size.height + ring * 2)
            drawRect(Tokens.red, topLeft = Offset(drop - ring, drop - ring), size = outer)
            drawRect(fill, topLeft = Offset(-ring, -ring), size = outer)
          }.background(fill)
          .border(LoadKeyRing, colors.background)
          .padding(horizontal = if (compact) 24.dp else 36.dp),
      horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 14.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        SampleIcons.Play,
        contentDescription = null,
        tint = colors.onPrimary,
        modifier = Modifier.size(if (compact) 20.dp else 26.dp),
      )
      Text(
        text = label,
        maxLines = 1,
        style =
          MaterialTheme.typography.labelLarge.copy(
            fontSize = fontSize,
            lineHeight = fontSize,
            letterSpacing = 0.08.em,
          ),
        color = colors.onPrimary,
      )
    }
  }
}

/**
 * The part of a key that takes the pointer. It stays where it was laid out while the drawn key in [content] lifts and
 * sinks inside it, so a press or a hover never moves the region it landed on.
 */
@Composable
private fun KeyHitBox(
  interaction: MutableInteractionSource,
  enabled: Boolean,
  onClick: () -> Unit,
  modifier: Modifier,
  content: @Composable () -> Unit,
) {
  Box(
    modifier =
      modifier.clickable(
        enabled = enabled,
        role = Role.Button,
        interactionSource = interaction,
        indication = null,
        onClick = onClick,
      ),
    propagateMinConstraints = true,
  ) {
    content()
  }
}

/**
 * A bare icon that presses like a key, for a small action in a strip. It keeps a touch sized target around [icon].
 */
@Composable
public fun IconKey(
  icon: ImageVector,
  contentDescription: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
  iconSize: Dp = 20.dp,
) {
  val interaction = remember { MutableInteractionSource() }
  Box(
    modifier =
      modifier
        .focusRing(interaction)
        .size(Tokens.sizeTouch)
        .clickable(
          enabled = enabled,
          role = Role.Button,
          interactionSource = interaction,
          indication = null,
          onClick = onClick,
        ),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = contentDescription,
      tint = if (enabled) tint else MaterialTheme.colorScheme.outline,
      modifier = Modifier.size(iconSize),
    )
  }
}

/**
 * A word that presses like a key, with no frame around it. The way into a lesser screen from a header strip.
 */
@Composable
public fun TextKey(
  label: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  color: Color = MaterialTheme.colorScheme.primary,
) {
  val interaction = remember { MutableInteractionSource() }
  Box(
    modifier =
      modifier
        .focusRing(interaction)
        .defaultMinSize(minHeight = Tokens.sizeTouch)
        .clickable(
          role = Role.Button,
          interactionSource = interaction,
          indication = null,
          onClick = onClick,
        ).padding(horizontal = 4.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(label, style = MaterialTheme.typography.labelLarge, color = color)
  }
}

/**
 * A word that acts for as long as it is held down, with no frame around it.
 *
 * [onHold] runs when the press starts and [onRelease] when it ends, however it ends.
 */
@Composable
public fun HoldKey(
  label: String,
  onHold: () -> Unit,
  onRelease: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  val hold by rememberUpdatedState(onHold)
  val release by rememberUpdatedState(onRelease)
  var held by remember { mutableStateOf(false) }
  DisposableEffect(Unit) {
    onDispose { if (held) release() }
  }

  Box(
    modifier =
      modifier
        .defaultMinSize(minWidth = Tokens.sizeTouch, minHeight = Tokens.sizeTouch)
        .semantics { role = Role.Button }
        .pointerInput(enabled) {
          if (!enabled) return@pointerInput
          detectTapGestures(
            onPress = {
              held = true
              hold()
              tryAwaitRelease()
              held = false
              release()
            },
          )
        }.padding(horizontal = 4.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelLarge,
      color =
        when {
          !enabled -> MaterialTheme.colorScheme.outline
          held -> MaterialTheme.colorScheme.primary
          else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
  }
}

private fun Modifier.hardShadow(
  reach: Dp,
  color: Color,
): Modifier =
  if (reach == 0.dp) {
    this
  } else {
    drawBehind {
      drawRect(color, topLeft = Offset(reach.toPx(), reach.toPx()), size = size)
    }
  }

package dev.jordond.filmstrip.sample.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val ScanlineThickness = 1.dp
private val ScanlinePitch = 3.dp
private val FocusRingWidth = 3.dp
private val FocusRingOffset = 3.dp
private val LedGlow = 8.dp
private val FitFloor = 10.sp
private val FitStep = 1.sp

/**
 * What an export will do with the settings on screen, a word on a solid fill so it reads on the blue setup menu.
 */
public enum class Verdict { Ready, WillAdjust, CannotExport }

/**
 * A filled status marker for a [Verdict], always holding its word.
 */
@Composable
public fun VerdictPill(
  verdict: Verdict,
  modifier: Modifier = Modifier,
) {
  val (fill, word) =
    when (verdict) {
      Verdict.Ready -> Tokens.cyan to "READY"
      Verdict.WillAdjust -> Tokens.amber to "WILL ADJUST"
      Verdict.CannotExport -> Tokens.red to "CANNOT EXPORT"
    }
  Box(modifier.background(fill).padding(horizontal = 12.dp, vertical = 2.dp)) {
    Text(
      text = word,
      style = MaterialTheme.typography.labelMedium.copy(fontSize = 24.sp),
      color = MaterialTheme.colorScheme.onPrimary,
    )
  }
}

/**
 * What an [Led] is showing. Ready is on and healthy, busy is working, recording is an export writing, off is dark.
 */
public enum class LedState { Off, Ready, Busy, Recording }

/**
 * A round status light, the one small round thing in an otherwise square set.
 *
 * With [blinking] it steps on and off like hardware, and stays lit when the device asks for reduced motion.
 */
@Composable
public fun Led(
  state: LedState,
  modifier: Modifier = Modifier,
  blinking: Boolean = false,
) {
  val lit = !blinking || rememberBlink()
  val colour =
    when {
      !lit -> Tokens.lineStrong
      state == LedState.Off -> Tokens.lineStrong
      state == LedState.Ready -> Tokens.cyan
      state == LedState.Busy -> Tokens.amber
      else -> Tokens.red
    }
  val glows = lit && (state == LedState.Ready || state == LedState.Busy)
  Box(
    modifier
      .size(Tokens.sizeLed)
      .drawBehind {
        if (glows) {
          drawCircle(
            brush =
              Brush.radialGradient(
                colors = listOf(colour.copy(alpha = 0.7f), colour.copy(alpha = 0f)),
                center = center,
                radius = size.minDimension / 2 + LedGlow.toPx(),
              ),
            radius = size.minDimension / 2 + LedGlow.toPx(),
          )
        }
      }.background(colour, CircleShape),
  )
}

/**
 * The container for anything grouped, a [title] on a faint scanline strip above its [content]. A list that draws its
 * own rows passes a [bodyPadding] of zero.
 */
@Composable
public fun Panel(
  title: String,
  modifier: Modifier = Modifier,
  color: Color = MaterialTheme.colorScheme.surface,
  trailing: @Composable (() -> Unit)? = null,
  bodyPadding: Dp = 16.dp,
  headerVerticalPadding: Dp = 8.dp,
  content: @Composable ColumnScope.() -> Unit,
) {
  Column(modifier.background(color).border(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
    Row(
      modifier =
        Modifier
          .fillMaxWidth()
          .scanlinesFaint()
          .bottomLine(MaterialTheme.colorScheme.outlineVariant)
          .padding(
            start = 16.dp,
            end = if (trailing == null) 16.dp else 4.dp,
            top = headerVerticalPadding,
            bottom = headerVerticalPadding,
          ),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f, fill = false),
      )
      trailing?.invoke()
    }
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .padding(bodyPadding),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      content = content,
    )
  }
}

/**
 * How far apart the two colour layers of a [FringeText] sit. Small is for status lines and small titles, large is for
 * the wordmark.
 */
public enum class Fringe(
  internal val offset: Dp,
  internal val pink: Color,
  internal val cyan: Color,
) {
  Small(1.5.dp, Tokens.fringeSmallPink, Tokens.fringeSmallCyan),
  Large(3.dp, Tokens.fringePink, Tokens.fringeCyan),
}

/**
 * Text with the CRT's chromatic fringe, a pink and a cyan copy either side of it.
 *
 * A fringe needs two shadows and Compose's `TextStyle` takes one, which is a limit of Compose, so this draws three
 * layers. Only the top one is exposed to accessibility.
 *
 * @param fitWidth Hold the text to one line, stepping down from the style's font size until it fits the width on offer
 * at the system's font scale.
 */
@Composable
public fun FringeText(
  text: String,
  style: TextStyle,
  color: Color,
  modifier: Modifier = Modifier,
  fringe: Fringe = Fringe.Small,
  maxLines: Int = Int.MAX_VALUE,
  overflow: TextOverflow = TextOverflow.Clip,
  contentAlignment: Alignment = Alignment.TopStart,
  fitWidth: Boolean = false,
) {
  val lines = if (fitWidth) 1 else maxLines
  val autoSize = if (fitWidth) TextAutoSize.StepBased(FitFloor, style.fontSize, FitStep) else null
  Box(modifier, contentAlignment = contentAlignment) {
    Text(
      text = text,
      style = style,
      color = fringe.pink,
      maxLines = lines,
      overflow = overflow,
      autoSize = autoSize,
      modifier = Modifier.offset(x = -fringe.offset).clearAndSetSemantics { },
    )
    Text(
      text = text,
      style = style,
      color = fringe.cyan,
      maxLines = lines,
      overflow = overflow,
      autoSize = autoSize,
      modifier = Modifier.offset(x = fringe.offset).clearAndSetSemantics { },
    )
    Text(text = text, style = style, color = color, maxLines = lines, overflow = overflow, autoSize = autoSize)
  }
}

/**
 * Draws the faint scanlines of a header strip or the stage floor behind the content, one line every three dp.
 */
public fun Modifier.scanlinesFaint(): Modifier =
  drawBehind {
    val thickness = ScanlineThickness.toPx()
    val pitch = ScanlinePitch.toPx()
    var y = 0f
    while (y < size.height) {
      drawRect(Tokens.scanlinesFaint, topLeft = Offset(0f, y), size = Size(size.width, thickness))
      y += pitch
    }
  }

/**
 * Darkens the edges of the element over [reach] from every side, the way a tube's vignette falls off.
 */
public fun Modifier.insetVignette(reach: Dp): Modifier =
  drawBehind {
    val edge = reach.toPx()
    val clear = Tokens.vignette.copy(alpha = 0f)
    val down = Brush.verticalGradient(listOf(Tokens.vignette, clear), startY = 0f, endY = edge)
    val up = Brush.verticalGradient(listOf(clear, Tokens.vignette), startY = size.height - edge, endY = size.height)
    val right = Brush.horizontalGradient(listOf(Tokens.vignette, clear), startX = 0f, endX = edge)
    val left = Brush.horizontalGradient(listOf(clear, Tokens.vignette), startX = size.width - edge, endX = size.width)
    drawRect(down)
    drawRect(up)
    drawRect(right)
    drawRect(left)
  }

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

/**
 * Draws a 1dp line along the bottom edge, the border between a strip and what sits under it.
 */
public fun Modifier.bottomLine(color: Color): Modifier =
  drawBehind {
    val thickness = 1.dp.toPx()
    drawRect(color, topLeft = Offset(0f, size.height - thickness), size = Size(size.width, thickness))
  }

/**
 * Tracks a blink that stays lit when reduced motion is on.
 */
@Composable
internal fun rememberBlink(periodMillis: Long = BLINK_MILLIS): Boolean {
  var lit by remember { mutableStateOf(true) }
  val reduced = rememberReducedMotion()
  LaunchedEffect(reduced, periodMillis) {
    lit = true
    if (reduced) return@LaunchedEffect
    while (true) {
      delay(periodMillis)
      lit = !lit
    }
  }
  return lit
}

private const val BLINK_MILLIS = 500L

package dev.jordond.filmstrip.sample.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

private const val TUBE_ASPECT = 16f / 10f
private const val BAR_SATURATION = 0.85f
private const val BAR_BRIGHTNESS = 0.8f
private const val ROLL_MILLIS = 7_000
private const val ROLL_BAND_PEAK = 0.11f

private val TubeScanlinePitch = 4.dp
private val TubeScanlineWidth = 1.dp
private val RollBandHeight = 60.dp

private class SetMetrics(
  val setRadius: Dp,
  val padding: Dp,
  val chinGap: Dp,
  val tubeBorder: Dp,
  val tubeRadiusX: Dp,
  val tubeRadiusY: Dp,
  val knob: Dp,
  val plateSize: Int,
  val vignetteReach: Dp,
)

private val WideSet = SetMetrics(
  setRadius = 36.dp,
  padding = 28.dp,
  chinGap = 14.dp,
  tubeBorder = 8.dp,
  tubeRadiusX = 40.dp,
  tubeRadiusY = 50.dp,
  knob = Tokens.sizeKnob,
  plateSize = 12,
  vignetteReach = 74.dp,
)

private val PhoneSet = SetMetrics(
  setRadius = 22.dp,
  padding = 14.dp,
  chinGap = 10.dp,
  tubeBorder = 6.dp,
  tubeRadiusX = 20.dp,
  tubeRadiusY = 26.dp,
  knob = 22.dp,
  plateSize = 11,
  vignetteReach = 37.dp,
)

/**
 * The television, a bezel holding a tube with a chin below it for the plate, a status [led] and the knobs.
 *
 * The set and its tube are the only round things in the sample. With [tubeEffects] the tube draws scanlines, a
 * vignette, glass and a rolling band over [content], which only ever belong on an idle tube.
 */
@Composable
public fun TvSet(
  modifier: Modifier = Modifier,
  compact: Boolean = isCompactWidth(),
  led: LedState = LedState.Off,
  tubeEffects: Boolean = true,
  content: @Composable BoxScope.() -> Unit,
) {
  val metrics = if (compact) PhoneSet else WideSet
  val setShape = RoundedCornerShape(metrics.setRadius)
  val tubeShape = remember(metrics) { ellipticalCorners(metrics.tubeRadiusX, metrics.tubeRadiusY) }
  Column(
    modifier = modifier
      .clip(setShape)
      .background(Tokens.bezel)
      .border(1.dp, Tokens.line, setShape)
      .padding(start = metrics.padding, end = metrics.padding, top = metrics.padding, bottom = metrics.chinGap + 2.dp),
    verticalArrangement = Arrangement.spacedBy(metrics.chinGap),
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(TUBE_ASPECT)
        .clip(tubeShape)
        .background(Tokens.screen)
        .border(metrics.tubeBorder, Tokens.groundDeep, tubeShape),
    ) {
      content()
      if (tubeEffects) TubeEffects(metrics.vignetteReach)
    }
    SetChin(metrics, compact, led)
  }
}

/**
 * The idle picture on a tube, the seven colour bars over the castellations and the pluge row.
 *
 * A phone drops the middle row since it is too thin to read at that size.
 */
@Composable
public fun ColourBars(
  modifier: Modifier = Modifier,
  compact: Boolean = isCompactWidth(),
) {
  val filter = remember { barFilter() }
  Canvas(modifier.fillMaxSize()) {
    val top = if (compact) 0.75f else 0.67f
    val middle = if (compact) 0f else 0.08f
    drawBarRow(BarsTop, y = 0f, heightFraction = top, filter = filter)
    if (middle > 0f) drawBarRow(BarsMiddle, y = top, heightFraction = middle, filter = filter)
    drawBarRow(BarsBottom, y = top + middle, heightFraction = 1f - top - middle, filter = filter)
  }
}

private val BarsTop = listOf(
  Tokens.barGrey,
  Tokens.barYellow,
  Tokens.barCyan,
  Tokens.barGreen,
  Tokens.barMagenta,
  Tokens.barRed,
  Tokens.barBlue,
).map { it to 1f }

private val BarsMiddle = listOf(
  Tokens.barBlue,
  Tokens.barBlack,
  Tokens.barMagenta,
  Tokens.barBlack,
  Tokens.barCyan,
  Tokens.barBlack,
  Tokens.barGrey,
).map { it to 1f }

private val BarsBottom = listOf(
  Tokens.barNavy to 5f,
  Tokens.white to 5f,
  Tokens.barViolet to 5f,
  Tokens.barBlack to 13f,
)

private fun barFilter(): ColorFilter {
  val saturation = ColorMatrix().apply { setToSaturation(BAR_SATURATION) }
  val brightness = ColorMatrix().apply { setToScale(BAR_BRIGHTNESS, BAR_BRIGHTNESS, BAR_BRIGHTNESS, 1f) }
  saturation *= brightness
  return ColorFilter.colorMatrix(saturation)
}

private fun DrawScope.drawBarRow(
  bars: List<Pair<Color, Float>>,
  y: Float,
  heightFraction: Float,
  filter: ColorFilter,
) {
  val total = bars.sumOf { it.second.toDouble() }.toFloat()
  var x = 0f
  bars.forEach { (colour, weight) ->
    val width = size.width * weight / total
    drawRect(
      color = colour,
      topLeft = Offset(x, size.height * y),
      size = Size(width, size.height * heightFraction),
      colorFilter = filter,
    )
    x += width
  }
}

@Composable
private fun BoxScope.TubeEffects(vignetteReach: Dp) {
  val roll = if (rememberReducedMotion()) null else rememberRoll()
  Box(
    Modifier
      .matchParentSize()
      .drawBehind {
        roll?.let { drawRollBand(it.value) }
        drawTubeScanlines()
        drawGlass()
      }.insetVignette(vignetteReach),
  )
}

@Composable
private fun rememberRoll(): State<Float> =
  rememberInfiniteTransition(label = "roll").animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(tween(ROLL_MILLIS, easing = LinearEasing), RepeatMode.Restart),
    label = "rollPosition",
  )

private fun DrawScope.drawRollBand(position: Float) {
  val band = RollBandHeight.toPx()
  val y = -band + (size.height + band * 2) * position
  drawRect(
    brush = Brush.verticalGradient(
      0f to Color.Transparent,
      0.5f to Tokens.white.copy(alpha = ROLL_BAND_PEAK),
      1f to Color.Transparent,
      startY = y,
      endY = y + band,
    ),
    topLeft = Offset(0f, y),
    size = Size(size.width, band),
  )
}

/**
 * Draws the tube's scanlines, one line every four dp, across everything the scope covers.
 */
internal fun DrawScope.drawTubeScanlines() {
  val pitch = TubeScanlinePitch.toPx()
  val width = TubeScanlineWidth.toPx()
  var y = pitch - width
  while (y < size.height) {
    drawRect(Tokens.scanlinesTube, topLeft = Offset(0f, y), size = Size(size.width, width))
    y += pitch
  }
}

private fun DrawScope.drawGlass() {
  drawRect(
    brush = Brush.radialGradient(
      colors = listOf(Tokens.white.copy(alpha = 0.07f), Color.Transparent),
      center = Offset(size.width * 0.3f, size.height * 0.1f),
      radius = size.width * 0.54f,
    ),
  )
}

@Composable
private fun SetChin(
  metrics: SetMetrics,
  compact: Boolean,
  led: LedState,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = "FILMSTRIP-VISION",
      maxLines = 1,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = TextStyle(
        fontFamily = MaterialTheme.typography.displaySmall.fontFamily,
        fontSize = metrics.plateSize.sp,
        letterSpacing = 0.2.em,
      ),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
      Led(led)
      Knob(metrics.knob, Tokens.amber)
      if (!compact) Knob(metrics.knob, Tokens.text, Modifier.rotate(120f))
    }
  }
}

@Composable
private fun Knob(
  size: Dp,
  tick: Color,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier
      .size(size)
      .background(Tokens.knob, CircleShape)
      .drawBehind {
        val width = 4.dp.toPx()
        drawRoundRect(
          color = tick,
          topLeft = Offset((this.size.width - width) / 2f, this.size.height * 0.13f),
          size = Size(width, this.size.height * 0.33f),
          cornerRadius = CornerRadius(2.dp.toPx()),
        )
      },
  )
}

private fun ellipticalCorners(
  x: Dp,
  y: Dp,
): Shape =
  object : Shape {
    override fun createOutline(
      size: Size,
      layoutDirection: LayoutDirection,
      density: Density,
    ): Outline =
      with(density) {
        Outline.Rounded(RoundRect(0f, 0f, size.width, size.height, CornerRadius(x.toPx(), y.toPx())))
      }
  }

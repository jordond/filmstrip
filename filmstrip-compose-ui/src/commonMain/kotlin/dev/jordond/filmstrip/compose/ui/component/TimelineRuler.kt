package dev.jordond.filmstrip.compose.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import dev.jordond.filmstrip.compose.ui.FilmstripTimelineDefaults
import dev.jordond.filmstrip.compose.ui.TimelineColors
import dev.jordond.filmstrip.compose.ui.geometry.TimelineScale
import kotlin.math.floor
import kotlin.time.Duration

/**
 * Tick marks and time labels above the strip.
 *
 * The tick unit follows the zoom rather than being fixed, so the labels stay a readable distance apart whether the
 * whole source is on screen or a fraction of a second is. Scroll is read while drawing, so a scrolling timeline redraws
 * the ruler without recomposing it.
 *
 * @param scale What the ticks are laid out against.
 * @param modifier Modifier for the ruler.
 * @param scrollPx How far the timeline has scrolled, in content pixels.
 * @param height How tall the ruler is drawn.
 * @param colors What the ruler paints with.
 * @param minTickSpacing How close two ticks may be drawn before a coarser unit is chosen.
 * @param label What each tick says.
 */
@Composable
public fun TimelineRuler(
  scale: TimelineScale,
  modifier: Modifier = Modifier,
  scrollPx: () -> Float = { 0f },
  height: Dp = FilmstripTimelineDefaults.RulerHeight,
  colors: TimelineColors = FilmstripTimelineDefaults.Palette,
  minTickSpacing: Dp = FilmstripTimelineDefaults.MinTickSpacing,
  label: (time: Duration, interval: Duration) -> String = FilmstripTimelineDefaults::clockLabel,
) {
  val measurer = rememberTextMeasurer(cacheSize = LABEL_CACHE_SIZE)
  val minSpacingPx = with(LocalDensity.current) { minTickSpacing.toPx() }
  val interval = remember(scale, minSpacingPx) { scale.tickInterval(minSpacingPx) }
  val style = remember(colors) { TextStyle(color = colors.rulerLabel, fontSize = LABEL_SIZE) }

  Canvas(modifier.fillMaxWidth().height(height)) {
    val step = scale.widthOf(interval)
    if (step <= 0f) return@Canvas

    val offset = scrollPx()
    val tickHeight = size.height * TICK_FRACTION
    var index = floor(offset / step).toInt().coerceAtLeast(0)

    while (true) {
      val x = index * step - offset
      if (x > size.width) break

      val time = interval * index
      if (time > scale.duration) break

      drawLine(
        color = colors.ruler,
        start = Offset(x, size.height - tickHeight),
        end = Offset(x, size.height),
      )
      drawText(
        textMeasurer = measurer,
        text = label(time, interval),
        topLeft = Offset(x + LABEL_INSET, 0f),
        style = style,
      )

      index++
    }
  }
}

@Preview
@Composable
private fun TimelineRulerPreview() {
  PreviewSurface(height = FilmstripTimelineDefaults.RulerHeight) {
    TimelineRuler(scale = PreviewScale)
  }
}

@Preview
@Composable
private fun TimelineRulerZoomedInPreview() {
  PreviewSurface(height = FilmstripTimelineDefaults.RulerHeight) {
    TimelineRuler(scale = TimelineScale(PreviewDuration, pixelsPerSecond = 240f))
  }
}

private val LABEL_SIZE = 10.sp
private const val LABEL_INSET = 3f
private const val TICK_FRACTION = 0.35f

// Sized against a full screen of ticks, which is roughly eight to twenty two labels at the minimum spacing. A cache
// smaller than that misses on every label past its size and lays them out again on every draw.
private const val LABEL_CACHE_SIZE = 32

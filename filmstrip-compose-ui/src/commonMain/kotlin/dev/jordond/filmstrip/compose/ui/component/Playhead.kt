package dev.jordond.filmstrip.compose.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import dev.jordond.filmstrip.compose.ui.FilmstripTimelineDefaults
import dev.jordond.filmstrip.compose.ui.TimelineColors
import dev.jordond.filmstrip.compose.ui.geometry.TimelineScale
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The playhead line and its knob, drawn over the strip.
 *
 * [position] and [scrollPx] are read while drawing rather than during composition, so a playhead ticking at ten frames
 * a second costs a redraw and no recomposition. Hand it `PlayheadState.positionProvider()` rather than reading the
 * position in a composable body.
 *
 * @param position Where the playhead is.
 * @param scale What turns that into content pixels.
 * @param modifier Modifier for the playhead, which fills whatever it is placed in.
 * @param scrollPx How far the timeline has scrolled, in content pixels.
 * @param sourceOffset Where the player's zero sits on the timeline's clock, so
 * `timelineTime == playerTime + sourceOffset()` .
 * @param colors What the playhead paints with.
 * @param width How wide the line is drawn.
 * @param knobSize How wide the knob above the line is drawn.
 */
@Composable
public fun Playhead(
  position: () -> Duration,
  scale: TimelineScale,
  modifier: Modifier = Modifier,
  scrollPx: () -> Float = { 0f },
  sourceOffset: () -> Duration = { Duration.ZERO },
  colors: TimelineColors = FilmstripTimelineDefaults.Palette,
  width: Dp = FilmstripTimelineDefaults.PlayheadWidth,
  knobSize: Dp = FilmstripTimelineDefaults.PlayheadKnobSize,
) {
  Canvas(modifier.fillMaxSize()) {
    val lineWidth = width.toPx()
    val knob = knobSize.toPx()
    val x = scale.xOf(sourceOffset() + position()) - scrollPx()
    if (x < -knob || x > size.width + knob) return@Canvas

    drawRect(
      color = colors.playhead,
      topLeft = Offset(x - lineWidth / 2f, 0f),
      size = Size(lineWidth, size.height),
    )
    drawRect(
      color = colors.playhead,
      topLeft = Offset(x - knob / 2f, 0f),
      size = Size(knob, knob),
    )
  }
}

@Preview
@Composable
private fun PlayheadPreview() {
  PreviewSurface {
    Playhead(position = { 12.seconds }, scale = PreviewScale)
  }
}

package dev.jordond.filmstrip.compose.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.compose.ui.FilmstripTimelineDefaults
import dev.jordond.filmstrip.compose.ui.TimelineColors
import dev.jordond.filmstrip.compose.ui.geometry.TimelineScale
import dev.jordond.filmstrip.compose.ui.interaction.TrimConstraint
import dev.jordond.filmstrip.compose.ui.interaction.TrimHandle
import dev.jordond.filmstrip.edit.TimeRange
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The trim window over the strip: a scrim on what the trim leaves out, and a handle at each end.
 *
 * Controlled, so a drag reports the range it wants through [onRangeChange] and draws whatever [range] is next passed
 * back in. A caller that drops the change draws a handle that does not move.
 *
 * The range is a [TimeRange], the same half-open type `Clip.trim` takes, so what the handles produce goes straight onto
 * the edit.
 *
 * ```
 * var trimRange by remember(source) { mutableStateOf(TimeRange.from(Duration.ZERO)) }
 *
 * Box(Modifier.height(FilmstripTimelineDefaults.StripHeight)) {
 *   FilmstripStrip(frames = frames, grid = timeline.grid, state = timeline.listState)
 *
 *   TrimOverlay(
 *     range = trimRange,
 *     scale = timeline.scale,
 *     onRangeChange = { trimRange = it },
 *     // The same scroll the strip is at, so the handles sit over the tiles they name.
 *     scrollPx = timeline::scrollPx,
 *     constraint = TrimConstraint.MinDuration(1.seconds),
 *   )
 * }
 *
 * val composition = remember(source, trimRange) {
 *   compositionOf { clip(source) { trim(trimRange) } }
 * }
 * ```
 *
 * @param range Where the trim currently sits. An open end runs to the end of the source, and stays open while the start
 * handle moves. Dragging the end handle closes it, since that gesture is the caller naming an end.
 * @param scale What turns the range into content pixels, and where the source's length comes from.
 * @param onRangeChange Called with the range a gesture asks for, already constrained.
 * @param modifier Modifier for the overlay, which fills whatever it is placed in.
 * @param scrollPx How far the timeline has scrolled, in content pixels.
 * @param constraint What the gesture is allowed to produce.
 * @param colors What the overlay paints with.
 * @param handleWidth How wide a handle is drawn.
 * @param touchWidth How wide a handle answers to a finger, which is wider than it is drawn.
 */
@Composable
public fun TrimOverlay(
  range: TimeRange,
  scale: TimelineScale,
  onRangeChange: (TimeRange) -> Unit,
  modifier: Modifier = Modifier,
  scrollPx: () -> Float = { 0f },
  constraint: TrimConstraint = FilmstripTimelineDefaults.Trim,
  colors: TimelineColors = FilmstripTimelineDefaults.Palette,
  handleWidth: Dp = FilmstripTimelineDefaults.HandleWidth,
  touchWidth: Dp = FilmstripTimelineDefaults.HandleTouchWidth,
) {
  val duration = scale.duration
  val end = range.endExclusive ?: duration

  Box(modifier.fillMaxSize()) {
    Canvas(Modifier.fillMaxSize()) {
      val offset = scrollPx()
      val startX = (scale.xOf(range.start) - offset).coerceIn(0f, size.width)
      val endX = (scale.xOf(end) - offset).coerceIn(0f, size.width)

      if (startX > 0f) {
        drawRect(colors.trimScrim, Offset.Zero, Size(startX, size.height))
      }
      if (endX < size.width) {
        drawRect(colors.trimScrim, Offset(endX, 0f), Size(size.width - endX, size.height))
      }
    }

    TrimHandleBar(
      time = range.start,
      handle = TrimHandle.Start,
      scale = scale,
      scrollPx = scrollPx,
      colors = colors,
      handleWidth = handleWidth,
      touchWidth = touchWidth,
      onDragTo = { contentX ->
        val moved = scale.timeAt(contentX)
        val next = constraint.constrain(TimeRange(moved, range.endExclusive), TrimHandle.Start, duration)
        onRangeChange(if (range.endExclusive == null) TimeRange.from(next.start) else next)
      },
    )

    TrimHandleBar(
      time = end,
      handle = TrimHandle.End,
      scale = scale,
      scrollPx = scrollPx,
      colors = colors,
      handleWidth = handleWidth,
      touchWidth = touchWidth,
      onDragTo = { contentX ->
        val moved = scale.timeAt(contentX)
        onRangeChange(constraint.constrain(TimeRange(range.start, moved), TrimHandle.End, duration))
      },
    )
  }
}

/**
 * One handle, drawn narrow and pressed wide.
 */
@Composable
private fun TrimHandleBar(
  time: Duration,
  handle: TrimHandle,
  scale: TimelineScale,
  scrollPx: () -> Float,
  colors: TimelineColors,
  handleWidth: Dp,
  touchWidth: Dp,
  onDragTo: (Float) -> Unit,
) {
  val halfTouch = with(LocalDensity.current) { touchWidth.toPx() / 2f }

  // The gesture keeps its own running total and reports where the handle should be, rather than
  // reporting each delta for the caller to add on. A pointer coroutine outlives the recompositions
  // that happen during its own gesture, so several events can be delivered against one value of
  // `time` and deltas applied to that value would overlap and lose ground.
  //
  // It is keyed on the handle alone, so it outlives a zoom as well and keeps whatever it captured
  // when it started. Everything the gesture reads therefore comes through one of these rather than
  // straight off the parameter.
  val currentTime by rememberUpdatedState(time)
  val currentScale by rememberUpdatedState(scale)
  val currentOnDragTo by rememberUpdatedState(onDragTo)

  Box(
    modifier =
      Modifier
        .offset { IntOffset((scale.xOf(time) - scrollPx() - halfTouch).roundToInt(), 0) }
        .width(touchWidth)
        .fillMaxHeight()
        .pointerInput(handle) {
          var origin = 0f
          var travelled = 0f
          detectHorizontalDragGestures(
            onDragStart = {
              origin = currentScale.xOf(currentTime)
              travelled = 0f
            },
          ) { change, delta ->
            change.consume()
            travelled += delta
            currentOnDragTo(origin + travelled)
          }
        },
    contentAlignment = Alignment.Center,
  ) {
    Box(
      modifier = Modifier.width(handleWidth).fillMaxHeight().background(colors.trimHandle),
      contentAlignment = Alignment.Center,
    ) {
      Box(Modifier.size(width = GRIP_WIDTH, height = GRIP_HEIGHT).background(colors.trimHandleGrip))
    }
  }
}

@Preview
@Composable
private fun TrimOverlayPreview() {
  PreviewSurface {
    TrimOverlay(range = TimeRange.of(6.seconds, 20.seconds), scale = PreviewScale, onRangeChange = { })
  }
}

@Preview
@Composable
private fun TrimOverlayOpenEndedPreview() {
  PreviewSurface {
    TrimOverlay(range = TimeRange.from(9.seconds), scale = PreviewScale, onRangeChange = { })
  }
}

private val GRIP_WIDTH = 2.dp
private val GRIP_HEIGHT = 18.dp

package dev.jordond.filmstrip.sample.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.compose.ui.geometry.TimelineScale
import dev.jordond.filmstrip.sample.EditState
import dev.jordond.filmstrip.sample.ui.Tokens
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A stretch of source time a lane fills, on the strip's clock.
 */
internal data class LaneSpan(
  val start: Duration,
  val end: Duration,
)

/**
 * The spans the timeline's lanes draw, with a null for a lane that contributes nothing.
 */
internal data class LaneSpans(
  val grade: LaneSpan?,
  val text: LaneSpan?,
)

/**
 * Where the trim starts, or zero while trimming is off.
 *
 * The player runs over the trimmed edit and the strip spans the whole source, so this is what turns edited time into
 * source time for the playhead, the follow and the lanes.
 */
internal fun EditState.trimOffset(): Duration = if (trimEnabled) trimStartSeconds.toDouble().seconds else Duration.ZERO

/**
 * Whether the edit is changing the picture's colour, which is what the grade lane and the tool rail mark.
 */
internal val EditState.gradeContributes: Boolean
  get() = colorGraded || vhsEnabled

/**
 * Works out where each lane sits on a strip of [duration].
 *
 * A grade covers everything the edit keeps. A text window is in edited time, so it moves by [trimOffset] and then
 * clamps to the kept range, and one that ends up empty is not on screen at any point of the edit.
 */
internal fun laneSpans(
  edit: EditState,
  duration: Duration,
): LaneSpans {
  val trim = edit.trimRange(duration)
  val kept = LaneSpan(trim?.start ?: Duration.ZERO, trim?.endExclusive ?: duration)

  val text =
    if (edit.textEnabled && edit.text.isNotBlank()) {
      val offset = edit.trimOffset()
      val window = edit.window(edit.textTimed, edit.textStartSeconds, edit.textEndSeconds)
      if (window == null) kept else kept.clamp(window.start + offset, (window.endExclusive ?: duration) + offset)
    } else {
      null
    }

  return LaneSpans(grade = kept.takeIf { edit.gradeContributes }, text = text)
}

private fun LaneSpan.clamp(
  from: Duration,
  to: Duration,
): LaneSpan? {
  val clamped = LaneSpan(from.coerceIn(start, end), to.coerceIn(start, end))
  return clamped.takeIf { it.end > it.start }
}

/**
 * One lane under the strip, in the strip's own scale and scroll.
 *
 * Drawn on a canvas that reads the scroll while drawing, so a playing strip costs a redraw, and clipped to the viewport
 * rather than laid out at its full length, which at a close zoom over a long source is wider than a layout can measure.
 * A drag on it scrolls the strip through [scrollState].
 */
@Composable
internal fun TimelineLane(
  label: String,
  span: LaneSpan,
  color: Color,
  scale: TimelineScale,
  scrollPx: () -> Float,
  scrollState: ScrollableState,
  height: Dp,
  modifier: Modifier = Modifier,
) {
  val measurer = rememberTextMeasurer()
  val style = MaterialTheme.typography.labelSmall.copy(color = Tokens.ground)
  val measured = remember(measurer, label, style) { measurer.measure(label, style) }

  Canvas(
    modifier
      .fillMaxWidth()
      .height(height)
      .semantics { contentDescription = "$label lane" }
      .scrollable(scrollState, Orientation.Horizontal, reverseDirection = true),
  ) {
    drawRect(Tokens.trackGround)

    val offset = scrollPx()
    val left = (scale.xOf(span.start) - offset).coerceAtLeast(0f)
    val right = (scale.xOf(span.end) - offset).coerceAtMost(size.width)
    if (right <= left) return@Canvas

    drawRect(color, Offset(left, 0f), Size(right - left, size.height))
    clipRect(left, 0f, right, size.height) {
      drawText(measured, topLeft = Offset(left + LANE_LABEL_INSET.toPx(), (size.height - measured.size.height) / 2f))
    }
  }
}

private val LANE_LABEL_INSET = 8.dp

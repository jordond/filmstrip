package dev.jordond.filmstrip.sample.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.fillMaxSize
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
import dev.jordond.filmstrip.sample.ClipEdit
import dev.jordond.filmstrip.sample.ClipId
import dev.jordond.filmstrip.sample.EditState
import dev.jordond.filmstrip.sample.Sequence
import dev.jordond.filmstrip.sample.ui.Tokens
import dev.jordond.filmstrip.sample.visibleWindow
import kotlin.time.Duration

/**
 * A stretch of time a lane fills, on the strip's clock.
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
 * The stretch of the timeline one clip plays over.
 */
internal data class ClipSpan(
  val id: ClipId,
  val start: Duration,
  val end: Duration,
)

/**
 * Whether the edit is changing the picture's colour, which is what the grade lane and the tool rail mark.
 */
internal val EditState.gradeContributes: Boolean
  get() =
    grade.graded || tapeEnabled || filmEnabled || bloomEnabled || crtEnabled || pixelLook.enabled || lutEnabled ||
      nightVisionEnabled || glitchEnabled || blur != 0f || vignette != 0f

/**
 * One extra track's stretch of the timeline, with the name its lane shows.
 */
internal data class TrackSpan(
  val label: String,
  val span: LaneSpan,
)

/**
 * Where each extra track of [sequence] sits on an edit that runs [duration], in the edit's own time.
 *
 * A track starts at its own start and runs to the end of its clips or, when it loops, to the end of the edit.
 * A track with nothing to play, or one still being read, has no lane.
 */
internal fun trackSpans(
  sequence: Sequence,
  duration: Duration,
): List<TrackSpan> =
  sequence.tracks.mapNotNull { edit ->
    val track = edit.track(open = false) ?: return@mapNotNull null
    val end = if (track.looping) duration else track.duration ?: return@mapNotNull null
    LaneSpan(Duration.ZERO, duration).clamp(track.start, end)?.let { TrackSpan(edit.label, it) }
  }

/**
 * Works out where each lane sits on an edit that runs [duration], in the edit's own time.
 *
 * A grade covers the whole edit. A text window is clamped to the edit, and one that ends up empty is not on
 * screen at any point of it.
 */
internal fun laneSpans(
  edit: EditState,
  duration: Duration,
): LaneSpans {
  val whole = LaneSpan(Duration.ZERO, duration)

  val text =
    if (edit.textEnabled && edit.text.isNotBlank()) {
      val window = visibleWindow(edit.textTimed, edit.textStartSeconds, edit.textEndSeconds)
      if (window == null) whole else whole.clamp(window.start, window.endExclusive ?: duration)
    } else {
      null
    }

  return LaneSpans(grade = whole.takeIf { edit.gradeContributes }, text = text)
}

/**
 * The stretch of the timeline each clip with a grade of its own plays over, in the edit's own time.
 */
internal fun clipGradeSpans(sequence: Sequence): List<LaneSpan> =
  clipSpans(sequence)
    .zip(sequence.clips)
    .filter { (span, clip) -> clip.grade.graded && span.end > span.start }
    .map { (span, _) -> LaneSpan(span.start, span.end) }

/**
 * The part of this span that [clip] plays, in the clip's source time, which is the clock of a strip laid over
 * that clip alone. Null when the clip plays none of it.
 */
internal fun LaneSpan.onClip(
  sequence: Sequence,
  clip: ClipEdit,
): LaneSpan? {
  val run = clipSpans(sequence).firstOrNull { it.id == clip.id } ?: return null
  val shown = LaneSpan(run.start, run.end).clamp(start, end) ?: return null
  val from = sequence.sourceTimeOf(clip.id, shown.start) ?: return null
  val to = sequence.sourceTimeOf(clip.id, shown.end) ?: return null
  return LaneSpan(from, to)
}

/**
 * Every lane moved onto the strip of [clip] alone.
 */
internal fun LaneSpans.onClip(
  sequence: Sequence,
  clip: ClipEdit,
): LaneSpans = LaneSpans(grade?.onClip(sequence, clip), text?.onClip(sequence, clip))

/**
 * Where each clip of [sequence] sits on the timeline, in the order they play.
 */
internal fun clipSpans(sequence: Sequence): List<ClipSpan> =
  sequence.clips.zip(sequence.clipStarts()) { clip, start ->
    ClipSpan(clip.id, start, start + (clip.duration ?: Duration.ZERO))
  }

private fun LaneSpan.clamp(
  from: Duration,
  to: Duration,
): LaneSpan? {
  val clamped = LaneSpan(from.coerceIn(start, end), to.coerceIn(start, end))
  return clamped.takeIf { it.end > it.start }
}

/**
 * One lane under the strip, in the strip's own scale and scroll, with [label] on each of its [spans].
 *
 * Drawn on a canvas that reads the scroll while drawing, so a playing strip costs a redraw, and clipped to the viewport
 * rather than laid out at its full length, which at a close zoom over a long source is wider than a layout can measure.
 * A drag on it scrolls the strip through [scrollState].
 */
@Composable
internal fun TimelineLane(
  label: String,
  spans: List<LaneSpan>,
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
    spans.forEach { span ->
      val left = (scale.xOf(span.start) - offset).coerceAtLeast(0f)
      val right = (scale.xOf(span.end) - offset).coerceAtMost(size.width)
      if (right <= left) return@forEach

      drawRect(color, Offset(left, 0f), Size(right - left, size.height))
      clipRect(left, 0f, right, size.height) {
        drawText(measured, topLeft = Offset(left + LANE_LABEL_INSET.toPx(), (size.height - measured.size.height) / 2f))
      }
    }
  }
}

private val LANE_LABEL_INSET = 8.dp

/**
 * A line where each clip after the first starts and rails along the selected clip, drawn over a strip that lays
 * out the whole edit.
 */
@Composable
internal fun ClipMarks(
  spans: List<ClipSpan>,
  selected: ClipId?,
  scale: TimelineScale,
  scrollPx: () -> Float,
  modifier: Modifier = Modifier,
) {
  val rail = MaterialTheme.colorScheme.primary
  val line = MaterialTheme.colorScheme.onSurface
  Canvas(modifier.fillMaxSize()) {
    val offset = scrollPx()
    val width = CLIP_MARK_WIDTH.toPx()

    spans.drop(1).forEach { span ->
      val x = scale.xOf(span.start) - offset
      if (x in 0f..size.width) drawRect(line, Offset(x - width / 2f, 0f), Size(width, size.height))
    }

    spans.firstOrNull { it.id == selected }?.let { span ->
      val left = (scale.xOf(span.start) - offset).coerceIn(0f, size.width)
      val right = (scale.xOf(span.end) - offset).coerceIn(0f, size.width)
      if (right <= left) return@let
      val height = CLIP_RAIL.toPx()
      drawRect(rail, Offset(left, 0f), Size(right - left, height))
      drawRect(rail, Offset(left, size.height - height), Size(right - left, height))
    }
  }
}

private val CLIP_MARK_WIDTH = 2.dp
private val CLIP_RAIL = 3.dp

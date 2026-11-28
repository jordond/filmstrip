package dev.jordond.filmstrip.sample.stories.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.compose.ui.FilmstripTimeline
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.sample.stories.StoryClock
import dev.jordond.filmstrip.sample.stories.StoryEditor
import dev.jordond.filmstrip.sample.ui.Tokens
import dev.jordond.filmstrip.still.FrameRenderer
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * When the selected element shows: a strip of the clip with a window whose two handles set the element's start and
 * end.
 *
 * Nothing is drawn with no element selected or before the clip's length is known.
 *
 * @param editor The session being edited.
 * @param clock Where the clip is and how long it runs.
 * @param renderer Draws the strip's frames.
 * @param composition The edit the strip shows.
 */
@Composable
internal fun TimingBar(
  editor: StoryEditor,
  clock: StoryClock,
  renderer: FrameRenderer,
  composition: Composition,
  modifier: Modifier = Modifier,
) {
  val element = editor.story.selected ?: return
  val duration = clock.duration
  if (duration <= Duration.ZERO) return
  Column(
    modifier = modifier.fillMaxWidth().background(Tokens.backdrop, BarShape).padding(10.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(
        text =
          element.window?.let { "Shows ${it.start.label()} to ${(it.endExclusive ?: duration).label()}" }
            ?: "Shows for the whole story",
        style = MaterialTheme.typography.bodySmall,
        color = Tokens.text,
        modifier = Modifier.weight(1f),
      )
      if (element.window != null) {
        Pill("Whole story", Modifier.testTag(StoryTags.TIMING_WHOLE)) {
          editor.story.window(element.id, timed = false, startSeconds = 0f, endSeconds = 0f)
        }
      }
    }
    Box(Modifier.testTag(StoryTags.TIMING_STRIP)) {
      FilmstripTimeline(
        renderer = renderer,
        composition = composition,
        duration = duration,
        position = { clock.playhead },
        isPlaying = clock.playing,
        trim = element.window ?: TimeRange(Duration.ZERO, duration),
        onTrimChange = { range ->
          editor.story.window(
            id = element.id,
            timed = true,
            startSeconds = range.start.seconds(),
            endSeconds = (range.endExclusive ?: duration).seconds(),
          )
        },
      )
    }
  }
}

private fun Duration.seconds(): Float = toDouble(DurationUnit.SECONDS).toFloat()

private fun Duration.label(): String = "${(inWholeMilliseconds / TENTH) / TENTHS_PER_SECOND.toFloat()}s"

private val BarShape = RoundedCornerShape(12.dp)
private const val TENTH = 100
private const val TENTHS_PER_SECOND = 10

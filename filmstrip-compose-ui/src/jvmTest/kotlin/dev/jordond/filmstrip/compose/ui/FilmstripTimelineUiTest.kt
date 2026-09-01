package dev.jordond.filmstrip.compose.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.compose.ui.geometry.TimelineZoom
import dev.jordond.filmstrip.compose.ui.interaction.PlayheadFollow
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalTestApi::class)
class FilmstripTimelineUiTest {
  @Test
  fun `the overlay is handed the state the timeline is drawing itself from`() =
    runComposeUiTest {
      lateinit var timeline: TimelineState
      lateinit var follow: PlayheadFollow
      setContent {
        Stage {
          FilmstripTimeline(
            filmstrip = Filmstrip(),
            composition = COMPOSITION,
            duration = DURATION,
            position = { Duration.ZERO },
            isPlaying = false,
            zoom = TimelineZoom.of(3),
            overlay = {
              timeline = this.timeline
              follow = this.follow
            },
          )
        }
      }

      // The state the overlay is given covers the same ground the timeline was asked for, rather
      // than a second copy built from whatever the overlay could reach on its own.
      timeline.zoom shouldBe TimelineZoom.of(3)
      timeline.scale.duration shouldBe DURATION
      timeline.grid.count shouldBe 21
      follow.isEngaged shouldBe true
    }

  @Test
  fun `the overlay follows a zoom change made through the state it was given`() =
    runComposeUiTest {
      lateinit var timeline: TimelineState
      setContent {
        Stage {
          FilmstripTimeline(
            filmstrip = Filmstrip(),
            composition = COMPOSITION,
            duration = DURATION,
            position = { Duration.ZERO },
            isPlaying = false,
            overlay = { timeline = this.timeline },
          )
        }
      }

      val state = timeline
      state.zoomTo(TimelineZoom.of(5), focusViewportPx = 0f)
      waitForIdle()

      // The same instance, moved, rather than a fresh one handed to the overlay on recomposition.
      timeline shouldBe state
      timeline.zoom shouldBe TimelineZoom.of(5)
    }

  @Test
  fun `the strip spans the duration it is given rather than the composition's own length`() =
    runComposeUiTest {
      var duration by mutableStateOf(DURATION)
      lateinit var timeline: TimelineState
      setContent {
        Stage {
          FilmstripTimeline(
            filmstrip = Filmstrip(),
            composition = COMPOSITION,
            duration = duration,
            position = { Duration.ZERO },
            isPlaying = false,
            zoom = TimelineZoom.of(3),
            overlay = { timeline = this.timeline },
          )
        }
      }

      timeline.scale.contentWidthPx shouldBe 960f

      // Half the source, at the same zoom, is half the content to scroll through.
      duration = 30.seconds
      waitForIdle()

      timeline.scale.contentWidthPx shouldBe 480f
    }

  @Composable
  private fun Stage(content: @Composable () -> Unit) {
    Box(Modifier.size(width = 400.dp, height = 120.dp)) { content() }
  }

  private companion object {
    val DURATION = 60.seconds

    val COMPOSITION =
      EditComposition(
        tracks = listOf(Track(listOf(Clip(MediaSource.of("timeline.mp4"), TimeRange.of(Duration.ZERO, DURATION))))),
      )
  }
}

package dev.jordond.filmstrip.compose.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.compose.ui.geometry.TimelineZoom
import dev.jordond.filmstrip.compose.ui.interaction.PlayheadFollow
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Every timeline here is 400 pixels wide on a density of one, with the default ruler over the default strip.
 */
@OptIn(ExperimentalTestApi::class)
class TimelineOverlayScopeUiTest {
  @Test
  fun `an overlay that matches its parent covers the strip and not the ruler`() =
    runComposeUiTest {
      showTimeline { Box(Modifier.matchParentSize().testTag(CHROME)) }

      onNodeWithTag(CHROME)
        .assertTopPositionInRootIsEqualTo(FilmstripTimelineDefaults.RulerHeight)
        .assertHeightIsEqualTo(FilmstripTimelineDefaults.StripHeight)
        .assertWidthIsEqualTo(400.dp)
    }

  @Test
  fun `an overlay aligns itself inside the strip's box`() =
    runComposeUiTest {
      showTimeline { Box(Modifier.align(Alignment.BottomEnd).size(CHROME_SIZE).testTag(CHROME)) }

      onNodeWithTag(CHROME)
        .assertLeftPositionInRootIsEqualTo(400.dp - CHROME_SIZE)
        .assertTopPositionInRootIsEqualTo(
          FilmstripTimelineDefaults.RulerHeight + FilmstripTimelineDefaults.StripHeight - CHROME_SIZE,
        )
    }

  @Test
  fun `the follow an overlay is handed is the one steering the strip`() =
    runComposeUiTest {
      var position by mutableStateOf(Duration.ZERO)
      lateinit var timeline: TimelineState
      lateinit var follow: PlayheadFollow
      showTimeline(position = { position }, isPlaying = true, zoom = TimelineZoom.of(3)) {
        timeline = this.timeline
        follow = this.follow
      }

      follow.disengage()
      waitForIdle()
      position = 40.seconds
      waitForIdle()

      timeline.scrollPx() shouldBe 0f

      follow.engage()
      waitForIdle()
      position = 41.seconds
      waitForIdle()

      // Recentred in the 400 pixel viewport once following is back on.
      timeline.scrollPx() shouldBe timeline.scale.xOf(41.seconds) - 200f
    }

  private fun ComposeUiTest.showTimeline(
    position: () -> Duration = { Duration.ZERO },
    isPlaying: Boolean = false,
    zoom: TimelineZoom? = null,
    overlay: @Composable TimelineOverlayScope.() -> Unit,
  ) {
    setContent {
      Box(Modifier.size(width = 400.dp, height = 120.dp)) {
        FilmstripTimeline(
          renderer = Filmstrip(),
          composition = COMPOSITION,
          duration = DURATION,
          position = position,
          isPlaying = isPlaying,
          zoom = zoom,
          overlay = overlay,
        )
      }
    }
    waitForIdle()
  }

  private companion object {
    const val CHROME = "chrome"
    val CHROME_SIZE = 24.dp
    val DURATION = 60.seconds

    val COMPOSITION =
      Composition(
        tracks = listOf(Track(listOf(Clip(MediaSource.of("timeline.mp4"), TimeRange.of(Duration.ZERO, DURATION))))),
      )
  }
}

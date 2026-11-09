package dev.jordond.filmstrip.compose.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.compose.ui.geometry.TimelineZoom
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Every timeline here is 400 pixels wide on a density of one, over a minute of source.
 */
@OptIn(ExperimentalTestApi::class)
class TimelineOptionsUiTest {
  @Test
  fun `the defaults stack the ruler over the strip`() =
    runComposeUiTest {
      showTimeline(TimelineOptions.Default)

      onNodeWithTag(TIMELINE).assertHeightIsEqualTo(
        FilmstripTimelineDefaults.RulerHeight + FilmstripTimelineDefaults.StripHeight,
      )
    }

  @Test
  fun `the ruler and strip are drawn at the heights the options ask for`() =
    runComposeUiTest {
      showTimeline(TimelineOptions(stripHeight = 40.dp, rulerHeight = 20.dp))

      onNodeWithTag(TIMELINE).assertHeightIsEqualTo(60.dp)
    }

  @Test
  fun `a timeline without a ruler is only as tall as its strip`() =
    runComposeUiTest {
      showTimeline(TimelineOptions(stripHeight = 40.dp, showRuler = false))

      onNodeWithTag(TIMELINE).assertHeightIsEqualTo(40.dp)
    }

  @Test
  fun `the tile width sets the grid the strip is cut into`() =
    runComposeUiTest {
      lateinit var timeline: TimelineState
      showTimeline(TimelineOptions(tileWidth = 60.dp), overlay = { timeline = this.timeline })

      timeline.grid.tileWidthPx shouldBe 60
    }

  @Test
  fun `the playhead is drawn unless the options turn it off`() =
    runComposeUiTest {
      var options by mutableStateOf(TimelineOptions.Default)
      lateinit var timeline: TimelineState
      showLiveTimeline(
        options = { options },
        position = { PLAYHEAD },
        colors = FilmstripTimelineDefaults.colors(playhead = Color.Red),
        overlay = { timeline = this.timeline },
      )
      val x = timeline.scale.xOf(PLAYHEAD).toInt()

      redInStripAt(x) shouldBe true

      options = TimelineOptions(showPlayhead = false)
      waitForIdle()

      redInStripAt(x) shouldBe false
    }

  @Test
  fun `the strip follows the playhead off screen only while following is on`() =
    runComposeUiTest {
      val (scroll, timeline) = followedScroll(TimelineOptions.Default)

      // The playhead is recentred in the 400 pixel viewport.
      scroll shouldBe timeline.scale.xOf(FOLLOWED) - 200f
    }

  @Test
  fun `a strip that is not following stays where it was`() =
    runComposeUiTest {
      followedScroll(TimelineOptions(followPlayhead = false)).first shouldBe 0f
    }

  @Test
  fun `a modified wheel steps the zoom unless the options turn wheel zoom off`() =
    runComposeUiTest {
      var options by mutableStateOf(TimelineOptions.Default)
      lateinit var timeline: TimelineState
      showLiveTimeline(options = { options }, overlay = { timeline = this.timeline })

      val before = timeline.zoom
      wheelZoomIn()
      val zoomed = timeline.zoom
      zoomed shouldNotBe before

      options = TimelineOptions(wheelZoom = false)
      waitForIdle()
      wheelZoomIn()

      timeline.zoom shouldBe zoomed
    }

  /**
   * Where the strip has scrolled to once a playing playhead jumps well past the viewport, and the state it scrolled.
   */
  private fun ComposeUiTest.followedScroll(options: TimelineOptions): Pair<Float, TimelineState> {
    var position by mutableStateOf(Duration.ZERO)
    lateinit var timeline: TimelineState
    setContent {
      Stage {
        FilmstripTimeline(
          renderer = Filmstrip(),
          composition = COMPOSITION,
          duration = DURATION,
          position = { position },
          isPlaying = true,
          zoom = TimelineZoom.of(3),
          options = options,
          overlay = { timeline = this.timeline },
        )
      }
    }
    waitForIdle()

    position = FOLLOWED
    waitForIdle()

    return timeline.scrollPx() to timeline
  }

  private fun ComposeUiTest.showTimeline(
    options: TimelineOptions,
    overlay: @Composable TimelineOverlayScope.() -> Unit = { },
  ) {
    showLiveTimeline(options = { options }, overlay = overlay)
  }

  private fun ComposeUiTest.showLiveTimeline(
    options: () -> TimelineOptions,
    position: () -> Duration = { Duration.ZERO },
    colors: TimelineColors = FilmstripTimelineDefaults.Palette,
    overlay: @Composable TimelineOverlayScope.() -> Unit = { },
  ) {
    setContent {
      Stage {
        FilmstripTimeline(
          renderer = Filmstrip(),
          composition = COMPOSITION,
          duration = DURATION,
          position = position,
          isPlaying = false,
          modifier = Modifier.testTag(TIMELINE),
          options = options(),
          colors = colors,
          overlay = overlay,
        )
      }
    }
    waitForIdle()
  }

  private fun ComposeUiTest.redInStripAt(x: Int): Boolean {
    val pixels = onRoot().captureToImage().toPixelMap()
    val row = (FilmstripTimelineDefaults.RulerHeight + FilmstripTimelineDefaults.StripHeight / 2).value.toInt()
    return (x - 1..x + 1).any { pixels[it, row] == Color.Red }
  }

  private fun ComposeUiTest.wheelZoomIn() {
    onRoot().performKeyInput { keyDown(Key.CtrlLeft) }
    onRoot().performMouseInput {
      moveTo(Offset(200f, FilmstripTimelineDefaults.RulerHeight.value + 10f))
      scroll(-3f)
    }
    onRoot().performKeyInput { keyUp(Key.CtrlLeft) }
    waitForIdle()
  }

  @Composable
  private fun Stage(content: @Composable () -> Unit) {
    Box(Modifier.size(width = 400.dp, height = 120.dp)) { content() }
  }

  private companion object {
    const val TIMELINE = "timeline"
    val DURATION = 60.seconds
    val PLAYHEAD = 10.seconds

    // Two thirds of the way through the 960 pixels zoom step three lays a minute out across.
    val FOLLOWED = 40.seconds

    val COMPOSITION =
      Composition(
        tracks = listOf(Track(listOf(Clip(MediaSource.of("timeline.mp4"), TimeRange.of(Duration.ZERO, DURATION))))),
      )
  }
}

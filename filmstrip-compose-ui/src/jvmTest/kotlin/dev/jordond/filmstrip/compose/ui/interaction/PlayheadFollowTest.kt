package dev.jordond.filmstrip.compose.ui.interaction

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.compose.ui.component.scrollStripTo
import dev.jordond.filmstrip.compose.ui.component.stripScrollPx
import dev.jordond.filmstrip.compose.ui.geometry.StripGrid
import dev.jordond.filmstrip.compose.ui.geometry.TimelineZoom
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalTestApi::class)
class PlayheadFollowTest {
  @Test
  fun `the strip recentres on a position the provider reports`() =
    runComposeUiTest {
      var position by mutableStateOf(Duration.ZERO)
      lateinit var listState: LazyListState
      lateinit var follow: PlayheadFollow
      setContent {
        listState = rememberLazyListState()
        follow = rememberPlayheadFollow({ position }, GRID, listState, isPlaying = true)
        strip(listState)
      }

      follow.isEngaged shouldBe true

      position = 30.seconds
      waitForIdle()

      // 30s at 16px per second is 480 content px, which is past the band the playhead may sit in,
      // so the strip recentres on it half a viewport short.
      listState.stripScrollPx(GRID) shouldBe 280f
    }

  @Test
  fun `a position inside the band leaves the strip alone`() =
    runComposeUiTest {
      var position by mutableStateOf(Duration.ZERO)
      lateinit var listState: LazyListState
      setContent {
        listState = rememberLazyListState()
        rememberPlayheadFollow({ position }, GRID, listState, isPlaying = true)
        strip(listState)
      }

      // 12.5s is 200 content px, which is the middle of the viewport rather than either edge of
      // the band, so nothing needs to move.
      position = 12.5.seconds
      waitForIdle()

      listState.stripScrollPx(GRID) shouldBe 0f
    }

  @Test
  fun `the provider is read again after it changes, rather than captured once`() =
    runComposeUiTest {
      var ahead by mutableStateOf(false)
      lateinit var listState: LazyListState
      setContent {
        listState = rememberLazyListState()
        val position: () -> Duration = if (ahead) ({ 30.seconds }) else ({ Duration.ZERO })
        rememberPlayheadFollow(position, GRID, listState, isPlaying = true)
        strip(listState)
      }

      ahead = true
      waitForIdle()

      listState.stripScrollPx(GRID) shouldBe 280f
    }

  @Test
  fun `a scroll the follow did not make disengages it, and the strip then stays put`() =
    runComposeUiTest {
      var position by mutableStateOf(Duration.ZERO)
      lateinit var listState: LazyListState
      lateinit var follow: PlayheadFollow
      setContent {
        listState = rememberLazyListState()
        follow = rememberPlayheadFollow({ position }, GRID, listState, isPlaying = true)
        strip(listState)
      }

      listState.scrollStripTo(GRID, 100f)
      waitForIdle()
      follow.isEngaged shouldBe false

      position = 45.seconds
      waitForIdle()

      listState.stripScrollPx(GRID) shouldBe 100f
    }

  @Test
  fun `playback starting engages it again`() =
    runComposeUiTest {
      var isPlaying by mutableStateOf(false)
      lateinit var listState: LazyListState
      lateinit var follow: PlayheadFollow
      setContent {
        listState = rememberLazyListState()
        follow = rememberPlayheadFollow({ Duration.ZERO }, GRID, listState, isPlaying)
        strip(listState)
      }

      listState.scrollStripTo(GRID, 100f)
      waitForIdle()
      follow.isEngaged shouldBe false

      isPlaying = true
      waitForIdle()

      follow.isEngaged shouldBe true
    }

  @Test
  fun `the source offset moves what counts as on screen`() =
    runComposeUiTest {
      var position by mutableStateOf(Duration.ZERO)
      lateinit var listState: LazyListState
      setContent {
        listState = rememberLazyListState()
        rememberPlayheadFollow({ position }, GRID, listState, isPlaying = true) { 20.seconds }
        strip(listState)
      }

      // The player's 12.5s is the timeline's 32.5s once the trim start is added, which is 520
      // content px rather than the 200 that would have needed no scroll at all.
      position = 12.5.seconds
      waitForIdle()

      listState.stripScrollPx(GRID) shouldBe 320f
    }

  @Test
  fun `a disabled follow leaves the scroll position alone`() =
    runComposeUiTest {
      var position by mutableStateOf(Duration.ZERO)
      lateinit var listState: LazyListState
      setContent {
        listState = rememberLazyListState()
        rememberPlayheadFollow({ position }, GRID, listState, isPlaying = true, enabled = false)
        strip(listState)
      }

      // The same 480 content px that recentres an enabled follow.
      position = 30.seconds
      waitForIdle()

      listState.stripScrollPx(GRID) shouldBe 0f
    }

  /**
   * A bare row over [GRID], laid out so a scroll on [listState] has real content to act on.
   */
  @Composable
  private fun strip(listState: LazyListState) {
    val density = LocalDensity.current
    Box(Modifier.size(width = 400.dp, height = 72.dp)) {
      LazyRow(state = listState) {
        items(GRID.count) { index ->
          val tileWidth = with(density) { GRID.tileWidthPxAt(index).toDp() }
          Box(Modifier.width(tileWidth).fillMaxHeight().background(Color.DarkGray))
        }
      }
    }
  }

  private companion object {
    val DURATION = 60.seconds

    /**
     * 60s at the fourth step of the ladder, which is 16px per second and 960 content pixels.
     */
    val GRID = StripGrid(TimelineZoom.of(3).scaleFor(DURATION), tileWidthPx = 46)
  }
}

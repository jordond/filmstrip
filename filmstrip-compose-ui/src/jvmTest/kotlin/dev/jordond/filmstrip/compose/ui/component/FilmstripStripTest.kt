package dev.jordond.filmstrip.compose.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.compose.FilmstripFrames
import dev.jordond.filmstrip.compose.rememberFilmstripFrames
import dev.jordond.filmstrip.compose.ui.RecordingThumbnailSource
import dev.jordond.filmstrip.compose.ui.filmstripWith
import dev.jordond.filmstrip.compose.ui.geometry.StripGrid
import dev.jordond.filmstrip.compose.ui.geometry.TimelineScale
import dev.jordond.filmstrip.compose.ui.testComposition
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalTestApi::class)
class FilmstripStripTest {
  @Test
  fun `the scroll the overlays read is where the row actually sits`() =
    runComposeUiTest {
      val source = RecordingThumbnailSource()
      lateinit var state: LazyListState
      setContent { state = strip(source).second }

      state.stripScrollPx(GRID) shouldBe 0f

      // On a tile boundary, then off one, so a reading that only works at multiples of the tile
      // width fails the second half.
      runBlocking { state.scrollStripTo(GRID, 500f) }
      waitForIdle()
      state.stripScrollPx(GRID) shouldBe 500f

      runBlocking { state.scrollStripTo(GRID, 733f) }
      waitForIdle()
      state.stripScrollPx(GRID) shouldBe 733f
    }

  @Test
  fun `the window the strip reports is the one the grid predicts`() =
    runComposeUiTest {
      val source = RecordingThumbnailSource()
      lateinit var state: LazyListState
      setContent { state = strip(source).second }

      runBlocking { state.scrollStripTo(GRID, 725f) }
      waitForIdle()

      val predicted = GRID.visibleRange(state.stripScrollPx(GRID), VIEWPORT_WIDTH_PX)
      val laidOut = state.layoutInfo.visibleItemsInfo

      laidOut.first().index shouldBe predicted.first
      laidOut.last().index shouldBe predicted.last

      // And the strip actually asked for that window rather than merely being able to predict it.
      // Overscan widens what is fetched, so the visible tiles are a subset of what was requested.
      val asked = source.requested.toSet()
      predicted.forEach { index -> asked.contains(GRID.positions[index]) shouldBe true }
    }

  @Composable
  private fun strip(source: RecordingThumbnailSource): Pair<FilmstripFrames, LazyListState> {
    val filmstrip = remember(source) { filmstripWith(source) }
    val composition = remember { testComposition() }
    val state = rememberLazyListState()
    val frames =
      rememberFilmstripFrames(filmstrip, composition, GRID.positions, heightPx = 72)

    Box(Modifier.size(width = 400.dp, height = 72.dp)) {
      FilmstripStrip(frames = frames, grid = GRID, state = state)
    }

    return frames to state
  }

  private companion object {
    const val VIEWPORT_WIDTH_PX = 400f
    val GRID = StripGrid(TimelineScale(30.seconds, 100f), tileWidthPx = 50)
  }
}

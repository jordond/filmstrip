package dev.jordond.filmstrip.compose.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.compose.FilmstripFrames
import dev.jordond.filmstrip.compose.rememberFilmstripFrames
import dev.jordond.filmstrip.compose.ui.FilmstripTimelineDefaults
import dev.jordond.filmstrip.compose.ui.TimelineColors
import dev.jordond.filmstrip.compose.ui.geometry.StripGrid

/**
 * The scrolling strip of tiles.
 *
 * Reports its own window to [frames] as it scrolls, which is what keeps decoding to the tiles on screen and their
 * overscan. [frames] is passed in rather than built here, so the byte cap and the overscan are the caller's to set.
 *
 * ```
 * BoxWithConstraints {
 *   val density = LocalDensity.current
 *   val timeline = rememberTimelineState(
 *     duration = sourceDuration,
 *     viewportWidthPx = with(density) { maxWidth.toPx() },
 *     tileWidthPx = with(density) { FilmstripTimelineDefaults.TileWidth.roundToPx() },
 *   )
 *   val frames = rememberFilmstripFrames(
 *     filmstrip = filmstrip,
 *     composition = composition,
 *     positions = timeline.grid.positions,
 *     heightPx = with(density) { FilmstripTimelineDefaults.StripHeight.roundToPx() },
 *   )
 *
 *   Box(Modifier.height(FilmstripTimelineDefaults.StripHeight)) {
 *     FilmstripStrip(frames = frames, grid = timeline.grid, state = timeline.listState)
 *
 *     Playhead(
 *       position = playhead.positionProvider(),
 *       scale = timeline.scale,
 *       scrollPx = timeline::scrollPx,
 *     )
 *   }
 * }
 * ```
 *
 * @param frames The decoded tiles, built over the same positions as [grid].
 * @param grid Where the tiles sit and how wide they are.
 * @param modifier Modifier for the row.
 * @param state The row's scroll state, which the ruler and the overlays read to stay aligned.
 * @param tileHeight How tall the tiles are drawn.
 * @param colors What the strip paints with.
 * @param userScrollEnabled Whether a finger may scroll the row.
 * @param placeholder What fills a tile whose frame has not arrived.
 */
@Composable
public fun FilmstripStrip(
  frames: FilmstripFrames,
  grid: StripGrid,
  modifier: Modifier = Modifier,
  state: LazyListState = rememberLazyListState(),
  tileHeight: Dp = FilmstripTimelineDefaults.StripHeight,
  colors: TimelineColors = FilmstripTimelineDefaults.Palette,
  userScrollEnabled: Boolean = true,
  placeholder: @Composable (index: Int) -> Unit = { },
) {
  val density = LocalDensity.current

  LaunchedEffect(frames, state) {
    snapshotFlow {
      val items = state.layoutInfo.visibleItemsInfo
      if (items.isEmpty()) null else items.first().index to items.last().index
    }.collect { window ->
      if (window != null) frames.onVisibleRange(window.first, window.second)
    }
  }

  LazyRow(
    modifier = modifier.height(tileHeight),
    state = state,
    userScrollEnabled = userScrollEnabled,
  ) {
    items(grid.count) { index ->
      val tileWidth = with(density) { grid.tileWidthPxAt(index).toDp() }
      Box(
        Modifier
          .width(tileWidth)
          .fillMaxHeight()
          .background(colors.tile),
      ) {
        val bitmap = frames[index]
        if (bitmap == null) {
          placeholder(index)
        } else {
          Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
          )
        }

        if (index != grid.count - 1) {
          Box(
            Modifier
              .align(Alignment.CenterEnd)
              .width(DIVIDER_WIDTH)
              .fillMaxHeight()
              .background(colors.tileDivider),
          )
        }
      }
    }
  }
}

/**
 * How far a strip laid out on [grid] has scrolled, in content pixels.
 *
 * The strip's tiles are all one width, so the row's first visible item and its offset are enough to place every overlay
 * drawn over it without measuring anything.
 */
public fun LazyListState.stripScrollPx(grid: StripGrid): Float =
  firstVisibleItemIndex.toFloat() * grid.tileWidthPx + firstVisibleItemScrollOffset

/**
 * Scrolls the strip so [contentPx] sits at the left edge of the viewport.
 *
 * The inverse of [stripScrollPx], and written beside it so a change to how tiles are laid out cannot move one direction
 * without the other.
 */
public suspend fun LazyListState.scrollStripTo(
  grid: StripGrid,
  contentPx: Float,
) {
  if (grid.count == 0 || grid.tileWidthPx <= 0) return
  val target = contentPx.coerceAtLeast(0f)
  val index = (target / grid.tileWidthPx).toInt().coerceIn(0 until grid.count)
  scrollToItem(index, (target - index.toFloat() * grid.tileWidthPx).toInt())
}

@Preview
@Composable
private fun FilmstripStripPreview() {
  val frames =
    rememberFilmstripFrames(
      filmstrip = Filmstrip(),
      composition = previewComposition(),
      positions = emptyList(),
      heightPx = 0,
    )

  PreviewSurface {
    FilmstripStrip(frames = frames, grid = StripGrid(PreviewScale, tileWidthPx = 46))
  }
}

private val DIVIDER_WIDTH = 1.dp

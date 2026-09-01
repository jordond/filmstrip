package dev.jordond.filmstrip.compose.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.jordond.filmstrip.compose.ui.component.scrollStripTo
import dev.jordond.filmstrip.compose.ui.component.stripScrollPx
import dev.jordond.filmstrip.compose.ui.geometry.StripGrid
import dev.jordond.filmstrip.compose.ui.geometry.TimelineScale
import dev.jordond.filmstrip.compose.ui.geometry.TimelineZoom
import kotlin.time.Duration

/**
 * Where the timeline sits, and the geometry that follows from it.
 *
 * [scale] and [grid] are derived from [zoom] and follow it wherever it moves, so a zoom change is enough to move the
 * ruler, the strip and every overlay reading them without rebuilding this holder. [listState] is the strip's own scroll
 * state, and [scrollPx] turns it into the content pixel offset the ruler and the overlays place themselves against.
 *
 * Built by [rememberTimelineState].
 *
 * @property listState The strip's scroll state.
 */
@Stable
public class TimelineState internal constructor(
  public val listState: LazyListState,
  initialZoom: TimelineZoom,
  duration: Duration,
  tileWidthPx: Int,
) {
  /**
   * How far in the timeline is drawn.
   */
  public var zoom: TimelineZoom by mutableStateOf(initialZoom)
    internal set

  /**
   * The time to pixel mapping at [zoom].
   */
  public val scale: TimelineScale by derivedStateOf { zoom.scaleFor(duration) }

  /**
   * Which source times the strip's tiles sit at, at [scale].
   */
  public val grid: StripGrid by derivedStateOf { StripGrid(scale, tileWidthPx) }

  /**
   * How far the strip has scrolled, in content pixels.
   */
  public fun scrollPx(): Float = listState.stripScrollPx(grid)

  /**
   * Moves to [zoom], holding the timeline time under [focusViewportPx] still.
   *
   * The time under the focal point is read at the current [scale] before the ladder moves, and the strip is then
   * scrolled so that same time sits under [focusViewportPx] again at the new one. Without this a step doubles or halves
   * the content width and slides whatever the caller was looking at off screen.
   *
   * @param zoom The step to move to.
   * @param focusViewportPx The viewport x to hold still, such as a pinch's focal point.
   */
  public suspend fun zoomTo(
    zoom: TimelineZoom,
    focusViewportPx: Float,
  ) {
    val focusTime = scale.timeAt(scrollPx() + focusViewportPx)
    this.zoom = zoom
    listState.scrollStripTo(grid, scale.xOf(focusTime) - focusViewportPx)
  }

  /**
   * Moves one step in on the ladder, holding the timeline time under [focusViewportPx] still.
   *
   * @param focusViewportPx The viewport x to hold still.
   */
  public suspend fun zoomIn(focusViewportPx: Float) {
    zoomTo(zoom.zoomedIn(), focusViewportPx)
  }

  /**
   * Moves one step out on the ladder, holding the timeline time under [focusViewportPx] still.
   *
   * @param focusViewportPx The viewport x to hold still.
   */
  public suspend fun zoomOut(focusViewportPx: Float) {
    zoomTo(zoom.zoomedOut(), focusViewportPx)
  }
}

/**
 * Remembers a [TimelineState] over [duration].
 *
 * [zoom] left null fits the whole duration into [viewportWidthPx] the first time the holder is built, and the zoom
 * afterwards belongs to the returned state: [TimelineState.zoomTo] and its neighbours are what move it from there, and
 * a later [viewportWidthPx] change does not refit it. Passed non-null instead, [zoom] stays authoritative on every
 * recomposition, so a caller who hoists the zoom keeps owning it.
 *
 * The holder is rebuilt when [listState], [duration] or [tileWidthPx] change, but not when [viewportWidthPx] does on
 * its own, which is what lets a resize leave a zoom the user set alone. [listState] stays the same instance across a
 * rebuild, so the strip's scroll position survives it.
 *
 * @param duration How much source time the timeline covers.
 * @param viewportWidthPx How wide the timeline is drawn. Only read to fit [zoom] when it is null, and only the first
 * time the holder is built.
 * @param tileWidthPx How wide one tile is drawn.
 * @param zoom How far in the timeline is drawn, or null to fit [duration] into [viewportWidthPx] once and let the state
 * own it from there.
 * @param listState The strip's scroll state.
 * @return State keyed to [listState], [duration] and [tileWidthPx], following [zoom] for as long as it stays non-null.
 */
@Composable
public fun rememberTimelineState(
  duration: Duration,
  viewportWidthPx: Float,
  tileWidthPx: Int,
  zoom: TimelineZoom? = null,
  listState: LazyListState = rememberLazyListState(),
): TimelineState {
  val state =
    remember(listState, duration, tileWidthPx) {
      TimelineState(listState, zoom ?: TimelineZoom.fitting(duration, viewportWidthPx), duration, tileWidthPx)
    }

  if (zoom != null) {
    SideEffect { state.zoom = zoom }
  }

  return state
}

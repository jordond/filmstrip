package dev.jordond.filmstrip.compose.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

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
  private val duration: Duration,
  tileWidthPx: Int,
  followsViewport: Boolean,
) {
  /**
   * How far in the timeline is drawn.
   */
  public var zoom: TimelineZoom by mutableStateOf(initialZoom)
    internal set

  private var followsViewport: Boolean by mutableStateOf(followsViewport)

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
   * A state that was fitting its viewport stops when this moves [zoom], so a later viewport change leaves [zoom] where
   * this put it. A move to the zoom already set keeps the fit, such as [zoomOut] at the bottom of the ladder. The
   * [zoomIn] and [zoomOut] neighbours go through this too.
   *
   * @param zoom The step to move to.
   * @param focusViewportPx The viewport x to hold still, such as a pinch's focal point.
   */
  public suspend fun zoomTo(
    zoom: TimelineZoom,
    focusViewportPx: Float,
  ) {
    val focusTime = scale.timeAt(scrollPx() + focusViewportPx)
    if (zoom != this.zoom) followsViewport = false
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

  /**
   * Takes [zoom] from the host, which owns it from here and keeps the state from refitting.
   */
  internal fun hostZoom(zoom: TimelineZoom) {
    followsViewport = false
    this.zoom = zoom
  }

  /**
   * Fits [duration] into [viewportWidthPx], for as long as nothing has set a zoom since the state was built.
   */
  internal fun fitViewport(viewportWidthPx: Float) {
    if (followsViewport) zoom = TimelineZoom.fitting(duration, viewportWidthPx)
  }
}

/**
 * Remembers a [TimelineState] over [duration].
 *
 * [zoom] left null fits the whole duration into [viewportWidthPx], and refits once [viewportWidthPx] has held a new
 * width for a moment, so the strip fills it after a rotation or a window resize. A width still changing keeps the zoom
 * and the tile positions it has, along with the frames decoded for them, and the strip can sit short of or past the
 * viewport until the width settles. A duration too long for the bottom of the ladder stays there and scrolls instead.
 * [TimelineState.zoomTo] and its neighbours hand the zoom to the user once they move it, and it stays where they put
 * it from then on. Passed non-null instead, [zoom] stays authoritative on every recomposition and never refits, so a
 * caller who hoists the zoom keeps owning it, and setting it back to null leaves the state at the last one it was
 * given.
 *
 * The holder is rebuilt when [listState], [duration] or [tileWidthPx] change, but not when [viewportWidthPx] does on
 * its own, which is what lets a resize leave a zoom the user set alone. [listState] stays the same instance across a
 * rebuild, so the strip's scroll position survives it.
 *
 * @param duration How much source time the timeline covers.
 * @param viewportWidthPx How wide the timeline is drawn. Only read to fit [zoom] when it is null.
 * @param tileWidthPx How wide one tile is drawn.
 * @param zoom How far in the timeline is drawn, or null to fit [duration] into [viewportWidthPx] until the user zooms.
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
      TimelineState(
        listState = listState,
        initialZoom = zoom ?: TimelineZoom.fitting(duration, viewportWidthPx),
        duration = duration,
        tileWidthPx = tileWidthPx,
        followsViewport = zoom == null,
      )
    }

  SideEffect {
    if (zoom != null) state.hostZoom(zoom)
  }

  LaunchedEffect(state, viewportWidthPx) {
    delay(REFIT_SETTLE)
    state.fitViewport(viewportWidthPx)
  }

  return state
}

/**
 * How long the viewport has to hold one width before a fit refits to it.
 */
internal val REFIT_SETTLE: Duration = 200.milliseconds

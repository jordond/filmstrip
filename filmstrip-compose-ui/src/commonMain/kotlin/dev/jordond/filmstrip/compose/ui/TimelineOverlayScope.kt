package dev.jordond.filmstrip.compose.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Stable
import dev.jordond.filmstrip.compose.ui.interaction.PlayheadFollow

/**
 * What an overlay drawn over a [FilmstripTimeline]'s strip is given.
 *
 * A [BoxScope], so an overlay places itself with `align` and `matchParentSize` the way it would in any box. [timeline]
 * and [follow] are the same instances the timeline is drawing itself from, so chrome drawn here steps the zoom or
 * recentres the strip rather than keeping a second copy of either.
 *
 * A `Row` or a `Column` opened inside the overlay shadows this scope, so chrome that reads [timeline] or [follow] from
 * inside one takes them as parameters.
 *
 * ```
 * FilmstripTimeline(
 *   // ...
 *   overlay = { TimelineChrome(timeline, follow, Modifier.align(Alignment.TopEnd)) },
 * )
 *
 * @Composable
 * private fun TimelineChrome(
 *   timeline: TimelineState,
 *   follow: PlayheadFollow,
 *   modifier: Modifier = Modifier,
 * ) {
 *   val scope = rememberCoroutineScope()
 *   val focus = { timeline.listState.layoutInfo.viewportSize.width / 2f }
 *
 *   Row(modifier) {
 *     if (!follow.isEngaged) {
 *       IconButton(onClick = follow::engage) { Icon(Recentre, "Follow the playhead") }
 *     }
 *     IconButton(
 *       onClick = { scope.launch { timeline.zoomOut(focus()) } },
 *       enabled = timeline.zoom.step > TimelineZoom.Steps.first,
 *     ) { Icon(ZoomOut, "Zoom out") }
 *     IconButton(
 *       onClick = { scope.launch { timeline.zoomIn(focus()) } },
 *       enabled = timeline.zoom.step < TimelineZoom.Steps.last,
 *     ) { Icon(ZoomIn, "Zoom in") }
 *   }
 * }
 * ```
 *
 * @property timeline Where the timeline sits, and the geometry that follows from it.
 * @property follow Whether the strip is keeping the playhead on screen.
 */
@Stable
public interface TimelineOverlayScope : BoxScope {
  public val timeline: TimelineState

  public val follow: PlayheadFollow
}

/**
 * The scope a timeline hands its overlay, delegating the box part to the strip's own box.
 */
internal class TimelineOverlay(
  boxScope: BoxScope,
  override val timeline: TimelineState,
  override val follow: PlayheadFollow,
) : TimelineOverlayScope,
  BoxScope by boxScope

package dev.jordond.filmstrip.compose.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import dev.jordond.filmstrip.compose.PlayheadState
import dev.jordond.filmstrip.compose.ScrubState
import dev.jordond.filmstrip.compose.rememberFilmstripFrames
import dev.jordond.filmstrip.compose.ui.component.FilmstripStrip
import dev.jordond.filmstrip.compose.ui.component.Playhead
import dev.jordond.filmstrip.compose.ui.component.TimelineRuler
import dev.jordond.filmstrip.compose.ui.component.TrimOverlay
import dev.jordond.filmstrip.compose.ui.geometry.TimelineZoom
import dev.jordond.filmstrip.compose.ui.interaction.rememberPlayheadFollow
import dev.jordond.filmstrip.compose.ui.interaction.scrubTimeline
import dev.jordond.filmstrip.compose.ui.interaction.wheelZoomTimeline
import dev.jordond.filmstrip.compose.ui.interaction.zoomTimeline
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.media.FrameRenderer
import kotlin.time.Duration

/**
 * A ruler, a scrolling strip, a playhead and an optional trim window, assembled.
 *
 * The ruler carries the scrub gesture and the strip scrolls under a finger, which keeps the two apart: a drag on the
 * ruler moves the playhead, a drag on the strip moves the view. The strip follows the playhead during playback and
 * stops as soon as the user scrolls it.
 *
 * One track is drawn. A composition with several renders as the timeline they lay out on, with no clip edges marked.
 *
 * Nothing here needs a player. [position] is read from whatever clock the host runs, so a preview it draws itself keeps
 * a timeline as honest as a `VideoPlayer` does, and a timeline with no [scrub] is simply one a drag on the ruler does
 * not seek.
 *
 * Assemble the parts directly for anything this does not lay out the way it is wanted. Nothing here is doing work the
 * public pieces cannot.
 *
 * ```
 * var trimRange by remember(source) { mutableStateOf(TimeRange.from(Duration.ZERO)) }
 * val composition = remember(source) { compositionOf { clip(source) } }
 * val player = rememberFilmstripPlayer(filmstrip, composition)
 * val playerState by player.state.collectAsState()
 * val playhead = rememberPlayheadState(player)
 *
 * Column {
 *   VideoSurface(player, Modifier.weight(1f))
 *
 *   FilmstripTimeline(
 *     renderer = filmstrip,
 *     composition = composition,
 *     duration = playhead.duration ?: Duration.ZERO,
 *     position = playhead.positionProvider(),
 *     isPlaying = playerState.isPlaying,
 *     scrub = rememberScrubState(player),
 *     trim = trimRange,
 *     // Confines playback to the window while the tiles keep covering the whole source, so a
 *     // drag does not rebuild the composition the player is running.
 *     onTrimChange = { trimRange = it; player.setPlaybackRange(it) },
 *   )
 * }
 *
 * // Where the trim finally reaches the edit, on the way to the encoder.
 * compositionOf { clip(source) { trim(trimRange) } }
 * ```
 *
 * @param renderer What renders the tiles, usually the `Filmstrip` itself.
 * @param composition The edit to draw.
 * @param duration How much source time the strip spans. With a [sourceOffset] this is the whole source the tiles cover
 * rather than the shorter run the player reports.
 * @param position Where the playhead sits, from [PlayheadState.positionProvider] or from whatever clock the host drives
 * its own transport with.
 * @param isPlaying Whether the player is advancing, from `PlayerState.isPlaying` .
 * @param modifier Modifier for the timeline.
 * @param scrub The protocol the ruler's gestures drive, or null for a timeline a drag on the ruler does not seek.
 * @param zoom How far in the timeline is drawn, or null to fit the whole composition on screen.
 * @param trim Where the trim sits. The window is drawn only when this and [onTrimChange] are both given, so a timeline
 * without one leaves both out.
 * @param onTrimChange Called with the range a trim gesture asks for, already constrained. The handles move only where
 * [trim] next puts them.
 * @param options Everything optional. This applies a pinch and a modified scroll wheel to the strip; keyboard control
 * does not go through here, since [dev.jordond.filmstrip.compose.ui.interaction.timelineKeys] needs focus and a
 * component that took it on composition would be stealing it from whatever the host had focused before. A host that
 * wants the keys applies it itself, such as to an element it places in [overlay], where [TimelineState] is at hand.
 * @param colors What the timeline paints with.
 * @param sourceOffset Where the player's zero sits on the timeline's clock, so
 * `timelineTime == playerTime + sourceOffset()` . The default suits a player that runs over the whole composition the
 * strip draws.
 * @param placeholder What fills a tile whose frame has not arrived.
 * @param overlay Drawn over the tiles and under the trim window and the playhead, for decoration and for chrome that
 * reads the timeline's own state.
 */
@Composable
public fun FilmstripTimeline(
  renderer: FrameRenderer,
  composition: EditComposition,
  duration: Duration,
  position: () -> Duration,
  isPlaying: Boolean,
  modifier: Modifier = Modifier,
  scrub: ScrubState? = null,
  zoom: TimelineZoom? = null,
  trim: TimeRange? = null,
  onTrimChange: ((TimeRange) -> Unit)? = null,
  options: TimelineOptions = TimelineOptions.Default,
  colors: TimelineColors = FilmstripTimelineDefaults.Palette,
  sourceOffset: () -> Duration = { Duration.ZERO },
  placeholder: @Composable (index: Int) -> Unit = { },
  overlay: @Composable TimelineOverlayScope.() -> Unit = { },
) {
  BoxWithConstraints(modifier.fillMaxWidth()) {
    val density = LocalDensity.current
    val viewportWidthPx = with(density) { maxWidth.toPx() }
    val tileWidthPx = with(density) { options.tileWidth.roundToPx() }
    val heightPx = with(density) { options.stripHeight.roundToPx() }

    val timeline =
      rememberTimelineState(
        duration = duration,
        viewportWidthPx = viewportWidthPx,
        tileWidthPx = tileWidthPx,
        zoom = zoom,
      )
    val scale = timeline.scale
    val grid = timeline.grid
    val listState = timeline.listState
    val scrollPx = timeline::scrollPx

    val frames =
      rememberFilmstripFrames(
        renderer = renderer,
        composition = composition,
        positions = grid.positions,
        heightPx = heightPx,
        maxBytes = options.maxBytes,
        overscan = options.overscan,
      )

    val follow =
      rememberPlayheadFollow(
        position = position,
        grid = grid,
        state = listState,
        isPlaying = isPlaying,
        enabled = options.followPlayhead,
        sourceOffset = sourceOffset,
      )

    Column(Modifier.fillMaxWidth()) {
      if (options.showRuler) {
        TimelineRuler(
          scale = scale,
          modifier =
            if (scrub == null) Modifier else Modifier.scrubTimeline(scrub, scale, scrollPx, sourceOffset),
          scrollPx = scrollPx,
          height = options.rulerHeight,
          colors = colors,
        )
      }

      Box(
        Modifier
          .fillMaxWidth()
          .height(options.stripHeight)
          .zoomTimeline(timeline, enabled = options.pinchZoom && zoom == null)
          .wheelZoomTimeline(timeline, enabled = options.wheelZoom && zoom == null),
      ) {
        FilmstripStrip(
          frames = frames,
          grid = grid,
          state = listState,
          tileHeight = options.stripHeight,
          colors = colors,
          placeholder = placeholder,
        )

        val boxScope = this
        remember(boxScope, timeline, follow) { TimelineOverlay(boxScope, timeline, follow) }.overlay()

        if (trim != null && onTrimChange != null) {
          TrimOverlay(
            range = trim,
            scale = scale,
            onRangeChange = onTrimChange,
            scrollPx = scrollPx,
            constraint = options.trimConstraint,
            colors = colors,
          )
        }

        if (options.showPlayhead) {
          Playhead(
            position = position,
            scale = scale,
            scrollPx = scrollPx,
            sourceOffset = sourceOffset,
            colors = colors,
          )
        }
      }
    }
  }
}

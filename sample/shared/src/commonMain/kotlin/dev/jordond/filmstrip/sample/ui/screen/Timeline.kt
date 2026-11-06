package dev.jordond.filmstrip.sample.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.compose.ScrubState
import dev.jordond.filmstrip.compose.rememberFilmstripFrames
import dev.jordond.filmstrip.compose.rememberScrubState
import dev.jordond.filmstrip.compose.ui.FilmstripTimelineDefaults
import dev.jordond.filmstrip.compose.ui.TimelineColors
import dev.jordond.filmstrip.compose.ui.TimelineState
import dev.jordond.filmstrip.compose.ui.component.FilmstripStrip
import dev.jordond.filmstrip.compose.ui.component.Playhead
import dev.jordond.filmstrip.compose.ui.component.TimelineRuler
import dev.jordond.filmstrip.compose.ui.component.TrimOverlay
import dev.jordond.filmstrip.compose.ui.geometry.TimelineScale
import dev.jordond.filmstrip.compose.ui.geometry.TimelineZoom
import dev.jordond.filmstrip.compose.ui.interaction.PlayheadFollow
import dev.jordond.filmstrip.compose.ui.interaction.rememberPlayheadFollow
import dev.jordond.filmstrip.compose.ui.interaction.scrubTimeline
import dev.jordond.filmstrip.compose.ui.interaction.timelineKeys
import dev.jordond.filmstrip.compose.ui.interaction.wheelZoomTimeline
import dev.jordond.filmstrip.compose.ui.interaction.zoomTimeline
import dev.jordond.filmstrip.compose.ui.rememberTimelineState
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.sample.SampleAppState
import dev.jordond.filmstrip.sample.ui.SampleIcons
import dev.jordond.filmstrip.sample.ui.Tokens
import dev.jordond.filmstrip.sample.ui.isCompactWidth
import kotlinx.coroutines.launch
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The strip under the viewport: the whole source laid out end to end, the lanes under it, the trim
 * window over it and the playhead across all of them.
 *
 * Tiles are addressed in source time rather than trimmed time, so the strip covers the same ground
 * no matter where the trim handles sit. The ruler, strip and lanes share one [TimelineState], which
 * is what keeps them on one scroll and one scale.
 */
@Composable
public fun Timeline(
  state: SampleAppState,
  modifier: Modifier = Modifier,
) {
  val duration = state.sourceDuration ?: Duration.ZERO
  val edit = state.edit
  val composition = state.filmstripComposition()?.takeIf { duration > Duration.ZERO }
  val compact = isCompactWidth()
  val stripHeight = if (compact) STRIP_HEIGHT_COMPACT else STRIP_HEIGHT

  // Seeking goes through the sample's own clock rather than straight at the player, so the ruler
  // scrubs the schematic before a preview is open just as it scrubs the video, and the playhead
  // keeps up with the finger instead of waiting for the player to report back. Begin and end are
  // where an open player relaxes its seek accuracy.
  val scrub =
    rememberScrubState(
      onSeek = { position -> state.seekTo(position.toSeconds()) },
      onBegin = state::beginScrub,
      onEnd = state::endScrub,
    )
  val position = remember(state) { { state.positionSeconds.toDuration() } }

  if (composition == null) {
    Box(
      modifier
        .fillMaxWidth()
        .height(stripHeight)
        .background(MaterialTheme.colorScheme.surfaceContainer),
    )
    return
  }

  val colors = sampleColors()
  // The player runs over the trimmed composition while the strip spans the whole source, so the
  // ruler, the playhead and the follow all carry the trim start as their sourceOffset.
  val sourceOffset = { edit.trimOffset() }

  BoxWithConstraints(modifier.fillMaxWidth()) {
    val density = LocalDensity.current
    val timeline =
      rememberTimelineState(
        duration = duration,
        viewportWidthPx = with(density) { maxWidth.toPx() },
        tileWidthPx = with(density) { FilmstripTimelineDefaults.TileWidth.roundToPx() },
      )
    val scale = timeline.scale
    val frames =
      rememberFilmstripFrames(
        renderer = state.filmstrip,
        composition = composition,
        positions = timeline.grid.positions,
        heightPx = with(density) { stripHeight.roundToPx() },
        exportSpec = state.spec(),
      )
    val follow =
      rememberPlayheadFollow(
        position = position,
        grid = timeline.grid,
        state = timeline.listState,
        isPlaying = state.playing,
        sourceOffset = sourceOffset,
      )
    val lanes = laneSpans(edit, duration)
    val laneHeight = if (compact) LANE_HEIGHT_COMPACT else LANE_HEIGHT

    Box(Modifier.fillMaxWidth()) {
      Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LANE_GAP)) {
        TimelineRuler(
          scale = scale,
          modifier = Modifier.scrubTimeline(scrub, scale, timeline::scrollPx, sourceOffset),
          scrollPx = timeline::scrollPx,
          colors = colors,
        )

        Column(
          Modifier
            .fillMaxWidth()
            .zoomTimeline(timeline)
            .wheelZoomTimeline(timeline),
          verticalArrangement = Arrangement.spacedBy(LANE_GAP),
        ) {
          Box(
            Modifier
              .fillMaxWidth()
              .height(stripHeight)
              .background(Tokens.trackGround),
          ) {
            FilmstripStrip(
              frames = frames,
              grid = timeline.grid,
              state = timeline.listState,
              tileHeight = stripHeight,
              colors = colors,
            )
            if (!compact) Sprockets(scale, timeline::scrollPx)
            KeyboardControl(timeline, scrub, position)

            val trim =
              if (edit.trimEnabled) {
                TimeRange(edit.trimStartSeconds.toDuration(), edit.trimEndSeconds.toDuration())
              } else {
                null
              }
            trim?.let { TrimWindow(it, scale, timeline::scrollPx) }
            TimelineChrome(timeline, follow, Modifier.align(Alignment.TopEnd))
            trim?.let {
              TrimOverlay(
                range = it,
                scale = scale,
                onRangeChange = { next ->
                  edit.trimStartSeconds = next.start.toSeconds()
                  edit.trimEndSeconds = (next.endExclusive ?: duration).toSeconds()
                  state.onEditChanged()
                },
                scrollPx = timeline::scrollPx,
                colors = colors,
                handleWidth = if (compact) TRIM_HANDLE_COMPACT else TRIM_HANDLE,
              )
            }
          }

          lanes.grade?.let { span ->
            TimelineLane("GRADE", span, Tokens.cyan, scale, timeline::scrollPx, timeline.listState, laneHeight)
          }
          lanes.text?.let { span ->
            TimelineLane("TEXT", span, Tokens.pink, scale, timeline::scrollPx, timeline.listState, laneHeight)
          }
        }
      }

      Playhead(
        position = position,
        scale = scale,
        modifier = Modifier.matchParentSize(),
        scrollPx = timeline::scrollPx,
        sourceOffset = sourceOffset,
        colors = colors,
      )
    }
  }
}

/**
 * The timeline's palette, taken from the app's theme.
 *
 * `filmstrip-compose-ui` draws with foundation alone and takes its colors as parameters, so this
 * is the one place Material and the timeline meet. The trim scrim is clear because [TrimWindow]
 * hatches the cut ranges under the handles.
 */
@Composable
private fun sampleColors(): TimelineColors {
  val scheme = MaterialTheme.colorScheme
  return remember(scheme) {
    FilmstripTimelineDefaults.colors(
      tile = scheme.surfaceContainer,
      tileDivider = scheme.scrim,
      ruler = Tokens.trackTick,
      rulerLabel = Tokens.trackTick,
      playhead = scheme.onSurface,
      trimHandle = scheme.primary,
      trimHandleGrip = scheme.background,
      trimScrim = Color.Transparent,
    )
  }
}

/**
 * Hatching over what the trim cuts and the amber rails along what it keeps, drawn under the handles.
 *
 * It reads the scroll while drawing and stops at the end of the source, so the part of the strip the source does not
 * reach stays plain ground.
 */
@Composable
private fun TrimWindow(
  range: TimeRange,
  scale: TimelineScale,
  scrollPx: () -> Float,
) {
  val rail = MaterialTheme.colorScheme.primary
  Canvas(Modifier.fillMaxSize()) {
    val offset = scrollPx()
    val startX = (scale.xOf(range.start) - offset).coerceIn(0f, size.width)
    val endX = (scale.xOf(range.endExclusive ?: scale.duration) - offset).coerceIn(0f, size.width)
    val contentEnd = (scale.contentWidthPx - offset).coerceIn(0f, size.width)

    val hatch = hatchBrush(offset)
    if (startX > 0f) drawRect(hatch, Offset.Zero, Size(startX, size.height))
    if (contentEnd > endX) drawRect(hatch, Offset(endX, 0f), Size(contentEnd - endX, size.height))

    val railHeight = TRIM_RAIL.toPx()
    drawRect(rail, Offset(startX, 0f), Size(endX - startX, railHeight))
    drawRect(rail, Offset(startX, size.height - railHeight), Size(endX - startX, railHeight))
  }
}

/**
 * Diagonal stripes on hard stops, moved by [scrollOffset] so they travel with the tiles.
 */
private fun DrawScope.hatchBrush(scrollOffset: Float): Brush {
  val dark = Tokens.groundDeep.copy(alpha = HATCH_ALPHA)
  val light = Tokens.trackHatch.copy(alpha = HATCH_ALPHA)
  val run = HATCH_PERIOD.toPx() / sqrt(2f)
  return Brush.linearGradient(
    0f to dark,
    0.5f to dark,
    0.5f to light,
    1f to light,
    start = Offset(-scrollOffset, 0f),
    end = Offset(run - scrollOffset, run),
    tileMode = TileMode.Repeated,
  )
}

/**
 * The zoom steps and the recentre button, drawn over the strip.
 *
 * The buttons step the same ladder a pinch on the strip moves, and the recentre button appears
 * once a scroll has taken the strip off the playhead.
 */
@Composable
private fun TimelineChrome(
  timeline: TimelineState,
  follow: PlayheadFollow,
  modifier: Modifier = Modifier,
) {
  val scope = rememberCoroutineScope()
  val focusViewportPx = { timeline.listState.layoutInfo.viewportSize.width / 2f }

  Row(
    modifier = modifier.padding(horizontal = 4.dp),
    horizontalArrangement = Arrangement.End,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (!follow.isEngaged) {
      IconButton(
        onClick = follow::engage,
        modifier = Modifier.size(CHROME_BUTTON),
        colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary),
      ) {
        Icon(SampleIcons.Recenter, contentDescription = "Follow the playhead", modifier = Modifier.size(CHROME_ICON))
      }
    }

    IconButton(
      onClick = { scope.launch { timeline.zoomOut(focusViewportPx()) } },
      enabled = timeline.zoom.step > TimelineZoom.Steps.first,
      modifier = Modifier.size(CHROME_BUTTON),
    ) {
      Icon(SampleIcons.ZoomOut, contentDescription = "Zoom out", modifier = Modifier.size(CHROME_ICON))
    }

    IconButton(
      onClick = { scope.launch { timeline.zoomIn(focusViewportPx()) } },
      enabled = timeline.zoom.step < TimelineZoom.Steps.last,
      modifier = Modifier.size(CHROME_BUTTON),
    ) {
      Icon(SampleIcons.ZoomIn, contentDescription = "Zoom in", modifier = Modifier.size(CHROME_ICON))
    }
  }
}

/**
 * An invisible layer that lets the keyboard drive the timeline once it has focus.
 *
 * Nothing else on this screen wants the keyboard, so this claims it as soon as it appears rather than waiting for a
 * click. A screen with other focusable content would request it in response to something the user does instead.
 */
@Composable
private fun KeyboardControl(
  timeline: TimelineState,
  scrub: ScrubState,
  position: () -> Duration,
) {
  val focusRequester = remember { FocusRequester() }
  LaunchedEffect(focusRequester) { focusRequester.requestFocus() }

  Box(
    Modifier
      .fillMaxSize()
      .focusRequester(focusRequester)
      .timelineKeys(timeline, scrub, position),
  )
}

/**
 * Perforations along both edges of the strip, which are decoration rather than timeline, as far as the source reaches.
 */
@Composable
private fun Sprockets(
  scale: TimelineScale,
  scrollPx: () -> Float,
) {
  val color = MaterialTheme.colorScheme.scrim.copy(alpha = SPROCKET_ALPHA)
  Canvas(Modifier.fillMaxSize()) {
    val inset = SPROCKET_INSET.toPx()
    val hole = Size(SPROCKET_WIDTH.toPx(), SPROCKET_HEIGHT.toPx())
    val step = SPROCKET_STEP.toPx()
    val end = (scale.contentWidthPx - scrollPx()).coerceAtMost(size.width)
    var x = 0f
    while (x + hole.width <= end) {
      drawRect(color, Offset(x, inset), hole)
      drawRect(color, Offset(x, size.height - inset - hole.height), hole)
      x += step
    }
  }
}

private fun Float.toDuration(): Duration = toDouble().seconds

private fun Duration.toSeconds(): Float = inWholeMilliseconds / 1000f

private val STRIP_HEIGHT = 56.dp
private val STRIP_HEIGHT_COMPACT = 48.dp
private val LANE_HEIGHT = 18.dp
private val LANE_HEIGHT_COMPACT = 16.dp
private val LANE_GAP = 3.dp

private val TRIM_HANDLE = 10.dp
private val TRIM_HANDLE_COMPACT = 12.dp
private val TRIM_RAIL = 3.dp

private val HATCH_PERIOD = 8.dp
private const val HATCH_ALPHA = 0.88f

private val SPROCKET_INSET = 3.dp
private val SPROCKET_WIDTH = 6.dp
private val SPROCKET_HEIGHT = 4.dp
private val SPROCKET_STEP = 14.dp
private const val SPROCKET_ALPHA = 0.7f

private val CHROME_BUTTON = 30.dp
private val CHROME_ICON = 16.dp

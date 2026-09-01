package dev.jordond.filmstrip.sample.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.compose.ScrubState
import dev.jordond.filmstrip.compose.rememberScrubState
import dev.jordond.filmstrip.compose.ui.FilmstripTimeline
import dev.jordond.filmstrip.compose.ui.FilmstripTimelineDefaults
import dev.jordond.filmstrip.compose.ui.TimelineColors
import dev.jordond.filmstrip.compose.ui.TimelineState
import dev.jordond.filmstrip.compose.ui.geometry.TimelineZoom
import dev.jordond.filmstrip.compose.ui.interaction.PlayheadFollow
import dev.jordond.filmstrip.compose.ui.interaction.timelineKeys
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.sample.EditState
import dev.jordond.filmstrip.sample.SampleAppState
import dev.jordond.filmstrip.sample.ui.SampleIcons
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The strip under the viewport: the whole source laid out end to end, with the trim window over it
 * and the playhead in it.
 *
 * Tiles are addressed in source time rather than trimmed time, so the strip covers the same ground
 * no matter where the trim handles sit. The library draws all of it, and the sample adds only what
 * a foundation-only artifact cannot ship: the sprockets, the tile placeholders, the Material
 * chrome and the theme's colours.
 */
@Composable
public fun Timeline(
  state: SampleAppState,
  modifier: Modifier = Modifier,
) {
  val duration = state.sourceDuration ?: Duration.ZERO
  val edit = state.edit
  val composition = state.filmstripComposition()?.takeIf { duration > Duration.ZERO }

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
        .height(FilmstripTimelineDefaults.StripHeight)
        .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(10.dp)),
    )
    return
  }

  FilmstripTimeline(
    renderer = state.filmstrip,
    composition = composition,
    duration = duration,
    position = position,
    isPlaying = state.playing,
    modifier = modifier,
    scrub = scrub,
    trim = if (edit.trimEnabled) TimeRange(
      start = edit.trimStartSeconds.toDuration(),
      endExclusive = edit.trimEndSeconds.toDuration(),
    ) else null,
    onTrimChange = { next ->
      edit.trimStartSeconds = next.start.toSeconds()
      edit.trimEndSeconds = (next.endExclusive ?: duration).toSeconds()
      state.onEditChanged()
    },
    colors = sampleColors(),
    // The player runs over the trimmed composition while the strip spans the whole source, so the
    // ruler, the playhead and the follow all carry the trim start as their sourceOffset.
    sourceOffset = { edit.trimOffset() },
    placeholder = { index -> TilePlaceholder(index) },
    overlay = {
      Sprockets()
      KeyboardControl(timeline, scrub, position)
      TimelineChrome(timeline, follow, Modifier.align(Alignment.TopEnd))
    },
  )
}

/**
 * The timeline's palette, taken from the app's theme.
 *
 * `filmstrip-compose-ui` draws with foundation alone and takes its colours as parameters, so this
 * is the one place Material and the timeline meet.
 */
@Composable
private fun sampleColors(): TimelineColors {
  val scheme = MaterialTheme.colorScheme
  return remember(scheme) {
    FilmstripTimelineDefaults.colors(
      tile = scheme.surfaceContainer,
      tileDivider = scheme.scrim.copy(alpha = 0.5f),
      ruler = scheme.outline,
      rulerLabel = scheme.outline,
      playhead = scheme.onSurface,
      trimHandle = scheme.primary,
      trimHandleGrip = scheme.background,
      trimScrim = scheme.scrim.copy(alpha = 0.72f),
    )
  }
}

/**
 * The zoom steps and the recentre button, drawn over the strip.
 *
 * The buttons step the same ladder a pinch on the strip moves, and the recentre button appears
 * once a scroll has taken the strip off the playhead. Both are handed the timeline's own state by
 * the overlay scope rather than keeping a copy of it.
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
 * What a tile shows before its frame arrives.
 *
 * A slot rather than a colour on the library's side, because what fills an empty tile is a design
 * opinion and this one is the sample's.
 */
@Composable
private fun TilePlaceholder(index: Int) {
  val hue = (index * 37f) % 360f
  Box(
    Modifier
      .fillMaxSize()
      .background(
        Brush.verticalGradient(
          listOf(
            Color.hsv(hue, 0.35f, 0.42f),
            Color.hsv((hue + 24f) % 360f, 0.45f, 0.22f),
          ),
        ),
      ),
  )
}

/**
 * Perforations along both edges of the strip, which are decoration rather than timeline.
 */
@Composable
private fun Sprockets() {
  Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
    repeat(2) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .height(9.dp)
          .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.65f))
          .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        repeat(18) {
          Box(
            Modifier
              .size(width = 7.dp, height = 4.dp)
              .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(1.dp)),
          )
        }
      }
    }
  }
}

/**
 * Where the trim starts, or zero while trimming is off.
 */
private fun EditState.trimOffset(): Duration = if (trimEnabled) trimStartSeconds.toDuration() else Duration.ZERO

private fun Float.toDuration(): Duration = toDouble().seconds

private fun Duration.toSeconds(): Float = inWholeMilliseconds / 1000f

private val CHROME_BUTTON = 30.dp
private val CHROME_ICON = 16.dp

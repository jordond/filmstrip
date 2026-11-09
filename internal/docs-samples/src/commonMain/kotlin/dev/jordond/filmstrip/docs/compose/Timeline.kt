package dev.jordond.filmstrip.docs.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.compose.VideoSurface
import dev.jordond.filmstrip.compose.rememberFilmstripPlayer
import dev.jordond.filmstrip.compose.rememberPlayheadState
import dev.jordond.filmstrip.compose.rememberScrubState
import dev.jordond.filmstrip.compose.ui.FilmstripTimeline
import dev.jordond.filmstrip.compose.ui.TimelineOptions
import dev.jordond.filmstrip.compose.ui.VideoStage
import dev.jordond.filmstrip.compose.ui.component.CropOverlay
import dev.jordond.filmstrip.compose.ui.geometry.TimelineZoom
import dev.jordond.filmstrip.compose.ui.interaction.TrimConstraint
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.geometry.crop
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.media.MediaSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Composable
internal fun Editor(
  filmstrip: Filmstrip,
  source: MediaSource,
) {
  // region timeline
  val composition = remember(source) { compositionOf { clip(source) } }
  val player = rememberFilmstripPlayer(filmstrip, composition)
  val playerState by player.state.collectAsState()
  val playhead = rememberPlayheadState(player)

  Column {
    VideoSurface(player, Modifier.weight(1f))

    FilmstripTimeline(
      renderer = filmstrip,
      composition = composition,
      duration = playhead.duration ?: Duration.ZERO,
      position = playhead.positionProvider(),
      isPlaying = playerState.isPlaying,
      scrub = rememberScrubState(player),
    )
  }
  // endregion
}

@Composable
internal fun TrimEditor(
  filmstrip: Filmstrip,
  source: MediaSource,
) {
  // region trim
  var trimRange by remember(source) { mutableStateOf(TimeRange.from(Duration.ZERO)) }
  val composition = remember(source) { compositionOf { clip(source) } }
  val player = rememberFilmstripPlayer(filmstrip, composition)
  val playerState by player.state.collectAsState()
  val playhead = rememberPlayheadState(player)

  FilmstripTimeline(
    renderer = filmstrip,
    composition = composition,
    duration = playhead.duration ?: Duration.ZERO,
    position = playhead.positionProvider(),
    isPlaying = playerState.isPlaying,
    scrub = rememberScrubState(player),
    trim = trimRange,
    onTrimChange = { range ->
      trimRange = range
      player.setPlaybackRange(range)
    },
  )
  // endregion
}

internal fun trimmedForExport(
  source: MediaSource,
  trimRange: TimeRange,
): Composition {
  // region trim-export
  val composition = compositionOf { clip(source) { trim(trimRange) } }
  // endregion
  return composition
}

@Composable
internal fun OptionedTimeline(
  filmstrip: Filmstrip,
  composition: Composition,
  duration: Duration,
  position: () -> Duration,
  isPlaying: Boolean,
) {
  // region options
  FilmstripTimeline(
    renderer = filmstrip,
    composition = composition,
    duration = duration,
    position = position,
    isPlaying = isPlaying,
    zoom = TimelineZoom.of(4),
    options =
      TimelineOptions(
        followPlayhead = false,
        trimConstraint = TrimConstraint.MinDuration(1.seconds),
      ),
  )
  // endregion
}

@Composable
internal fun CropStage(
  filmstrip: Filmstrip,
  source: MediaSource,
  aspect: Float,
) {
  // region stage
  val composition = remember(source) { compositionOf { clip(source) } }
  val player = rememberFilmstripPlayer(filmstrip, composition)
  var rect by remember(source) { mutableStateOf(NormalizedRect.Full) }

  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    VideoStage(
      player = player,
      outputAspect = aspect,
      modifier = Modifier.padding(16.dp),
    ) {
      CropOverlay(rect = rect, onRectChange = { rect = it })
    }
  }
  // endregion
}

internal fun croppedForExport(
  source: MediaSource,
  rect: NormalizedRect,
): Composition {
  // region stage-export
  val composition =
    compositionOf {
      clip(source)
      effects { crop(rect) }
    }
  // endregion
  return composition
}

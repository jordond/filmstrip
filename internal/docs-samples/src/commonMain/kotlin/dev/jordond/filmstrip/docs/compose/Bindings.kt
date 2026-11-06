package dev.jordond.filmstrip.docs.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.compose.PlaybackEventEffect
import dev.jordond.filmstrip.compose.VideoContentScale
import dev.jordond.filmstrip.compose.VideoSurface
import dev.jordond.filmstrip.compose.rememberFilmstripFrame
import dev.jordond.filmstrip.compose.rememberFilmstripFrames
import dev.jordond.filmstrip.compose.rememberFilmstripPlayer
import dev.jordond.filmstrip.compose.rememberPlayheadState
import dev.jordond.filmstrip.compose.rememberVideoSurfaceState
import dev.jordond.filmstrip.compose.videoAspect
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.player.PlaybackEvent
import kotlin.time.Duration

@Composable
internal fun PlayerScreen(
  filmstrip: Filmstrip,
  composition: EditComposition,
) {
  // region player
  val player = rememberFilmstripPlayer(filmstrip, composition)
  val surface = rememberVideoSurfaceState(player)

  VideoSurface(
    player = player,
    modifier = Modifier.fillMaxWidth().videoAspect(surface, VideoContentScale.Fit),
  )
  // endregion
}

@Composable
internal fun PlayheadLabel(
  filmstrip: Filmstrip,
  composition: EditComposition,
) {
  // region playhead
  val player = rememberFilmstripPlayer(filmstrip, composition)
  val playhead = rememberPlayheadState(player)

  BasicText("${playhead.position} of ${playhead.duration}")
  // endregion
}

@Composable
internal fun PlayerEvents(
  filmstrip: Filmstrip,
  composition: EditComposition,
) {
  // region events
  val player = rememberFilmstripPlayer(filmstrip, composition)

  PlaybackEventEffect(player) { event ->
    if (event is PlaybackEvent.Failed) println(event.error.message)
  }
  // endregion
}

@Composable
internal fun PosterFrame(
  filmstrip: Filmstrip,
  composition: EditComposition,
  at: Duration,
) {
  // region poster
  val poster = rememberFilmstripFrame(filmstrip, composition, at, heightPx = 360)

  if (poster != null) {
    Image(poster, contentDescription = null)
  }
  // endregion
}

@Composable
internal fun FrameStrip(
  filmstrip: Filmstrip,
  composition: EditComposition,
  positions: List<Duration>,
) {
  // region strip
  val frames = rememberFilmstripFrames(filmstrip, composition, positions, heightPx = 96)
  val listState = rememberLazyListState()

  LaunchedEffect(frames, listState) {
    snapshotFlow { listState.firstVisibleItemIndex to listState.layoutInfo.visibleItemsInfo.size }
      .collect { (first, visible) -> frames.onVisibleRange(first, visible) }
  }

  LazyRow(state = listState) {
    items(frames.count) { index ->
      Box(Modifier.size(64.dp, 96.dp)) {
        frames[index]?.let { frame -> Image(frame, contentDescription = null) }
      }
    }
  }
  // endregion
}

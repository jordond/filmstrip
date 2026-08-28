package dev.jordond.filmstrip.sample

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

@Composable
actual fun ResultVideoPlayer(
  path: String,
  modifier: Modifier,
) {
  val context = LocalContext.current
  val player = remember(path) {
    ExoPlayer.Builder(context).build().apply {
      setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
      prepare()
    }
  }

  DisposableEffect(path) {
    onDispose { player.release() }
  }

  AndroidView(
    modifier = modifier,
    factory = { PlayerView(it).apply { this.player = player } },
  )
}

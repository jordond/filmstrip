@file:OptIn(ExperimentalForeignApi::class)

package dev.jordond.filmstrip.sample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitViewController
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.pause
import platform.AVKit.AVPlayerViewController
import platform.Foundation.NSURL

@Composable
actual fun ResultVideoPlayer(
  path: String,
  modifier: Modifier,
) {
  val player = remember(path) { AVPlayer(uRL = NSURL.fileURLWithPath(path)) }

  DisposableEffect(player) {
    onDispose { player.pause() }
  }

  UIKitViewController(
    factory = { AVPlayerViewController().apply { this.player = player } },
    modifier = modifier,
    update = { it.player = player },
  )
}

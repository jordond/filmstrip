package dev.jordond.filmstrip.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import dev.jordond.filmstrip.playback.attachPreviewLayer
import dev.jordond.filmstrip.player.VideoPlayer
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCClass
import kotlinx.cinterop.readValue
import platform.AVFoundation.AVLayerVideoGravity
import platform.AVFoundation.AVLayerVideoGravityResize
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerLayer
import platform.CoreGraphics.CGRectZero
import platform.UIKit.UIView
import platform.UIKit.UIViewMeta

/**
 * iOS renders into a `UIView` whose backing layer is an `AVPlayerLayer`.
 *
 * The layer takes the player from [VideoPlayer.nativePlayer] and reports back to the engine when it
 * has pixels, which is what closes the shutter on [VideoSurfaceState]. A player with no Apple
 * backend under it has no platform player to attach, so this lays out an empty box of the size it
 * was given.
 *
 * The interop view sits below the Metal canvas and Compose punches a hole for it, so content drawn
 * after this composable still composites over the video and `Modifier.clip` still rounds it.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
public actual fun VideoSurface(
  player: VideoPlayer,
  modifier: Modifier,
  contentScale: VideoContentScale,
) {
  val native = player.nativePlayer as? AVPlayer
  if (native == null) {
    Box(modifier)
    return
  }

  val view = remember { PlayerLayerView() }
  val gravity = contentScale.videoGravity()

  DisposableEffect(player, view) {
    val handle = player.attachPreviewLayer(view.playerLayer)
    onDispose { handle.cancel() }
  }

  UIKitView(
    factory = { view },
    modifier = modifier,
    update = { hosted ->
      hosted.playerLayer.player = native
      hosted.playerLayer.videoGravity = gravity
    },
    onRelease = { hosted -> hosted.playerLayer.player = null },
  )
}

/**
 * A view whose backing layer is the player layer, so the layer tracks the view's bounds without a
 * resize hook of its own.
 */
@OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
private class PlayerLayerView : UIView(frame = CGRectZero.readValue()) {
  val playerLayer: AVPlayerLayer get() = layer as AVPlayerLayer

  companion object : UIViewMeta() {
    override fun layerClass(): ObjCClass = AVPlayerLayer
  }
}

private fun VideoContentScale.videoGravity(): AVLayerVideoGravity =
  when (this) {
    VideoContentScale.Fit -> AVLayerVideoGravityResizeAspect
    VideoContentScale.Crop -> AVLayerVideoGravityResizeAspectFill
    VideoContentScale.Stretch -> AVLayerVideoGravityResize
  }

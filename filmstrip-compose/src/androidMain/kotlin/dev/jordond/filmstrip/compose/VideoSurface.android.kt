package dev.jordond.filmstrip.compose

import android.graphics.Bitmap
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.playback.attachPreviewSurface
import dev.jordond.filmstrip.playback.observePreviewStill
import dev.jordond.filmstrip.player.VideoPlayer
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.Size as ComposeSize

/**
 * Android renders into a `SurfaceView`.
 *
 * The view is handed to the engine behind [VideoPlayer.nativePlayer], which points its player at it
 * and reports back when it has pixels, which is what closes the shutter on [VideoSurfaceState]. A
 * player with no media3 backend under it has no platform player to attach, so this lays out an
 * empty box of the size it was given.
 *
 * A `SurfaceView` carries HDR and protected content but ignores the UI layer's clipping, alpha and
 * transforms, so the video draws under anything Compose puts over it rather than blending with it.
 * The still this draws over the surface during a composition swap rides on that, and only holds
 * while the view stays where it is: a `SurfaceView` that changes bounds re-punches its hole, and for
 * a frame or two the surface composites over whatever Compose had drawn on top. So the view is sized
 * from the frame on screen rather than the one being loaded, and moves when the still is dropped.
 */
@Composable
public actual fun VideoSurface(
  player: VideoPlayer,
  modifier: Modifier,
  contentScale: VideoContentScale,
) {
  if (player.nativePlayer !is Player) {
    Box(modifier)
    return
  }

  val context = LocalContext.current
  val view = remember(context) { SurfaceView(context) }
  val surface = rememberVideoSurfaceState(player)
  val still = previewStill(player)

  DisposableEffect(player, view) {
    val handle = player.attachPreviewSurface(view)
    onDispose { handle.cancel() }
  }

  Box(modifier.clipToBounds(), contentAlignment = Alignment.Center) {
    AndroidView(
      factory = { view },
      modifier = Modifier.videoScale(surface.outputSize, contentScale),
    )
    still?.let { frame -> FrozenFrame(frame, contentScale) }
  }
}

/**
 * The frame the engine is holding over the surface, or null while the live surface is what to show.
 */
@Composable
private fun previewStill(player: VideoPlayer): Bitmap? {
  var still by remember(player) { mutableStateOf<Bitmap?>(null) }

  DisposableEffect(player) {
    val handle =
      player.observePreviewStill { frame ->
        // The shutter has already moved on to frame by the time this runs, so the one this replaces
        // is ours alone to recycle. What the shutter is still showing when this stops listening is
        // not ours to touch: it can hand that same bitmap to whatever attaches next.
        still?.takeIf { it !== frame }?.recycle()
        still = frame
      }
    onDispose { handle.cancel() }
  }

  return still
}

/**
 * Covers the surface with [frame] while the graph behind it is rebuilt.
 *
 * The still is laid out from its own dimensions rather than from the composition's, since it was
 * drawn by the composition being replaced and the new output size is already in force around it.
 * The backdrop hides whatever the live surface is doing in the space the still does not fill.
 */
@Composable
private fun BoxScope.FrozenFrame(
  frame: Bitmap,
  contentScale: VideoContentScale,
) {
  val image = remember(frame) { frame.asImageBitmap() }

  Box(
    modifier = Modifier.matchParentSize().background(Color.Black),
    contentAlignment = Alignment.Center,
  ) {
    Image(
      bitmap = image,
      contentDescription = null,
      contentScale = ContentScale.FillBounds,
      modifier = Modifier.videoScale(Size(frame.width, frame.height), contentScale),
    )
  }
}

/**
 * Sizes the surface to the rectangle [contentScale] asks for and centres it.
 *
 * The player stretches its frames over whatever surface it is given, so the fit is the view's size
 * and nothing else. A [VideoContentScale.Crop] rectangle overflows on one axis while the size
 * reported upwards stays inside the constraints, so the overflow falls outside the layout and is
 * clipped only as far as a `SurfaceView` can be.
 */
private fun Modifier.videoScale(
  output: Size,
  contentScale: VideoContentScale,
): Modifier =
  layout { measurable, constraints ->
    val target = surfaceSize(output, constraints, contentScale)
    val placeable = measurable.measure(target?.let { Constraints.fixed(it.width, it.height) } ?: constraints)
    val width = placeable.width.coerceAtMost(constraints.maxWidth)
    val height = placeable.height.coerceAtMost(constraints.maxHeight)

    layout(width, height) {
      placeable.place((width - placeable.width) / 2, (height - placeable.height) / 2)
    }
  }

/**
 * The size the surface takes under [constraints], or null to measure it with them unchanged.
 *
 * An unbounded axis has nothing to fit into, so a fit's rectangle comes off the bounded one alone,
 * and [videoRect] falls a crop back to that same rectangle rather than one sized to an infinite
 * axis. Both axes unbounded leaves the size to whatever the view asks for.
 */
private fun surfaceSize(
  output: Size,
  constraints: Constraints,
  contentScale: VideoContentScale,
): IntSize? {
  val aspect = output.aspect
  if (aspect <= 0f || contentScale == VideoContentScale.Stretch) return null
  if (!constraints.hasBoundedWidth && !constraints.hasBoundedHeight) return null

  val available =
    ComposeSize(
      if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else Float.POSITIVE_INFINITY,
      if (constraints.hasBoundedHeight) constraints.maxHeight.toFloat() else Float.POSITIVE_INFINITY,
    )
  val rect = videoRect(output, available, contentScale)
  return IntSize(rect.width.roundToInt(), rect.height.roundToInt())
}

package dev.jordond.filmstrip.compose

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.player.PreviewSurfaceType
import dev.jordond.filmstrip.player.ReadbackFrame
import dev.jordond.filmstrip.player.ReadbackResult
import dev.jordond.filmstrip.player.VideoPlayer
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import androidx.compose.ui.geometry.Size as ComposeSize

/**
 * The browser draws the composite itself, so there is no native view to attach.
 *
 * The surface is an ordinary Compose draw of the frame the pipeline already produced, pulled
 * through [VideoPlayer.readback], which is the same framebuffer the encoder takes. Everything the
 * other targets have to settle here, such as the surface class, the player layer and touch delivery
 * through a transparent cut-out, is native view interop that a Compose draw sidesteps.
 *
 * [surfaceType] is ignored. Both of its arms name Android view classes.
 */
@Composable
public actual fun VideoSurface(
  player: VideoPlayer,
  modifier: Modifier,
  contentScale: VideoContentScale,
  surfaceType: PreviewSurfaceType,
) {
  val info by player.previewInfo.collectAsState()
  var frame by remember(player) { mutableStateOf<ImageBitmap?>(null) }

  LaunchedEffect(player) {
    // collectLatest, so a readback still in flight is cancelled when the playhead moves past it.
    player.positionFlow(FRAME_TICK).collectLatest { position ->
      frame = player.frameAt(position)
    }
  }

  Canvas(modifier) {
    drawPreview(frame, info.outputSize, contentScale)
  }
}

private suspend fun VideoPlayer.frameAt(position: Duration): ImageBitmap? =
  suspendCancellableCoroutine { continuation ->
    val request =
      readback.requestFrame(position) { result ->
        continuation.resume(
          when (result) {
            is ReadbackResult.Success -> result.frame.toImageBitmap()
            is ReadbackResult.Failure -> null
          },
        )
      }
    continuation.invokeOnCancellation { request.cancel() }
  }

// The readback contract is tightly packed RGBA_8888 with no row padding, which is exactly what
// Skia's RGBA_8888 raster wants, so this is one copy and no reformatting.
private fun ReadbackFrame.toImageBitmap(): ImageBitmap? {
  if (size.width <= 0 || size.height <= 0) return null
  val info = ImageInfo(size.width, size.height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
  return Image.makeRaster(info, pixels, size.width * BYTES_PER_PIXEL).toComposeImageBitmap()
}

// Sizes the video rectangle to the composition's output aspect. The effect pipeline preserves that
// aspect and letterboxes, so a rectangle laid out from the source aspect turns mostly black once
// effects are on.
private fun DrawScope.drawPreview(
  frame: ImageBitmap?,
  outputSize: Size,
  contentScale: VideoContentScale,
) {
  if (frame == null || outputSize.width <= 0 || outputSize.height <= 0) return

  val destination = fit(outputSize, size, contentScale)
  drawImage(
    image = frame,
    dstOffset =
      IntOffset(
        ((size.width - destination.width) / 2f).roundToInt(),
        ((size.height - destination.height) / 2f).roundToInt(),
      ),
    dstSize = IntSize(destination.width.roundToInt(), destination.height.roundToInt()),
  )
}

private fun fit(
  outputSize: Size,
  available: ComposeSize,
  contentScale: VideoContentScale,
): ComposeSize {
  if (available.width <= 0f || available.height <= 0f) return ComposeSize.Zero

  val horizontal = available.width / outputSize.width
  val vertical = available.height / outputSize.height
  val factor =
    when (contentScale) {
      VideoContentScale.Fit -> minOf(horizontal, vertical)
      VideoContentScale.Crop -> maxOf(horizontal, vertical)
      VideoContentScale.Stretch -> return available
    }

  return ComposeSize(outputSize.width * factor, outputSize.height * factor)
}

// 30 Hz. The readback is pull-based and this is a display path, so a tick faster than the frame
// rate only queues work the next tick cancels.
private val FRAME_TICK = 33.milliseconds
private const val BYTES_PER_PIXEL = 4

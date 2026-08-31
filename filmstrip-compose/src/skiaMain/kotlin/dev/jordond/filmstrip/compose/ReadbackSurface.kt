package dev.jordond.filmstrip.compose

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.player.ReadbackResult
import dev.jordond.filmstrip.player.VideoPlayer
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import androidx.compose.ui.geometry.Size as ComposeSize

/**
 * Draws the frame the pipeline already produced, pulled through [VideoPlayer.readback].
 *
 * The frame is the same framebuffer the encoder takes, drawn into a Compose `Canvas`. Everything a
 * native surface has to settle, such as the surface class, the player layer and touch delivery
 * through a transparent cut-out, is interop that a Compose draw sidesteps.
 *
 * @param player The player to pull frames from.
 * @param modifier Applied to the canvas.
 * @param contentScale How the video fills the space it is given.
 */
@Composable
internal fun ReadbackSurface(
  player: VideoPlayer,
  modifier: Modifier,
  contentScale: VideoContentScale,
) {
  val info by player.previewInfo.collectAsState()
  var frame by remember(player) { mutableStateOf<ImageBitmap?>(null) }

  LaunchedEffect(player) {
    // collectLatest, so a readback still in flight is cancelled when the playhead moves past it.
    player.positionFlow(FRAME_TICK).collectLatest { position ->
      val next = player.frameAt(position)
      // Each frame is its own Skia bitmap rather than a shared one, so the tick it is replaced on
      // is also the tick nothing else can still be drawing from it.
      frame?.closeSkiaBacking()
      frame = next
    }
  }

  DisposableEffect(player) {
    onDispose { frame?.closeSkiaBacking() }
  }

  Canvas(modifier) {
    drawPreview(frame, info.outputSize, contentScale)
  }
}

private fun ImageBitmap.closeSkiaBacking() {
  asSkiaBitmap().close()
}

private suspend fun VideoPlayer.frameAt(position: Duration): ImageBitmap? =
  suspendCancellableCoroutine { continuation ->
    val request =
      readback.requestFrame(position) { result ->
        val bitmap =
          when (result) {
            is ReadbackResult.Success -> result.frame.toImageBitmap()
            is ReadbackResult.Failure -> null
          }
        // A frame resumed into a continuation the tick has already moved past reaches nobody, and
        // nothing else is left holding it.
        continuation.resume(bitmap) { _, dropped, _ -> dropped?.closeSkiaBacking() }
      }
    continuation.invokeOnCancellation { request.cancel() }
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

  val destination = videoRect(outputSize, size, contentScale)
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

// 30 Hz. The readback is pull-based and this is a display path, so a tick faster than the frame
// rate only queues work the next tick cancels.
private val FRAME_TICK = 33.milliseconds

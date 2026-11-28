package dev.jordond.filmstrip.compose.overlay

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayAnimation
import dev.jordond.filmstrip.geometry.Size
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Bakes composables into [ImageOverlay]s for an export, for content that is on no stage.
 *
 * An element a stage is showing is baked by the stage's [ComposedOverlayState]. This is the form for the rest, like a
 * template rendered for a batch of exports. A capture lays its content out on an [OverlayCanvas], draws it scaled
 * and turned the way an [OverlayTransform] says, and hands back an overlay whose picture is exactly the pixels of the
 * output frame the element covers. The drawing happens inside an [OverlayCaptureHost], which the app mounts once, so
 * the capturer needs one in composition while it runs. Captures run one at a time.
 */
@Stable
public class OverlayCapturer {
  private val turn = Mutex()

  internal var request: CaptureRequest? by mutableStateOf(null)
    private set

  internal var hosts: Int by mutableStateOf(0)

  /**
   * Captures [content] as an overlay on [output] under [transform].
   *
   * [content] is exactly what is baked, so pass the composable that shows the element and never the text field it is
   * edited with, or the cursor and selection are captured too. It is laid out on [canvas], two frames pass, then
   * [ready] runs, then two more frames pass and the content is drawn once more, and that drawing is what gets baked.
   * Content that loads a font or an image asynchronously holds [ready] until it has. The picture is encoded off the
   * main thread.
   *
   * May be called from any coroutine. Cancelling the caller cancels the capture.
   *
   * @param transform Where the element sits, how big and how far turned.
   * @param canvas The canvas [content] is laid out on.
   * @param output The output frame the overlay is drawn on, in pixels. This is `PreviewInfo.outputSize` or the export
   * plan's output size.
   * @param ready Suspends until [content] has everything it draws.
   * @param opacity Alpha applied to the overlay, in `0f..1f`.
   * @param visibleDuring When the overlay is visible, or null for the whole composition.
   * @param animation How the overlay is drawn at each frame of its run, or null to hold it still.
   * @param content The element to bake.
   * @return The overlay, or null when none of the element falls on the output frame.
   * @throws IllegalStateException When no [OverlayCaptureHost] is in composition for this capturer, the host leaves
   * composition before the capture finishes, or the host is not drawn within 10 seconds. Whatever [ready] throws is
   * thrown from here too.
   */
  public suspend fun capture(
    transform: OverlayTransform,
    canvas: OverlayCanvas,
    output: Size,
    ready: suspend () -> Unit = {},
    opacity: Float = 1f,
    visibleDuring: TimeRange? = null,
    animation: OverlayAnimation? = null,
    content: @Composable () -> Unit,
  ): ImageOverlay? {
    val captured = captureRaster(transform, canvas, output, ready, content) ?: return null
    return captured.toImageOverlay(output, opacity, visibleDuring, animation)
  }

  /**
   * Draws [content] the way [capture] does and hands back the raster with the bounds it was drawn for, before any
   * encode.
   */
  internal suspend fun captureRaster(
    transform: OverlayTransform,
    canvas: OverlayCanvas,
    output: Size,
    ready: suspend () -> Unit,
    content: @Composable () -> Unit,
  ): Captured? {
    check(hosts > 0) { NO_HOST }
    return turn.withLock {
      // The host may have left while this capture waited its turn.
      check(hosts > 0) { NO_HOST }
      val pending = CaptureRequest(transform, canvas, output, ready, content)
      request = pending
      try {
        pending.result.awaitWhileHosted({ hosts }, HOST_LEFT)
      } finally {
        request = null
      }
    }
  }
}

/**
 * Remembers an [OverlayCapturer] for the life of the calling composition.
 *
 * Pass the same capturer to the [OverlayCaptureHost] that draws for it and to the code that captures.
 */
@Composable
public fun rememberOverlayCapturer(): OverlayCapturer = remember { OverlayCapturer() }

/**
 * One capture waiting on the host, completed with the raster or with null when the element is off the frame.
 */
internal class CaptureRequest(
  val transform: OverlayTransform,
  val canvas: OverlayCanvas,
  val output: Size,
  val ready: suspend () -> Unit,
  val content: @Composable () -> Unit,
) {
  val result: CompletableDeferred<Captured?> = CompletableDeferred()
}

internal const val NO_HOST =
  "An OverlayCapturer needs an OverlayCaptureHost in composition to draw in, and none is mounted for this one."

internal const val HOST_LEFT = "The OverlayCaptureHost left composition before the capture finished."

internal const val NOT_DRAWN =
  "The OverlayCaptureHost was not drawn within 10 seconds. A host off screen or sized to nothing is never drawn."

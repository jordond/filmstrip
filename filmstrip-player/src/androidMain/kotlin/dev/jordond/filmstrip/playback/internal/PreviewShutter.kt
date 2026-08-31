package dev.jordond.filmstrip.playback.internal

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.core.graphics.createBitmap
import dev.jordond.filmstrip.Cancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Holds the frame a preview surface last drew while the graph behind it is rebuilt.
 *
 * A swap that changes the output geometry moves the surface to its new shape as soon as the plan is
 * applied, and media3 then spends a few hundred milliseconds building the graph that fills it. The
 * still captured as the swap starts covers that gap, so a host shows the old picture and then the
 * new one rather than a half drawn frame between them.
 *
 * A capture that fails, and one taken with no surface attached, leave the still null and the host
 * showing the live surface, which is what it would have shown anyway.
 *
 * Every call and every listener are confined to [scope]'s dispatcher.
 *
 * @param scope The engine's dispatcher.
 * @param timeout How long a still is held before it is dropped whatever the graph is doing, so a
 *   swap that never renders cannot leave the preview frozen.
 * @param capture Reads the pixels a surface is showing.
 */
internal class PreviewShutter(
  private val scope: CoroutineScope,
  private val timeout: Duration = FALLBACK_REVEAL,
  private val capture: SurfaceCapture = PixelCopyCapture,
) {
  private var listeners: List<(Bitmap?) -> Unit> = emptyList()
  private var fallback: Job? = null

  // Bumped by everything that ends a hold, so a capture that arrives once its own hold is over,
  // whether the swap was revealed or a newer swap took the surface, can tell that neither the
  // still nor the disturbance it carries is wanted anymore.
  private var pass = 0

  /**
   * The frame to hold over the surface, or null while the live surface is what should be shown.
   */
  var still: Bitmap? = null
    private set

  /**
   * Freezes what [view] is showing until [reveal], or until [timeout] elapses.
   *
   * A null [view] means nothing is on screen to freeze, so the hold is skipped rather than left to
   * time out.
   *
   * @param onCaptured Runs once the surface has been read, whatever came back, and only while
   * this hold is still the standing one. Anything that disturbs the surface belongs here rather
   * than beside the call, since a capture is answered a few milliseconds later and would
   * otherwise read what the disturbance left behind. A hold that something else ended in the
   * meantime skips it, since the disturbance it carries was measured against a surface the
   * shutter no longer speaks for.
   */
  fun close(
    view: SurfaceView?,
    onCaptured: () -> Unit = {},
  ) {
    reveal()
    if (view == null) {
      // Nothing was asked of the platform, so this hold is still the standing one and nothing can
      // have superseded it between the call and here.
      onCaptured()
      return
    }

    val taken = pass
    capture.capture(view) { frame ->
      scope.launch {
        if (taken != pass) return@launch
        frame?.let(::publish)
        onCaptured()
      }
    }
    fallback =
      scope.launch {
        delay(timeout)
        fallback = null
        reveal()
      }
  }

  /**
   * Drops the still, whether one was ever captured.
   */
  fun reveal() {
    pass++
    fallback?.cancel()
    fallback = null
    publish(null)
  }

  /**
   * Reports the still to [listener] until the handle is cancelled.
   *
   * [listener] is called at once with whatever is standing, so a host attaching mid-swap covers the
   * surface rather than waiting for the next one.
   *
   * @return a handle that stops the reports.
   */
  fun addListener(listener: (Bitmap?) -> Unit): Cancellable {
    listeners = listeners + listener
    listener(still)
    return Cancellable { listeners = listeners - listener }
  }

  /**
   * Drops the still and every listener. Idempotent.
   */
  fun dispose() {
    reveal()
    listeners = emptyList()
  }

  private fun publish(frame: Bitmap?) {
    if (still === frame) return
    still = frame
    listeners.forEach { it(frame) }
  }

  private companion object {
    val FALLBACK_REVEAL = 1.seconds
  }
}

/**
 * Reads the pixels a surface is showing.
 *
 * The frame is handed back on whatever thread the platform answers on, so a caller that needs it
 * somewhere else moves it there itself.
 */
internal fun interface SurfaceCapture {
  /**
   * Copies [view]'s current contents, calling [onFrame] with the copy or with null where there was
   * nothing to read.
   */
  fun capture(
    view: SurfaceView,
    onFrame: (Bitmap?) -> Unit,
  )
}

/**
 * Copies a surface through `PixelCopy`, which reads the buffer the compositor is showing.
 *
 * A `SurfaceView` draws nothing into the view hierarchy, so asking it to draw itself onto a canvas
 * produces the hole it punches rather than the video in it.
 */
private object PixelCopyCapture : SurfaceCapture {
  private val handler = Handler(Looper.getMainLooper())

  override fun capture(
    view: SurfaceView,
    onFrame: (Bitmap?) -> Unit,
  ) {
    val width = view.width
    val height = view.height
    if (width <= 0 || height <= 0 || !view.holder.surface.isValid) {
      onFrame(null)
      return
    }

    val frame = createBitmap(width, height)
    try {
      PixelCopy.request(
        view,
        frame,
        { result -> onFrame(frame.takeIf { result == PixelCopy.SUCCESS }) },
        handler,
      )
    } catch (_: IllegalArgumentException) {
      // The surface can go away between the validity check and the request, which the request
      // itself reports by throwing rather than by failing its listener.
      onFrame(null)
    }
  }
}

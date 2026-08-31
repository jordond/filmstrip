package dev.jordond.filmstrip.playback

import android.app.Activity
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A window holding one `SurfaceView`, which is the only way a test can point the player at a real
 * display.
 *
 * A `SurfaceView` produces no surface until it is attached to a window and laid out, so a test
 * waits on [awaitSurface] before handing the view to an engine.
 */
class SurfaceHostActivity : Activity() {
  lateinit var surfaceView: SurfaceView
    private set

  private val surfaceReady = CountDownLatch(1)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setShowWhenLocked(true)
    setTurnScreenOn(true)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

    surfaceView = SurfaceView(this)
    surfaceView.holder.addCallback(
      object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
          surfaceReady.countDown()
        }

        override fun surfaceChanged(
          holder: SurfaceHolder,
          format: Int,
          width: Int,
          height: Int,
        ) = Unit

        override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
      },
    )
    setContentView(FrameLayout(this).apply { addView(surfaceView) })
  }

  /**
   * Resizes the view the way a layout whose aspect changed would.
   *
   * A preview surface is sized to the composition's output frame, so an edit that turns the frame
   * moves the view under a player that is already drawing into it.
   */
  fun resizeSurface(
    width: Int,
    height: Int,
  ) {
    runOnUiThread {
      surfaceView.layoutParams = FrameLayout.LayoutParams(width, height)
      surfaceView.requestLayout()
    }
  }

  /**
   * Pins the surface's buffer to a shape of its own, the way the engine does once it knows the
   * frame it renders.
   *
   * Repinning it is the one thing left that reaches a holder callback after the engine has fixed
   * the buffer, since a view that moves over a fixed buffer changes nothing the holder reports.
   */
  fun resizeBuffer(
    width: Int,
    height: Int,
  ) {
    runOnUiThread { surfaceView.holder.setFixedSize(width, height) }
  }

  /**
   * Blocks until the view has a surface to draw into.
   *
   * @return whether one arrived before the wait ran out.
   */
  fun awaitSurface(): Boolean = surfaceReady.await(SURFACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)

  private companion object {
    const val SURFACE_TIMEOUT_SECONDS = 10L
  }
}

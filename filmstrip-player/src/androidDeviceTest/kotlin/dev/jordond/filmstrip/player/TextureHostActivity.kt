package dev.jordond.filmstrip.player

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.TextureView
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * A window holding one `TextureView`, the texture counterpart of [SurfaceHostActivity].
 *
 * A `TextureView` has no texture until it is attached to a hardware accelerated window and laid out. The engine takes
 * the view's listener when it attaches, so a test polls [TextureView.isAvailable] instead of listening for it.
 */
class TextureHostActivity : Activity() {
  lateinit var textureView: TextureView
    private set

  private lateinit var root: FrameLayout

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
      setShowWhenLocked(true)
      setTurnScreenOn(true)
    } else {
      @Suppress("DEPRECATION")
      window.addFlags(
        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
      )
    }
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

    textureView = TextureView(this)
    root = FrameLayout(this).apply { addView(textureView) }
    setContentView(root)
  }

  /**
   * Resizes the view the way a layout whose aspect changed would.
   */
  fun resizeTexture(
    width: Int,
    height: Int,
  ) {
    runOnUiThread {
      textureView.layoutParams = FrameLayout.LayoutParams(width, height)
      textureView.requestLayout()
    }
  }

  /**
   * Takes the view out of the window, which destroys its texture, the way a host that leaves the screen does.
   */
  fun removeTexture() {
    runOnUiThread { root.removeView(textureView) }
  }

  /**
   * Puts the view back in the window, where it is given a new texture.
   */
  fun restoreTexture() {
    runOnUiThread { root.addView(textureView) }
  }
}

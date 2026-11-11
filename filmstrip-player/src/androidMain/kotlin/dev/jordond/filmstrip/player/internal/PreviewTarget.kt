package dev.jordond.filmstrip.player.internal

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import androidx.core.graphics.createBitmap
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.transformer.CompositionPlayer
import dev.jordond.filmstrip.geometry.Size
import androidx.media3.common.util.Size as Media3Size

/**
 * The view a preview draws into, as the surface manager, the player host and the shutter see it.
 *
 * Each kind of view reaches the player, its buffer and its pixels a different way, and this is the one place that
 * knows which.
 */
@ExperimentalApi
internal interface PreviewTarget {
  /**
   * The view itself, which is what an attachment is known by.
   */
  val view: View

  /**
   * Whether the view can show SDR only, so a preview drawing into it tone maps a grade it would have kept.
   */
  val showsSdrOnly: Boolean

  /**
   * Points [player] at this view. Player thread only.
   */
  fun applyTo(player: CompositionPlayer)

  /**
   * Takes [player] off this view. Player thread only.
   */
  fun clearFrom(player: CompositionPlayer)

  /**
   * Fixes the buffer the view shows to [size], whatever size the view is laid out at.
   */
  fun fixBuffer(size: Size)

  /**
   * Starts following the view's surface, and calls [onResized] each time it changes shape, until [stopListening].
   */
  fun listen(onResized: () -> Unit)

  /**
   * Stops following the view's surface, and lets go of anything made to draw into it.
   */
  fun stopListening()

  /**
   * Lets go of whatever is held for the view as the player goes away. Player thread only.
   *
   * @param player The player standing as it goes, or null when there is none.
   */
  fun release(player: CompositionPlayer?)

  /**
   * Copies what the view is showing, calling [onFrame] with the copy or with null where there was nothing to read.
   *
   * The frame is handed back on whatever thread the platform answers on.
   */
  fun capture(onFrame: (Bitmap?) -> Unit)
}

/**
 * A [SurfaceView] as a [PreviewTarget]. media3 takes the view itself and reads its size off the holder.
 */
@ExperimentalApi
internal class SurfaceViewTarget(
  override val view: SurfaceView,
) : PreviewTarget {
  override val showsSdrOnly: Boolean get() = false

  private var resizes: SurfaceHolder.Callback? = null

  override fun applyTo(player: CompositionPlayer) {
    player.setVideoSurfaceView(view)
  }

  override fun clearFrom(player: CompositionPlayer) {
    player.clearVideoSurfaceView(view)
  }

  override fun fixBuffer(size: Size) {
    view.holder.setFixedSize(size.width, size.height)
  }

  override fun listen(onResized: () -> Unit) {
    stopListening()
    val callback =
      object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder): Unit = Unit

        override fun surfaceChanged(
          holder: SurfaceHolder,
          format: Int,
          width: Int,
          height: Int,
        ) {
          onResized()
        }

        override fun surfaceDestroyed(holder: SurfaceHolder): Unit = Unit
      }
    resizes = callback
    view.holder.addCallback(callback)
  }

  override fun stopListening() {
    resizes?.let(view.holder::removeCallback)
    resizes = null
  }

  override fun release(player: CompositionPlayer?) {
    stopListening()
  }

  // Through PixelCopy, which reads the buffer the compositor is showing. A SurfaceView draws nothing into the view
  // hierarchy, so asking it to draw itself onto a canvas produces the hole it punches rather than the video in it.
  override fun capture(onFrame: (Bitmap?) -> Unit) {
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
        MAIN_HANDLER,
      )
    } catch (_: IllegalArgumentException) {
      // The surface can go away between the validity check and the request, which the request
      // itself reports by throwing rather than by failing its listener.
      onFrame(null)
    }
  }
}

/**
 * A [TextureView] as a [PreviewTarget].
 *
 * media3's composition player takes a `SurfaceView` or a `SurfaceHolder` and refuses a `TextureView`, which is its
 * limit and not the view's. So this owns a [Surface] over the view's `SurfaceTexture` and hands the player that
 * with a size. The surface is made when the texture arrives, handed to the player standing then, and taken off the
 * player before the texture is released.
 *
 * The size the player is told is the buffer [fixBuffer] set, or the view's own before one is fixed. A `TextureView`
 * sets its texture's buffer back to its own size each time it is laid out, so a fixed buffer is fixed again then.
 *
 * @param view The view drawn into.
 * @param handler Posts onto the player's thread.
 * @param main Posts onto the main thread, where a `TextureView` reports its texture and gives up its pixels.
 * @param newSurface Makes the surface over a texture.
 */
@ExperimentalApi
internal class TextureViewTarget(
  override val view: TextureView,
  private val handler: Handler,
  private val main: Handler = MAIN_HANDLER,
  private val newSurface: (SurfaceTexture) -> Surface = ::Surface,
) : PreviewTarget {
  // A TextureView is drawn by the app's own layer, which is SDR. That is the view's limit.
  override val showsSdrOnly: Boolean get() = true

  // The player this is pointed at, the surface over the view's texture, the size the player is told and whether it
  // holds the surface now. Player thread only.
  private var standing: CompositionPlayer? = null
  private var surface: Surface? = null
  private var outputSize: Size? = null
  private var handed = false

  @Volatile
  private var buffer: Size? = null

  @Volatile
  private var onResized: (() -> Unit)? = null

  @Volatile
  private var following = false

  private val textures =
    object : TextureView.SurfaceTextureListener {
      override fun onSurfaceTextureAvailable(
        texture: SurfaceTexture,
        width: Int,
        height: Int,
      ) {
        take(texture, width, height)
        // A texture that arrives under a player is a surface that changed shape, and is drawn into the same way.
        onResized?.invoke()
      }

      override fun onSurfaceTextureSizeChanged(
        texture: SurfaceTexture,
        width: Int,
        height: Int,
      ) {
        val fixed = buffer
        if (fixed != null) {
          texture.setDefaultBufferSize(fixed.width, fixed.height)
        } else {
          handler.post { outputSize = Size(width, height) }
        }
        onResized?.invoke()
      }

      // Answers false so the texture outlives this call and is released once the player has let go of the surface
      // over it. A player thread that has gone leaves the release to the view.
      override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        val posted =
          handler.post {
            drop()
            texture.release()
          }
        return !posted
      }

      override fun onSurfaceTextureUpdated(texture: SurfaceTexture): Unit = Unit
    }

  override fun applyTo(player: CompositionPlayer) {
    standing = player
    hand()
  }

  override fun clearFrom(player: CompositionPlayer) {
    val held = surface
    if (handed && held != null) player.clearVideoSurface(held)
    handed = false
    standing = null
  }

  override fun fixBuffer(size: Size) {
    buffer = size
    view.surfaceTexture?.setDefaultBufferSize(size.width, size.height)
    handler.post {
      outputSize = size
      hand()
    }
  }

  override fun listen(onResized: () -> Unit) {
    this.onResized = onResized
    following = true
    main.post {
      if (!following) return@post
      view.surfaceTextureListener = textures
      view.surfaceTexture?.let { take(it, view.width, view.height) }
    }
  }

  override fun stopListening() {
    unfollow()
    handler.post { drop() }
  }

  override fun release(player: CompositionPlayer?) {
    unfollow()
    player?.let(::clearFrom)
    drop()
  }

  // Off the view's own layer. PixelCopy has no way into a TextureView.
  override fun capture(onFrame: (Bitmap?) -> Unit) {
    main.post { onFrame(if (view.isAvailable) view.bitmap else null) }
  }

  private fun unfollow() {
    following = false
    onResized = null
    main.post { if (view.surfaceTextureListener === textures) view.surfaceTextureListener = null }
  }

  // Main thread. The surface is made on the player's thread, where everything that reads it runs.
  private fun take(
    texture: SurfaceTexture,
    width: Int,
    height: Int,
  ) {
    val fixed = buffer
    if (fixed != null) texture.setDefaultBufferSize(fixed.width, fixed.height)
    val size = fixed ?: Size(width, height)
    handler.post {
      if (!following) return@post
      drop()
      surface = newSurface(texture)
      outputSize = size
      hand()
    }
  }

  private fun hand() {
    val player = standing ?: return
    val to = surface ?: return
    val size = outputSize?.takeIf { it.width > 0 && it.height > 0 } ?: return
    player.setVideoSurface(to, Media3Size(size.width, size.height))
    handed = true
  }

  // Clearing blocks until media3 has stopped drawing, so the surface is free to release right after.
  private fun drop() {
    val held = surface ?: return
    if (handed) standing?.clearVideoSurface(held)
    handed = false
    held.release()
    surface = null
  }
}

private val MAIN_HANDLER by lazy { Handler(Looper.getMainLooper()) }

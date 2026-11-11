package dev.jordond.filmstrip.player.internal

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.os.Handler
import android.view.Surface
import android.view.TextureView
import dev.jordond.filmstrip.geometry.Size
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import kotlin.test.Test

/**
 * What a [TextureViewTarget] does with the view's texture and the surface it owns over it.
 *
 * No player stands behind the target here, since media3's player cannot be loaded on a host JVM. What reaches the
 * player is the device suite's to check.
 */
class TextureViewTargetTest {
  private val texture = mockk<SurfaceTexture>(relaxed = true)
  private val surface = mockk<Surface>(relaxed = true)
  private val listener = slot<TextureView.SurfaceTextureListener>()
  private var available: SurfaceTexture? = texture
  private val view =
    mockk<TextureView>(relaxed = true).also {
      every { it.surfaceTexture } answers { available }
      every { it.isAvailable } answers { available != null }
      every { it.width } returns VIEW.width
      every { it.height } returns VIEW.height
      every { it.surfaceTextureListener = capture(listener) } returns Unit
      every { it.surfaceTextureListener } answers { if (listener.isCaptured) listener.captured else null }
    }
  private var made = 0
  private val target =
    TextureViewTarget(view, inline(), inline()) {
      made++
      surface
    }

  @Test
  fun `a texture already there is taken up with a surface of its own`() {
    target.listen {}

    made shouldBe 1
    verify(exactly = 0) { texture.setDefaultBufferSize(any(), any()) }
  }

  @Test
  fun `a texture that arrives later is taken up then and reported as a resize`() {
    available = null
    var resizes = 0
    target.listen { resizes++ }
    made shouldBe 0

    available = texture
    listener.captured.onSurfaceTextureAvailable(texture, VIEW.width, VIEW.height)

    made shouldBe 1
    resizes shouldBe 1
  }

  @Test
  fun `the buffer is fixed to the rendered frame and not to the view`() {
    target.listen {}

    target.fixBuffer(FRAME)

    verify(exactly = 1) { texture.setDefaultBufferSize(FRAME.width, FRAME.height) }
  }

  @Test
  fun `a view laid out again has its fixed buffer fixed again`() {
    var resizes = 0
    target.listen { resizes++ }
    target.fixBuffer(FRAME)

    listener.captured.onSurfaceTextureSizeChanged(texture, TALL.width, TALL.height)

    verify(exactly = 2) { texture.setDefaultBufferSize(FRAME.width, FRAME.height) }
    resizes shouldBe 1
  }

  @Test
  fun `a buffer fixed before the texture exists is fixed as it arrives`() {
    available = null
    target.listen {}
    target.fixBuffer(FRAME)

    available = texture
    listener.captured.onSurfaceTextureAvailable(texture, VIEW.width, VIEW.height)

    verify(exactly = 1) { texture.setDefaultBufferSize(FRAME.width, FRAME.height) }
  }

  @Test
  fun `a destroyed texture is released after the surface over it and not by the view`() {
    target.listen {}

    val viewReleases = listener.captured.onSurfaceTextureDestroyed(texture)

    viewReleases.shouldBeFalse()
    verifyOrder {
      surface.release()
      texture.release()
    }
  }

  @Test
  fun `a texture destroyed after the player's thread has gone is left to the view`() {
    val gone = mockk<Handler>().also { every { it.post(any()) } returns false }
    val orphan = TextureViewTarget(view, gone, inline()) { surface }
    orphan.listen {}

    listener.captured.onSurfaceTextureDestroyed(texture).shouldBeTrue()
    verify(exactly = 0) { texture.release() }
  }

  @Test
  fun `letting go releases the surface and gives the view its listener back`() {
    target.listen {}

    target.stopListening()

    verify(exactly = 1) { surface.release() }
    verify { view.surfaceTextureListener = null }
    verify(exactly = 0) { texture.release() }
  }

  @Test
  fun `a texture that arrives after letting go is not taken up`() {
    available = null
    target.listen {}
    val textures = listener.captured
    target.stopListening()

    textures.onSurfaceTextureAvailable(texture, VIEW.width, VIEW.height)

    made shouldBe 0
  }

  @Test
  fun `a capture hands back the view's own bitmap and nothing without a texture`() {
    val bitmap = mockk<Bitmap>()
    every { view.bitmap } returns bitmap
    var captured: Bitmap? = null
    target.capture { captured = it }
    captured shouldBeSameInstanceAs bitmap

    available = null
    target.capture { captured = it }
    captured shouldBe null
  }

  private fun inline(): Handler =
    mockk<Handler>().also {
      every { it.post(any()) } answers {
        firstArg<Runnable>().run()
        true
      }
    }

  private companion object {
    val VIEW = Size(960, 540)
    val TALL = Size(540, 960)
    val FRAME = Size(1920, 1080)
  }
}

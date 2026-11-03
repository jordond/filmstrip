package dev.jordond.filmstrip.compose

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.player.ReadbackFrame
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import java.nio.Buffer
import java.nio.ByteBuffer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Duration

class ReadbackFrameAndroidTest {
  private val bitmap = mockk<Bitmap>(relaxed = true)
  private val copied = slot<Buffer>()

  @BeforeTest
  fun setUp() {
    mockkStatic(Bitmap::class)
    every { Bitmap.createBitmap(any<Int>(), any<Int>(), any<Bitmap.Config>()) } returns bitmap
    every { bitmap.copyPixelsFromBuffer(capture(copied)) } returns Unit
  }

  @AfterTest
  fun tearDown() {
    unmockkAll()
  }

  @Test
  fun `the frame's bytes are copied into a bitmap of its size`() {
    val pixels = ByteArray(3 * 2 * 4) { it.toByte() }

    val image = frame(Size(3, 2), pixels).toImageBitmap().shouldNotBeNull()

    image.asAndroidBitmap() shouldBeSameInstanceAs bitmap
    verify { Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888) }
    val buffer = copied.captured as ByteBuffer
    ByteArray(buffer.remaining()).also { buffer.get(it) } shouldBe pixels
  }

  @Test
  fun `the bitmap is marked opaque so its premultiplied bytes read as the frame's`() {
    frame(Size(2, 2), ByteArray(2 * 2 * 4)).toImageBitmap()

    verify { bitmap.setHasAlpha(false) }
  }

  @Test
  fun `a frame with no width or no height has nothing to convert`() {
    frame(Size(0, 4), ByteArray(0)).toImageBitmap().shouldBeNull()
    frame(Size(4, 0), ByteArray(0)).toImageBitmap().shouldBeNull()
    frame(Size(-1, 4), ByteArray(0)).toImageBitmap().shouldBeNull()

    verify(exactly = 0) { Bitmap.createBitmap(any<Int>(), any<Int>(), any<Bitmap.Config>()) }
  }

  private fun frame(
    size: Size,
    pixels: ByteArray,
  ): ReadbackFrame =
    ReadbackFrame(
      pixels = pixels,
      size = size,
      presentationTime = Duration.ZERO,
      colorSpace = ColorSpace.Bt709,
      renderScale = 1f,
    )
}

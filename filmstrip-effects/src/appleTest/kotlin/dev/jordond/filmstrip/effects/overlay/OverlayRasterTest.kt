package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.style.TextAlignment
import dev.jordond.filmstrip.style.TextStyle
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.CoreImage.kCIFormatRGBA8
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The CoreText rasteriser, including which way up it draws.
 *
 * A bitmap context is bottom-left with +Y up and so is Core Image, so nothing should be flipped on
 * the way through. That claim is only worth anything if a glyph whose ink sits at one end of the
 * box is measured, which is what the full stop is for.
 */
@OptIn(ExperimentalForeignApi::class)
class OverlayRasterTest {
  // Skipped where Core Image cannot build a context, which a headless simulator process is. The
  // question is about CoreText and CoreGraphics, not the renderer, and the host answers it.
  @Test
  fun `draws text the right way up`() {
    val context = renderingContext() ?: return
    val raster = assertNotNull(rasterizeText(".", TextStyle(fontSize = 0.5f), FRAME))
    val size = raster.pixelSize()

    val (top, bottom) = raster.inkByHalf(size, context)
    // A full stop sits on the baseline, so its ink belongs in the lower half. A flipped context
    // puts it in the upper one and nothing else about the image changes.
    assertTrue(bottom > top, "ink was $top above and $bottom below, so the raster is upside down")
  }

  /**
   * A context to read pixels through, or null when this process cannot build one.
   *
   * An Objective-C `init` that answers nil reaches Kotlin as a non-null reference that is not
   * there, and Kotlin/Native raises at the constructor, not at the first use.
   */
  private fun renderingContext(): CIContext? =
    try {
      CIContext()
    } catch (absent: NullPointerException) {
      null
    }

  @Test
  fun `sizes the block to the glyphs and not to the authored wrap width`() {
    val narrow = assertNotNull(rasterizeText("hi", TextStyle(maxWidth = 0.9f), FRAME))
    val wide = assertNotNull(rasterizeText("a much longer caption", TextStyle(maxWidth = 0.9f), FRAME))

    assertTrue(
      wide.pixelSize().width > narrow.pixelSize().width,
      "both blocks came back the same width, so the box is the authored one",
    )
    assertTrue(narrow.pixelSize().width < FRAME.width, "the short block filled the whole wrap width")
  }

  @Test
  fun `wraps at the authored width onto more than one line`() {
    val style = TextStyle(fontSize = 0.08f, maxWidth = 0.3f, alignment = TextAlignment.Center)
    val one = assertNotNull(rasterizeText("one", style, FRAME))
    val many = assertNotNull(rasterizeText("one two three four five six seven", style, FRAME))

    assertTrue(many.pixelSize().height > one.pixelSize().height, "the long string did not wrap")
  }

  @Test
  fun `scales the block with the frame it is drawn on`() {
    val style = TextStyle(fontSize = 0.1f)
    val small = assertNotNull(rasterizeText("caption", style, Size(640, 360)))
    val large = assertNotNull(rasterizeText("caption", style, Size(1280, 720)))

    val ratio = large.pixelSize().height.toFloat() / small.pixelSize().height
    assertTrue(ratio in 1.8f..2.2f, "doubling the frame changed the block by ${ratio}x")
  }

  @Test
  fun `pads a block that carries a background plate`() {
    val plain = assertNotNull(rasterizeText("caption", TextStyle(), FRAME))
    val plated = assertNotNull(rasterizeText("caption", TextStyle(backgroundColor = BLACK), FRAME))

    assertTrue(plated.pixelSize().width > plain.pixelSize().width, "the plate stops at the glyphs")
  }

  @Test
  fun `draws nothing for an empty string`() {
    assertNull(rasterizeText("", TextStyle(), FRAME))
  }

  @Test
  fun `reads no image from empty bytes`() {
    assertNull(ImageSource.ofBytes(ByteArray(0)).decode())
  }

  @Test
  fun `reports an image's own pixel size`() {
    val raster = assertNotNull(rasterizeText("x", TextStyle(), FRAME))
    raster.pixelSize().width shouldBe raster.extent.useContents { size.width.toInt() }
  }

  /**
   * Total alpha above and below the horizontal midline.
   */
  private fun CIImage.inkByHalf(
    size: Size,
    context: CIContext,
  ): Pair<Long, Long> =
    memScoped {
      val rowBytes = size.width * CHANNELS
      val pixels = allocArray<UByteVar>(rowBytes * size.height)
      val space = CGColorSpaceCreateDeviceRGB()
      context.render(
        image = this@inkByHalf,
        toBitmap = pixels,
        rowBytes = rowBytes.toLong(),
        bounds = extent,
        format = kCIFormatRGBA8,
        colorSpace = space,
      )
      CGColorSpaceRelease(space)

      var top = 0L
      var bottom = 0L
      for (row in 0 until size.height) {
        var line = 0L
        for (column in 0 until size.width) {
          line += pixels[row * rowBytes + column * CHANNELS + ALPHA].toLong()
        }
        // Row zero of the rendered bitmap is the top of the image.
        if (row < size.height / 2) top += line else bottom += line
      }
      top to bottom
    }

  private companion object {
    val FRAME = Size(1280, 720)
    const val CHANNELS = 4
    const val ALPHA = 3
    const val BLACK = 0xFF000000.toInt()
  }
}

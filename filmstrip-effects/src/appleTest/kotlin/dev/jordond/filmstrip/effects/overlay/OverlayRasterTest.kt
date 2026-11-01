package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.effects.assertRasterReads
import dev.jordond.filmstrip.effects.color.createCodeSpace
import dev.jordond.filmstrip.effects.overlay.OverlayFixtures.decodeHex
import dev.jordond.filmstrip.effects.renderingContext
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.authoredHdrNits
import dev.jordond.filmstrip.media.signalFromNits
import dev.jordond.filmstrip.style.TextStyle
import dev.jordond.filmstrip.test.greySignal
import dev.jordond.filmstrip.test.sampleOfCode
import dev.jordond.filmstrip.test.straightSampleTolerance
import dev.jordond.filmstrip.test.straightSignalTolerance
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceRef
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.kCGColorSpaceITUR_2100_HLG
import platform.CoreGraphics.kCGColorSpaceITUR_2100_PQ
import platform.CoreGraphics.kCGColorSpaceSRGB
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.CoreImage.kCIFormatRGBA8
import platform.CoreImage.kCIFormatRGBAf
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
  @Test
  fun `draws text the right way up`() {
    val context = renderingContext()
    val raster = drawnText(".", TextStyle(fontSize = 0.5f), FRAME)
    val size = raster.size

    val (top, bottom) = raster.codes.inkByHalf(size, context)
    // A full stop sits on the baseline, so its ink belongs in the lower half. A flipped context
    // puts it in the upper one and nothing else about the image changes.
    assertTrue(bottom > top, "ink was $top above and $bottom below, so the raster is upside down")
  }

  @Test
  fun `draws glyphs in the authored colour`() {
    val context = renderingContext()

    // Two saturated colours, since one alone lets a run that swaps a channel read as correct.
    for (authored in listOf(RED, BLUE)) {
      val raster = drawnText("H", TextStyle(fontSize = 0.5f, color = authored), FRAME)
      val drawn =
        raster.enteringFrame(transfer = null, ColorSpace.Bt709).opaqueInk(raster.size, context, inCodes = true)

      for (shift in listOf(RED_SHIFT, GREEN_SHIFT, BLUE_SHIFT)) {
        assertTrue(
          abs(((authored shr shift) and BYTE) - ((drawn shr shift) and BYTE)) <= TOLERANCE,
          "asked for ${authored.hex()} and drew ${drawn.hex()}",
        )
      }
    }
  }

  // Rendered back into the frame's own codes, a plate entering an SDR frame reads as authored. The
  // mid grey is where BT.709's curve and sRGB's part company, so the raster matched from device RGB
  // lands elsewhere and that gap is what the tolerance is taken from.
  @Test
  fun `a plate enters an sdr frame as its own codes`() {
    val context = renderingContext()
    val style = TextStyle(fontSize = 0.5f, color = GREY, backgroundColor = GREY)
    val raster = drawnText("H", style, FRAME)

    val sdr = raster.enteringFrame(transfer = null, ColorSpace.Bt709)
    val entered = sdr.opaqueInk(raster.size, context, inCodes = true)
    val managed =
      raster.codes.matchedFrom(CGColorSpaceCreateDeviceRGB()).opaqueInk(raster.size, context, inCodes = true)
    val gap = distance(managed, GREY)

    assertTrue(gap > 0, "the managed plate drew ${managed.hex()}, the same as its own codes")
    assertTrue(distance(entered, GREY) * 2 < gap, "asked for ${GREY.hex()} and drew ${entered.hex()}")
  }

  // Read back in the transfer's own space, a plate on an HDR frame carries the signal a fill of its colour does. The
  // read runs in a float working format, so the bound is the stored sample's alone.
  @Test
  fun `a plate enters an hdr frame at the shared fill signal`() {
    val context = renderingContext(floatFormat = true)
    val raster = drawnText("H", TextStyle(color = GREY, backgroundColor = GREY), FRAME)
    val code = greyCode(GREY)

    HdrTransfer.entries.forEach { transfer ->
      val expected = transfer.signalFromNits(authoredHdrNits(GREY))
      val read = raster.enteringFrame(transfer, ColorSpace.Bt2020).signalAtCentre(raster.size, context, transfer)
      val bound = straightSignalTolerance(OPAQUE, code, transfer)

      assertTrue(
        (0 until 3).all { abs(read[it] - expected[it]) <= bound },
        "the $transfer plate carried ${read.toList()} and the shared fill asks for ${expected.toList()} within $bound",
      )
      assertEquals(1f, read[ALPHA], alphaTolerance(), "the $transfer plate lost its alpha")
    }
  }

  // The colour is mapped before the alpha multiplies it, so a translucent texel keeps the straight signal of its
  // colour and carries its alpha alongside. The read runs in a float working format, so the bound is the stored
  // sample's alone.
  @Test
  fun `a translucent texel keeps its colour's signal on an hdr frame`() {
    val context = renderingContext(floatFormat = true)

    for (plate in TRANSLUCENT_PLATES) {
      val raster = drawnText("H", TextStyle(color = GREY, backgroundColor = plate), FRAME)
      val code = greyCode(plate)
      val authoredAlpha = plate ushr ALPHA_SHIFT

      HdrTransfer.entries.forEach { transfer ->
        val expected = transfer.signalFromNits(authoredHdrNits(plate))
        val corner = raster.enteringFrame(transfer, ColorSpace.Bt2020).signalAt(0, 0, raster.size, context, transfer)
        val alpha = corner[ALPHA]
        val bound = straightSignalTolerance(authoredAlpha, code, transfer)
        val plateName = "the $transfer ${plate.hex()} plate"

        assertEquals(authoredAlpha / FULL, alpha, alphaTolerance(), "$plateName lost its alpha")
        (0 until 3).forEach { channel ->
          val straight = corner[channel] / alpha
          assertEquals(expected[channel], straight, bound, "$plateName read ${corner.toList()}")
        }
      }
    }
  }

  // Premultiplied on the eight-bit grid and divided back out, a plate's straight sample lands far enough off that its
  // signal leaves the bound the HDR plate is read within, on both transfers and at both plates.
  @Test
  fun `an eight bit store falls outside the hdr signal bound`() {
    for (plate in TRANSLUCENT_PLATES) {
      val code = greyCode(plate)
      val alpha = plate ushr ALPHA_SHIFT
      val stored = (code * alpha / FULL).roundToInt()
      val eightBit = stored * FULL / alpha * sampleOfCode(1)

      HdrTransfer.entries.forEach { transfer ->
        val authored = greySignal(sampleOfCode(code).toFloat(), transfer)
        val read = greySignal(eightBit, transfer)
        val moved = (0 until 3).maxOf { abs(read[it] - authored[it]) }
        val bound = straightSignalTolerance(alpha, code, transfer)

        assertTrue(moved > bound, "the $transfer ${plate.hex()} plate moved $moved stored at eight bits, inside $bound")
      }
    }
  }

  // Premultiplied on the eight-bit grid, colour 0x80 at alpha 0x80 reads back straight 128.5 samples short and colour
  // 0xC8 at alpha 0x40 200.8 samples off, both outside what a sixteen-bit store rounds away.
  @Test
  fun `a translucent texel stores its authored straight colour`() {
    assertRasterReads()

    for (plate in TRANSLUCENT_PLATES) {
      val raster = drawnText("H", TextStyle(color = GREY, backgroundColor = plate), FRAME)
      val samples = assertNotNull(raster.stored)
      val alpha = plate ushr ALPHA_SHIFT

      val corner = (0 until CHANNELS).map { samples[it].toInt() and SAMPLE_MASK }
      val authored =
        listOf(RED_SHIFT, GREEN_SHIFT, BLUE_SHIFT, ALPHA_SHIFT).map { sampleOfCode((plate ushr it) and BYTE) }
      // The alpha is stored as it is, the way an opaque texel's colour is, so it never divides by itself.
      val tolerances =
        authored.indices.map { channel ->
          straightSampleTolerance(if (channel == ALPHA) OPAQUE else alpha)
        }
      assertTrue(
        corner.indices.all { abs(corner[it] - authored[it]) <= tolerances[it] },
        "the ${plate.hex()} plate's corner stored $corner and was authored as $authored, within $tolerances",
      )
    }
  }

  @Test
  fun `an hdr frame draws text the right way up`() {
    val context = renderingContext()
    val raster = drawnText(".", TextStyle(fontSize = 0.5f), FRAME)

    val (top, bottom) = raster.enteringFrame(HdrTransfer.Pq, ColorSpace.Bt2020).inkByHalf(raster.size, context)
    assertTrue(bottom > top, "ink was $top above and $bottom below, so the HDR raster is upside down")
  }

  @Test
  fun `draws nothing for an empty string`() {
    assertEquals(TextRaster.Empty, rasterizeText("", TextStyle(), FRAME))
  }

  @Test
  fun `reads no image from empty bytes`() {
    assertIs<PictureRead.Unreadable>(ImageSource.ofBytes(ByteArray(0)).read())
  }

  // Matched from the sRGB space the PNG names, a mid grey lands off its own codes in a BT.709
  // frame. Decoded bare and entering an SDR frame, it lands as authored.
  @Test
  fun `decodes an image as its own codes`() {
    val context = renderingContext()
    val pixels = GREY_PNG.read().orFail("the PNG")

    assertEquals(PNG_SIZE, pixels.codes.pixelSize(), "the codes image is not the PNG's size")

    val managed =
      pixels.codes
        .matchedFrom(CGColorSpaceCreateWithName(kCGColorSpaceSRGB))
        .opaqueInk(PNG_SIZE, context, inCodes = true)
    val entered = pixels.enteringFrame(transfer = null, ColorSpace.Bt709).opaqueInk(PNG_SIZE, context, inCodes = true)
    val gap = distance(managed, GREY)

    assertTrue(gap > 0, "the managed image drew ${managed.hex()}, the same as its own codes")
    assertTrue(distance(entered, GREY) * 2 < gap, "the PNG holds ${GREY.hex()} and drew ${entered.hex()}")
  }

  @Test
  fun `reports an image's own pixel size`() {
    val raster = drawnText("x", TextStyle(), FRAME)
    raster.size.width shouldBe raster.codes.extent.useContents { size.width.toInt() }
  }

  private fun drawnText(
    text: String,
    style: TextStyle,
    frame: Size,
  ): OverlayPixels =
    assertIs<TextRaster.Drawn<OverlayPixels>>(rasterizeText(text, style, frame), "the text drew nothing").pixels

  // An alpha stored at sixteen bits reads back within one sample, as a fraction of full.
  private fun alphaTolerance(): Float = straightSampleTolerance(OPAQUE) / sampleOfCode(OPAQUE)

  /**
   * Total alpha above and below the horizontal midline.
   */
  private fun CIImage.inkByHalf(
    size: Size,
    context: CIContext,
  ): Pair<Long, Long> =
    pixels(size, context) { bitmap, rowBytes ->
      var top = 0L
      var bottom = 0L
      for (row in 0 until size.height) {
        var line = 0L
        for (column in 0 until size.width) {
          line += bitmap[row * rowBytes + column * CHANNELS + ALPHA].toLong()
        }
        // Row zero of the rendered bitmap is the top of the image.
        if (row < size.height / 2) top += line else bottom += line
      }
      top to bottom
    }

  /**
   * The colour of the most opaque pixel, packed as `0xRRGGBB`.
   *
   * The bitmap is premultiplied, so a pixel at full alpha already carries the colour it was drawn
   * with and the multiply never has to be undone.
   */
  private fun CIImage.opaqueInk(
    size: Size,
    context: CIContext,
    inCodes: Boolean = false,
  ): Int =
    pixels(size, context, inCodes) { bitmap, rowBytes ->
      var opaque = -1
      var ink = 0
      for (row in 0 until size.height) {
        for (column in 0 until size.width) {
          val pixel = row * rowBytes + column * CHANNELS
          val alpha = bitmap[pixel + ALPHA].toInt()
          if (alpha <= opaque) continue
          opaque = alpha
          ink =
            (bitmap[pixel].toInt() shl RED_SHIFT) or
            (bitmap[pixel + 1].toInt() shl GREEN_SHIFT) or
            bitmap[pixel + 2].toInt()
        }
      }
      ink
    }

  /**
   * Renders the image to an eight-bit RGBA bitmap and hands [read] the buffer with its row stride.
   *
   * The bitmap is device RGB, or the codes an SDR BT.709 frame is written with when [inCodes] is set.
   */
  private fun <T> CIImage.pixels(
    size: Size,
    context: CIContext,
    inCodes: Boolean = false,
    read: (CPointer<UByteVar>, Int) -> T,
  ): T =
    memScoped {
      val rowBytes = size.width * CHANNELS
      val bitmap = allocArray<UByteVar>(rowBytes * size.height)
      val space = if (inCodes) ColorSpace.Bt709.createCodeSpace() else CGColorSpaceCreateDeviceRGB()
      context.render(
        image = this@pixels,
        toBitmap = bitmap,
        rowBytes = rowBytes.toLong(),
        bounds = extent,
        format = kCIFormatRGBA8,
        colorSpace = space,
      )
      CGColorSpaceRelease(space)
      read(bitmap, rowBytes)
    }

  /**
   * These codes read as colours in [space] and carried into the working space. Releases [space].
   */
  private fun CIImage.matchedFrom(space: CGColorSpaceRef?): CIImage {
    val matched = imageByColorMatchingColorSpaceToWorkingSpace(assertNotNull(space)) ?: this
    CGColorSpaceRelease(space)
    return matched
  }

  private fun CIImage.signalAtCentre(
    size: Size,
    context: CIContext,
    transfer: HdrTransfer,
  ): FloatArray = signalAt(size.width / 2, size.height / 2, size, context, transfer)

  /**
   * The premultiplied red, green, blue and alpha at ([column], [row]), rendered into [transfer]'s own space so the
   * colour reads as that transfer's signal. Row zero is the top of the image.
   */
  private fun CIImage.signalAt(
    column: Int,
    row: Int,
    size: Size,
    context: CIContext,
    transfer: HdrTransfer,
  ): FloatArray =
    memScoped {
      val rowFloats = size.width * CHANNELS
      val bitmap = allocArray<FloatVar>(rowFloats * size.height)
      val space =
        CGColorSpaceCreateWithName(
          when (transfer) {
            HdrTransfer.Pq -> kCGColorSpaceITUR_2100_PQ
            HdrTransfer.Hlg -> kCGColorSpaceITUR_2100_HLG
          },
        )
      context.render(
        image = this@signalAt,
        toBitmap = bitmap,
        rowBytes = (rowFloats * Float.SIZE_BYTES).toLong(),
        bounds = extent,
        format = kCIFormatRGBAf,
        colorSpace = space,
      )
      CGColorSpaceRelease(space)
      FloatArray(CHANNELS) { bitmap[row * rowFloats + column * CHANNELS + it] }
    }

  private fun Int.hex(): String = "0x${toUInt().toString(HEX)}"

  // The one code a grey colour holds in every channel, which a grey signal bound is read at.
  private fun greyCode(argb: Int): Int {
    val code = (argb shr RED_SHIFT) and BYTE
    require(code == (argb shr GREEN_SHIFT) and BYTE && code == argb and BYTE) { "${argb.hex()} is not grey" }
    return code
  }

  private fun distance(
    a: Int,
    b: Int,
  ): Int = listOf(RED_SHIFT, GREEN_SHIFT, BLUE_SHIFT).sumOf { abs(((a shr it) and BYTE) - ((b shr it) and BYTE)) }

  private companion object {
    val FRAME = Size(1280, 720)

    const val CHANNELS = 4
    const val ALPHA = 3
    const val RED = 0xFFFF0000.toInt()
    const val BLUE = 0xFF0000FF.toInt()
    const val GREY = 0xFF808080.toInt()
    const val ALPHA_SHIFT = 24
    const val OPAQUE = 0xFF
    const val FULL = 255f
    const val RED_SHIFT = 16
    const val GREEN_SHIFT = 8
    const val BLUE_SHIFT = 0
    const val BYTE = 0xFF
    const val HEX = 16

    // A four by two opaque mid grey PNG tagged sRGB.
    val GREY_PNG =
      ImageSource.ofBytes(
        (
          "89504e470d0a1a0a0000000d49484452000000040000000208060000007fa87d63000000017352474200aece1ce9" +
            "000000114944415478da63686868f88f8c19d005004db513f9369e45fb0000000049454e44ae426082"
        ).decodeHex(),
      )
    val PNG_SIZE = Size(4, 2)

    // Headroom for the trip through Core Image's working space, which need not land back on the
    // exact code value it started from.
    const val TOLERANCE = 2

    // Colour equal to alpha, and colour apart from it at a low alpha, where a divide by alpha widens any rounding most.
    val TRANSLUCENT_PLATES = listOf(0x80808080.toInt(), 0x40C8C8C8)
  }
}

package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.effects.assertRasterReads
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.picture.picturePeakBytes
import dev.jordond.filmstrip.style.TextStyle
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * The text [rasterizeText] refuses on Apple, past the picture ceiling, far too large to size, or when Core Graphics
 * makes no bitmap for it.
 */
@OptIn(ExperimentalForeignApi::class)
class TextRasterRefusalTest {
  @Test
  fun `a raster past the ceiling is refused before its bitmap is made`() {
    assertRasterReads()
    val style = TextStyle(color = GREY, backgroundColor = GREY)
    val size = drawnText("H", style, FRAME).size
    val texels = size.width.toLong() * size.height
    val band = drawnBandBytes(size.width.toLong(), size.height.toLong())
    val peak = picturePeakBytes(texels * TEXT_TEXEL_BYTES, band, texels * FLOAT_TEXEL_BYTES).shouldNotBeNull()
    var made = false
    val counted = { width: Int, height: Int ->
      made = true
      textBitmap(width, height)
    }

    val refused =
      rasterizeText("H", style, FRAME, ceiling = peak - 1, bitmapOf = counted).shouldBeInstanceOf<TextRaster.Refused>()
    val reason = textTooLargeToDraw(size.width.toLong(), size.height.toLong(), TEXT_TEXEL_BYTES, peak - 1)
    refused.reason shouldBe reason
    withClue("the bitmap was made before the raster was refused") { made shouldBe false }

    val drawn =
      rasterizeText(
        "H",
        style,
        FRAME,
        ceiling = peak,
        bitmapOf = counted,
      ).shouldBeInstanceOf<TextRaster.Drawn<OverlayPixels>>()
    withClue("the bitmap holds more than its texels") { drawn.pixels.held.bytes shouldBe texels * TEXT_TEXEL_BYTES }
    // The HDR draw counts the same peak against the same ceiling, so a raster that fits draws its floats and one a
    // byte under it does not.
    drawn.pixels.enteringFrame(HdrTransfer.Pq, ColorSpace.Bt2020)
    val under = OverlayPixels(drawn.pixels.held, ceiling = peak - 1)
    assertFailsWith<PictureTooLargeToDraw> { under.enteringFrame(HdrTransfer.Pq, ColorSpace.Bt2020) }
  }

  // A plate's padding saturates at the largest Int for a font this size, and two of those wrapped a side negative.
  @Test
  fun `a plated text far too large is refused and never made`() {
    var made = false
    val counted = { width: Int, height: Int ->
      made = true
      textBitmap(width, height)
    }
    val style = TextStyle(fontSize = HUGE_FONT, backgroundColor = GREY)

    val raster = rasterizeText("H", style, FRAME, bitmapOf = counted)

    val refused =
      withClue("the text at a font size of $HUGE_FONT was not refused") {
        raster.shouldBeInstanceOf<TextRaster.Refused>()
      }
    refused.reason shouldBe textTooTallToLayOut(ceil(lineOf("H", style)).toLong())
    withClue("a bitmap was made for text at a font size of $HUGE_FONT") { made shouldBe false }
  }

  // Core Text measures a face whole up to its tallest line and nothing past it, whatever height it is given.
  @Test
  fun `a face core text lays out is refused by its measured sides and one past its tallest line by name`() {
    val perFontSize = lineOf("H", TextStyle(fontSize = 1f))
    for (share in listOf(0.5, 0.9, 0.9999, 1.0001, 1.5, 10.0)) {
      val style = TextStyle(fontSize = (CORE_TEXT_TALLEST_LINE * share / perFontSize).toFloat())
      val line = lineOf("H", style)
      val block = blockOf("H", style)
      var made = false
      val counted = { width: Int, height: Int ->
        made = true
        textBitmap(width, height)
      }

      val refused =
        withClue("at $share") {
          rasterizeText("H", style, FRAME, bitmapOf = counted).shouldBeInstanceOf<TextRaster.Refused>()
        }
      if (line <= CORE_TEXT_TALLEST_LINE) {
        withClue("a $line pixel line measured ${block.height} tall at $share") { (block.height >= line) shouldBe true }
        val (wide, tall) = block.width.toLong() to block.height.toLong()
        withClue("at $share") { refused.reason shouldBe textTooLargeToDraw(wide, tall, TEXT_TEXEL_BYTES) }
      } else {
        withClue("Core Text measured a $line pixel line at $share") { block shouldBe Size(0, 0) }
        withClue("at $share") { refused.reason shouldBe textTooTallToLayOut(ceil(line).toLong()) }
      }
      withClue("a bitmap was made at $share") { made shouldBe false }
    }
  }

  // Laid out a million pixels tall, these lines were cut off at about two fifths of their height.
  @Test
  fun `text many lines tall is measured whole and refused by its whole size`() {
    val text = "H" + "\n".repeat(NEWLINES) + "H"
    val style = TextStyle(fontSize = SMALL_FONT)
    val block = blockOf(text, style)
    // Core Text sets lines apart by its own pitch rather than the face's ascent and descent, read off two lines.
    val pitch = blockOf("H\nH", style).height - blockOf("H", style).height
    withClue("$NEWLINES lines $pitch pixels apart measured $block") { (block.height >= NEWLINES * pitch) shouldBe true }

    val (wide, tall) = block.width.toLong() to block.height.toLong()
    val expected =
      if (textRasterFits(wide, tall, TEXT_TEXEL_BYTES, drawnBandBytes(wide, tall))) {
        textAllocationFailed(block.width, block.height)
      } else {
        textTooLargeToDraw(wide, tall, TEXT_TEXEL_BYTES)
      }
    val refused =
      rasterizeText(text, style, FRAME, bitmapOf = {
        _,
        _,
        ->
        null
      }).shouldBeInstanceOf<TextRaster.Refused>()
    refused.reason shouldBe expected
  }

  @Test
  fun `a bitmap core graphics cannot make refuses the raster`() {
    val style = TextStyle(color = GREY, backgroundColor = GREY)
    val size = drawnText("H", style, FRAME).size

    val refused = rasterizeText("H", style, FRAME, bitmapOf = { _, _ -> null }).shouldBeInstanceOf<TextRaster.Refused>()
    refused.reason shouldBe textAllocationFailed(size.width, size.height)
  }

  private fun drawnText(
    text: String,
    style: TextStyle,
    frame: Size,
  ): OverlayPixels =
    withClue("the text drew nothing") {
      rasterizeText(text, style, frame).shouldBeInstanceOf<TextRaster.Drawn<OverlayPixels>>()
    }.pixels

  private fun blockOf(
    text: String,
    style: TextStyle,
  ): Size =
    withClue("nothing was laid out") {
      laidOutText(text, style, FRAME) { _, _, block, _ -> block }.shouldNotBeNull()
    }

  private fun lineOf(
    text: String,
    style: TextStyle,
  ): Double =
    withClue("nothing was laid out") {
      laidOutText(text, style, FRAME) { _, _, _, line -> line }.shouldNotBeNull()
    }

  private companion object {
    val FRAME = Size(1280, 720)
    const val GREY = 0xFF808080.toInt()

    // A cap height ten million frames tall, far outside the documented range and past what a side can hold.
    const val HUGE_FONT = 1e7f

    // About ten pixel text, each line a little over twelve pixels tall.
    const val SMALL_FONT = 0.01f
    const val NEWLINES = 200_000
  }
}

package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.style.FontWeight
import dev.jordond.filmstrip.style.TextStyle
import dev.jordond.filmstrip.test.sampleOfCode
import dev.jordond.filmstrip.test.straightSampleTolerance
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.math.ceil
import kotlin.test.Test

/**
 * The text raster the JVM draws for the filter graph, from the face it finds to the samples it lays.
 *
 * Every case draws in the faces the host has, so each one reads only what any host with a sans serif draws the same.
 */
class JvmTextRasterTest {
  @Test
  fun `a translucent plate keeps its straight color in every texel the glyphs leave uncovered`() {
    for ((code, alpha) in listOf(0x80 to 0x80, 0xC8 to 0x40)) {
      val plate = (alpha shl ALPHA_SHIFT) or (code * GREY)
      val picture = drawn("Plate", TextStyle(backgroundColor = plate))
      val samples = picture.samples.shouldBeInstanceOf<StoredSamples.Rgba16>().rgba

      val corner = (0 until RGBA).map { samples[it].toInt() and SAMPLE_MASK }
      val tolerance = straightSampleTolerance(alpha).toDouble()
      for (channel in 0 until RGB) {
        withClue("0x${code.toString(16)} at alpha 0x${alpha.toString(16)}, channel $channel") {
          corner[channel].toDouble() shouldBe (sampleOfCode(code).toDouble() plusOrMinus tolerance)
        }
      }
      withClue("alpha 0x${alpha.toString(16)}") { corner[RGB] shouldBe sampleOfCode(alpha) }
    }
  }

  @Test
  fun `a capital stands the cap height the style names on the frame`() {
    for (fontSize in listOf(SMALL_CAP, MID_CAP, LARGE_CAP)) {
      val style = TextStyle(fontSize = fontSize)
      val picture = drawn(CAPITAL, style)

      withClue("font size $fontSize") {
        inkRows(picture).toFloat() shouldBe (style.capHeightOn(FRAME) plusOrMinus CAP_TOLERANCE)
      }
    }
  }

  @Test
  fun `a long line wraps at the wrap width and the raster is the widest line with the plate either side`() {
    val style = TextStyle(maxWidth = NARROW, backgroundColor = OPAQUE_PLATE)
    val block = textBlock(LONG_TEXT, style, FRAME, sansSerif()).shouldNotBeNull()
    val wrap = style.wrapWidthOn(FRAME)

    block.lines.size shouldBeGreaterThan 1
    for (line in block.lines) {
      val advance = line.shouldNotBeNull().visibleAdvance
      withClue("a line runs past the wrap width") { (advance <= wrap) shouldBe true }
    }
    block.width shouldBe ceil(block.lines.maxOf { checkNotNull(it).visibleAdvance }.toDouble()).toLong()
    block.height shouldBe ceil(block.lineHeight.toDouble() * block.lines.size).toLong()

    val padding = style.platePaddingOn(FRAME)
    drawn(LONG_TEXT, style).size shouldBe
      Size(plateSide(block.width, padding).toInt(), plateSide(block.height, padding).toInt())
  }

  @Test
  fun `a short line is laid tight to its own width rather than the wrap width`() {
    val style = TextStyle(backgroundColor = OPAQUE_PLATE)
    val block = textBlock(CAPITAL, style, FRAME, sansSerif()).shouldNotBeNull()

    block.lines.size shouldBe 1
    block.width.toInt() shouldBeLessThan style.wrapWidthOn(FRAME)
  }

  @Test
  fun `a raster one byte past its ceiling is refused before it is drawn`() {
    val size = drawn(CAPITAL, TextStyle.Default).size
    val wide = size.width.toLong()
    val tall = size.height.toLong()
    val texels = wide * tall
    val peak =
      picturePeakBytes(texels * TEXT_TEXEL_BYTES, jvmTextBandBytes(wide, tall), texels * FLOAT_TEXEL_BYTES)
        .shouldNotBeNull()

    rasterizeText(CAPITAL, TextStyle.Default, FRAME, ceiling = peak).shouldBeInstanceOf<TextRaster.Drawn<*>>()
    val refused =
      rasterizeText(CAPITAL, TextStyle.Default, FRAME, ceiling = peak - 1).shouldBeInstanceOf<TextRaster.Refused>()
    refused.reason shouldBe textTooLargeToDraw(wide, tall, TEXT_TEXEL_BYTES, peak - 1)
  }

  @Test
  fun `a host with no face to draw the family in is refused by name`() {
    val style = TextStyle(resolvedFontFamily = "Nowhere Sans")

    val refused =
      rasterizeText(CAPITAL, style, FRAME, fontOf = { _, _ -> null }).shouldBeInstanceOf<TextRaster.Refused>()
    refused.reason shouldBe textNoFont(style)
  }

  @Test
  fun `a character the face has no glyph for is refused by name rather than drawn as a box`() {
    val text = "$CAPITAL $UNCOVERED_TEXT"
    sansSerif().canDisplay(UNCOVERED) shouldBe false

    val refused = rasterizeText(text, TextStyle.Default, FRAME).shouldBeInstanceOf<TextRaster.Refused>()
    refused.reason shouldBe textMissingGlyph(TextStyle.Default, UNCOVERED)
  }

  @Test
  fun `spaces and line breaks the face draws nothing for still draw`() {
    rasterizeText("$CAPITAL \t\n$CAPITAL", TextStyle.Default, FRAME).shouldBeInstanceOf<TextRaster.Drawn<*>>()
  }

  @Test
  fun `no text leaves nothing to draw`() {
    rasterizeText("", TextStyle.Default, FRAME) shouldBeSameInstanceAs TextRaster.Empty
  }

  private fun drawn(
    text: String,
    style: TextStyle,
  ): StoredPicture = rasterizeText(text, style, FRAME).shouldBeInstanceOf<TextRaster.Drawn<StoredPicture>>().pixels

  private fun sansSerif() = awtFont(null, FontWeight.Regular).shouldNotBeNull()

  // The rows a capital covers more than half of somewhere along them, which is its drawn height to a pixel.
  private fun inkRows(picture: StoredPicture): Int {
    val samples = (picture.samples as StoredSamples.Rgba16).rgba
    val width = picture.size.width
    return (0 until picture.size.height).count { row ->
      (0 until width).any { column ->
        (samples[(row * width + column) * RGBA + RGB].toInt() and SAMPLE_MASK) > SAMPLE_MASK / 2
      }
    }
  }

  private companion object {
    val FRAME = Size(1920, 1080)
    const val CAPITAL = "H"
    const val LONG_TEXT = "The quick brown fox jumps over the lazy dog, and then it does it all again"
    const val SMALL_CAP = 0.02f
    const val MID_CAP = 0.07f
    const val LARGE_CAP = 0.25f
    const val NARROW = 0.3f

    // The first code point of Supplementary Private Use Area-A, which no font a JDK or an OS ships has a glyph for.
    const val UNCOVERED = 0xF0000
    val UNCOVERED_TEXT = String(Character.toChars(UNCOVERED))
    const val OPAQUE_PLATE = 0xFF202020.toInt()

    // An antialiased edge row counts once it is half covered, which lands within a pixel of the cap at either end.
    const val CAP_TOLERANCE = 1.5f
    const val GREY = 0x010101
    const val ALPHA_SHIFT = 24
    const val SAMPLE_MASK = 0xFFFF
    const val RGB = 3
    const val RGBA = 4
  }
}

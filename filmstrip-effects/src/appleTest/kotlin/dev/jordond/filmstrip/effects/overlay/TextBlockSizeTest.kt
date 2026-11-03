package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.style.TextAlignment
import dev.jordond.filmstrip.style.TextStyle
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test

/**
 * How big a block Apple's [rasterizeText] draws, sized to its glyphs, wrapped at the authored width, scaled with the
 * frame and padded by a plate.
 */
@OptIn(ExperimentalForeignApi::class)
class TextBlockSizeTest {
  @Test
  fun `sizes the block to the glyphs and not to the authored wrap width`() {
    val narrow = drawnText("hi", TextStyle(maxWidth = 0.9f), FRAME)
    val wide = drawnText("a much longer caption", TextStyle(maxWidth = 0.9f), FRAME)

    withClue("both blocks came back the same width, so the box is the authored one") {
      (wide.size.width > narrow.size.width) shouldBe true
    }
    withClue("the short block filled the whole wrap width") { (narrow.size.width < FRAME.width) shouldBe true }
  }

  @Test
  fun `wraps at the authored width onto more than one line`() {
    val style = TextStyle(fontSize = 0.08f, maxWidth = 0.3f, alignment = TextAlignment.Center)
    val one = drawnText("one", style, FRAME)
    val many = drawnText("one two three four five six seven", style, FRAME)

    withClue("the long string did not wrap") { (many.size.height > one.size.height) shouldBe true }
  }

  @Test
  fun `scales the block with the frame it is drawn on`() {
    val style = TextStyle(fontSize = 0.1f)
    val small = drawnText("caption", style, Size(640, 360))
    val large = drawnText("caption", style, Size(1280, 720))

    val ratio = large.size.height.toFloat() / small.size.height
    withClue("doubling the frame changed the block by ${ratio}x") { (ratio in 1.8f..2.2f) shouldBe true }
  }

  @Test
  fun `pads a block that carries a background plate`() {
    val plain = drawnText("caption", TextStyle(), FRAME)
    val plated = drawnText("caption", TextStyle(backgroundColor = BLACK), FRAME)

    withClue("the plate stops at the glyphs") { (plated.size.width > plain.size.width) shouldBe true }
  }

  @Test
  fun `a plate widens the raster by its padding on every side`() {
    val plated = TextStyle(fontSize = FONT_SIZE, backgroundColor = BLACK, maxWidth = MAX_WIDTH)
    val unplated = TextStyle(fontSize = FONT_SIZE, maxWidth = MAX_WIDTH)

    val withPlate = drawnText(WORD, plated, METRICS_FRAME).size
    val without = drawnText(WORD, unplated, METRICS_FRAME).size

    val padding = plated.platePaddingOn(METRICS_FRAME)
    withPlate.width shouldBe without.width + 2 * padding
    withPlate.height shouldBe without.height + 2 * padding
  }

  @Test
  fun `text wider than the wrap width breaks onto a second line`() {
    val once = drawnText(WORD, TextStyle(fontSize = FONT_SIZE, maxWidth = 1f), METRICS_FRAME)
    val word = once.size

    // A wrap width half again the word's own width holds the word once but not twice.
    val style = TextStyle(fontSize = FONT_SIZE, maxWidth = 1.5f * word.width / METRICS_FRAME.width)
    val wrap = style.wrapWidthOn(METRICS_FRAME)
    withClue("wrap $wrap against a word ${word.width} wide") {
      (word.width <= wrap && wrap < 2 * word.width) shouldBe true
    }

    val twice = drawnText("$WORD $WORD", style, METRICS_FRAME).size

    // CoreText measures a fractional line height and the block is rounded up once, not per line, so
    // two lines stand one pixel short of twice a rounded single line at most.
    withClue("two lines stand ${twice.height} tall") {
      (twice.height in 2 * word.height - 1..2 * word.height) shouldBe true
    }
    withClue("a ${twice.width} wide raster outgrew its $wrap wrap") { (twice.width <= wrap) shouldBe true }
  }

  private fun drawnText(
    text: String,
    style: TextStyle,
    frame: Size,
  ): OverlayPixels =
    withClue("the text drew nothing") {
      rasterizeText(text, style, frame).shouldBeInstanceOf<TextRaster.Drawn<OverlayPixels>>()
    }.pixels

  private companion object {
    val FRAME = Size(1280, 720)
    const val BLACK = 0xFF000000.toInt()

    val METRICS_FRAME = Size(1920, 1080)
    const val WORD = "Filmstrip"
    const val FONT_SIZE = 0.06f
    const val MAX_WIDTH = 0.55f
  }
}

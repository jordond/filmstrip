package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.style.TextAlignment
import dev.jordond.filmstrip.style.TextStyle
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

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

    assertTrue(
      wide.size.width > narrow.size.width,
      "both blocks came back the same width, so the box is the authored one",
    )
    assertTrue(narrow.size.width < FRAME.width, "the short block filled the whole wrap width")
  }

  @Test
  fun `wraps at the authored width onto more than one line`() {
    val style = TextStyle(fontSize = 0.08f, maxWidth = 0.3f, alignment = TextAlignment.Center)
    val one = drawnText("one", style, FRAME)
    val many = drawnText("one two three four five six seven", style, FRAME)

    assertTrue(many.size.height > one.size.height, "the long string did not wrap")
  }

  @Test
  fun `scales the block with the frame it is drawn on`() {
    val style = TextStyle(fontSize = 0.1f)
    val small = drawnText("caption", style, Size(640, 360))
    val large = drawnText("caption", style, Size(1280, 720))

    val ratio = large.size.height.toFloat() / small.size.height
    assertTrue(ratio in 1.8f..2.2f, "doubling the frame changed the block by ${ratio}x")
  }

  @Test
  fun `pads a block that carries a background plate`() {
    val plain = drawnText("caption", TextStyle(), FRAME)
    val plated = drawnText("caption", TextStyle(backgroundColor = BLACK), FRAME)

    assertTrue(plated.size.width > plain.size.width, "the plate stops at the glyphs")
  }

  @Test
  fun `a plate widens the raster by its padding on every side`() {
    val plated = TextStyle(fontSize = FONT_SIZE, backgroundColor = BLACK, maxWidth = MAX_WIDTH)
    val unplated = TextStyle(fontSize = FONT_SIZE, maxWidth = MAX_WIDTH)

    val withPlate = drawnText(WORD, plated, METRICS_FRAME).size
    val without = drawnText(WORD, unplated, METRICS_FRAME).size

    val padding = plated.platePaddingOn(METRICS_FRAME)
    assertEquals(without.width + 2 * padding, withPlate.width)
    assertEquals(without.height + 2 * padding, withPlate.height)
  }

  @Test
  fun `text wider than the wrap width breaks onto a second line`() {
    val once = drawnText(WORD, TextStyle(fontSize = FONT_SIZE, maxWidth = 1f), METRICS_FRAME)
    val word = once.size

    // A wrap width half again the word's own width holds the word once but not twice.
    val style = TextStyle(fontSize = FONT_SIZE, maxWidth = 1.5f * word.width / METRICS_FRAME.width)
    val wrap = style.wrapWidthOn(METRICS_FRAME)
    assertTrue(word.width <= wrap && wrap < 2 * word.width, "wrap $wrap against a word ${word.width} wide")

    val twice = drawnText("$WORD $WORD", style, METRICS_FRAME).size

    // CoreText measures a fractional line height and the block is rounded up once, not per line, so
    // two lines stand one pixel short of twice a rounded single line at most.
    assertTrue(twice.height in 2 * word.height - 1..2 * word.height, "two lines stand ${twice.height} tall")
    assertTrue(twice.width <= wrap, "a ${twice.width} wide raster outgrew its $wrap wrap")
  }

  private fun drawnText(
    text: String,
    style: TextStyle,
    frame: Size,
  ): OverlayPixels =
    assertIs<TextRaster.Drawn<OverlayPixels>>(rasterizeText(text, style, frame), "the text drew nothing").pixels

  private companion object {
    val FRAME = Size(1280, 720)
    const val BLACK = 0xFF000000.toInt()

    val METRICS_FRAME = Size(1920, 1080)
    const val WORD = "Filmstrip"
    const val FONT_SIZE = 0.06f
    const val MAX_WIDTH = 0.55f
  }
}

package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.toByteArray
import dev.jordond.filmstrip.style.TextStyle
import dev.jordond.filmstrip.test.sampleOfCode
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The glyph coverage the browser's text raster is drawn from, in the text's own colour and in tiles.
 *
 * A browser shapes a glyph's coverage by the colour it is filled with, so the coverage is read against the same text
 * drawn on one plain canvas in its colour. Tiles are read against one canvas small enough to hold the whole text.
 */
@OptIn(InternalFilmstripApi::class, ExperimentalWasmJsInterop::class)
class WebTextCoverageTest {
  @Test
  fun textAtFullAlphaKeepsTheCoverageItsOwnColourDrawsAt() {
    for (ink in INKS) {
      val coverage = drawnCoverage(ink, TEXT_TILE_SIDE)
      val samples = platedSamples(coverage, ink, plate = null)
      val canvas = plainCanvasRead(ink)

      val missed = (canvas.indices step CHANNELS).count { sampleAt(samples, it) != alphaOf(canvas, it) }
      assertEquals(0, missed, "${ink.hex()} text kept a coverage apart from its own colour's at $missed texels")
      assertTrue(canvas.indices.any { canvas[it] != 0.toByte() }, "${ink.hex()} text drew nothing")
    }
  }

  @Test
  fun everyTileIsFilledInTheTextColourAtFullAlpha() {
    for (ink in INKS + TRANSLUCENT_INK) {
      val fills = mutableSetOf<String>()

      textCoverage(WIDE, TALL, ink, NO_SIDE_LIMIT, tileSide = TINY_TILE_SIDE) { context, _ ->
        fills += context.fillStyle
      }

      assertEquals(setOf(cssHexOf(ink)), fills, "${ink.hex()} text was filled in $fills")
    }
  }

  // The fill rests on this. A browser that covered glyphs alike in every colour fails it rather than skipping it.
  @Test
  fun aBrowserCoversGlyphsByTheColourTheyAreDrawnIn() {
    val white = alphasOf(plainCanvasRead(WHITE))

    for (ink in INKS) {
      assertNotEquals(white, alphasOf(plainCanvasRead(ink)), "${ink.hex()} glyphs cover as white ones do")
    }
  }

  @Test
  fun textDrawnInTilesMatchesItDrawnOnOneCanvas() {
    for (ink in INKS) {
      val whole = drawnCoverage(ink, TEXT_TILE_SIDE)
      val tiled = drawnCoverage(ink, TINY_TILE_SIDE)

      assertContentEquals(whole, tiled, "${ink.hex()} text drawn in $TINY_TILE_SIDE pixel tiles")
    }
  }

  @Test
  fun aColumnTallerThanTwoTilesIsCoveredOnEveryRow() {
    val drawn =
      textCoverage(1, TALL_COLUMN.toLong(), WHITE, NO_SIDE_LIMIT) { context, _ ->
        context.fillRect(0.0, 0.0, 1.0, TALL_COLUMN.toDouble())
      }

    val coverage = assertIs<TextRaster.Drawn<ByteArray>>(drawn).pixels
    assertTrue(TALL_COLUMN > 2 * TEXT_TILE_SIDE)
    assertEquals(TALL_COLUMN * CHANNELS, coverage.size)
    val bare = (0 until TALL_COLUMN).filter { (coverage[it * CHANNELS + ALPHA].toInt() and BYTE) != BYTE }
    assertTrue(bare.isEmpty(), "rows ${bare.take(SHOWN)} of $TALL_COLUMN are not covered")
  }

  @Test
  fun textTallerThanATileDrawsInEveryTile() {
    val raster = drawnText(LONG_TEXT, LONG_STYLE, FRAME, NO_SIDE_LIMIT)
    val (width, height) = raster.size.width to raster.size.height

    assertTrue(height > 2 * TEXT_TILE_SIDE, "the long text stands $height tall")
    for (top in 0 until height step TEXT_TILE_SIDE) {
      val rows = top until minOf(top + TEXT_TILE_SIDE, height)
      val covered = rows.any { y -> (0 until width).any { sampleAt(raster.samples, (y * width + it) * CHANNELS) > 0 } }
      assertTrue(covered, "the tile from row $top holds no text")
    }
  }

  @Test
  fun textTallerThanATileResolvesUnderTheTextureSideAndNotPastIt() {
    val maxSide = 2 * TEXT_TILE_SIDE
    val capabilities = textCapabilities(maxSide)
    val line = drawnText("i", LONG_STYLE, FRAME).size.height
    val text = List((TEXT_TILE_SIDE + maxSide) / 2 / line) { "i" }.joinToString("\n")
    val height = drawnText(text, LONG_STYLE, FRAME, maxSide).size.height
    assertTrue(height in TEXT_TILE_SIDE + 1..maxSide, "the text stands $height tall")

    val resolver = BuiltInEffectResolver()
    val fits = resolver.resolve(TextOverlay(text, LONG_STYLE), capabilities, TEXT_ATTRIBUTES)
    assertIs<EffectResolution.Resolved>(fits, "the text $height tall resolved as $fits")

    val past = resolver.resolve(TextOverlay(LONG_TEXT, LONG_STYLE), capabilities, TEXT_ATTRIBUTES)
    val refused = assertIs<TextRaster.Refused>(rasterizeText(LONG_TEXT, LONG_STYLE, FRAME, maxSide))
    val unsupported = assertIs<EffectResolution.Unsupported>(past, "the long text resolved as $past")
    assertEquals(refused.reason, unsupported.message)
  }

  @Test
  fun aRasterATileWideDrawsInOneTileAndOneTexelMoreInFour() {
    val side = TEXT_TILE_SIDE
    val one = filledInTiles(side)
    assertEquals(1, one.tiles, "a $side pixel square drew in ${one.tiles} tiles")
    for ((x, y) in listOf(0 to 0, side / 2 to side / 2, side - 1 to side - 1)) {
      assertEquals(BYTE, alphaAt(one.coverage, side, x, y), "texel $x, $y of the $side pixel square")
    }

    val four = filledInTiles(side + 1)
    assertEquals(4, four.tiles, "a ${side + 1} pixel square drew in ${four.tiles} tiles")
    val seams = listOf(0, side - 1, side)
    val bare =
      (0..side)
        .flatMap { along -> seams.flatMap { across -> listOf(along to across, across to along) } }
        .filter { (x, y) -> alphaAt(four.coverage, side + 1, x, y) != BYTE }
    assertTrue(bare.isEmpty(), "texels ${bare.take(SHOWN)} on the corners and seams are not covered")
  }

  @Test
  fun everyTileCanvasIsSetToNothingOnceItIsRead() {
    val contexts = mutableListOf<OverlayContext2D>()

    textCoverage(WIDE, TALL, WHITE, NO_SIDE_LIMIT, tileSide = TINY_TILE_SIDE) { context, _ ->
      contexts += context
    }

    assertTrue(contexts.size > 1, "the text drew ${contexts.size} tiles")
    val held = contexts.filter { it.canvas.width != 0 || it.canvas.height != 0 }
    assertTrue(held.isEmpty(), "${held.size} of ${contexts.size} tile canvases still hold pixels")
  }

  @Test
  fun aTileCanvasIsSetToNothingWhenDrawingItThrows() {
    var drawnOn: OverlayContext2D? = null

    assertFailsWith<IllegalStateException> {
      textCoverage(WIDE, TALL, WHITE, NO_SIDE_LIMIT) { context, _ ->
        drawnOn = context
        error("the draw failed")
      }
    }

    val canvas = assertNotNull(drawnOn, "the draw was never called").canvas
    assertEquals(0 to 0, canvas.width to canvas.height, "the tile canvas still holds pixels")
  }

  private class Filled(
    val coverage: ByteArray,
    val tiles: Int,
  )

  private fun filledInTiles(side: Int): Filled {
    var tiles = 0
    val drawn =
      textCoverage(side.toLong(), side.toLong(), WHITE, NO_SIDE_LIMIT) { context, _ ->
        tiles++
        context.fillRect(0.0, 0.0, side.toDouble(), side.toDouble())
      }
    return Filled(assertIs<TextRaster.Drawn<ByteArray>>(drawn).pixels, tiles)
  }

  private fun alphaAt(
    coverage: ByteArray,
    width: Int,
    x: Int,
    y: Int,
  ): Int = coverage[(y * width + x) * CHANNELS + ALPHA].toInt() and BYTE

  private fun drawnCoverage(
    ink: Int,
    tileSide: Int,
  ): ByteArray {
    val drawn =
      textCoverage(WIDE, TALL, ink, NO_SIDE_LIMIT, tileSide = tileSide) { context, _ ->
        drawLines(context)
      }
    return assertIs<TextRaster.Drawn<ByteArray>>(drawn).pixels
  }

  // The same lines drawn the way the raster drew them before it kept only their coverage, in the text colour on an
  // eight-bit canvas.
  private fun plainCanvasRead(ink: Int): ByteArray {
    val canvas = OffscreenCanvas(SIZE.width, SIZE.height)
    val context = checkNotNull(canvas.getContext(CONTEXT_2D))
    context.fillStyle = cssHexOf(ink)
    drawLines(context)
    return context
      .getImageData(0, 0, SIZE.width, SIZE.height)
      .data.buffer
      .toByteArray()
  }

  private fun drawLines(context: OverlayContext2D) {
    context.font = FONT
    context.textBaseline = ALPHABETIC
    LINES.forEachIndexed { index, line -> context.fillText(line, LINE_X, LINE_Y + index * LINE_ADVANCE) }
  }

  private fun sampleAt(
    samples: ShortArray,
    texel: Int,
  ): Int = samples[texel + ALPHA].toInt() and SAMPLE_MASK

  private fun alphaOf(
    codes: ByteArray,
    texel: Int,
  ): Int = sampleOfCode(codes[texel + ALPHA].toInt() and BYTE)

  private fun Int.hex(): String = toUInt().toString(HEX)

  // How a canvas serialises an opaque fill, lowercase hex with no alpha.
  private fun cssHexOf(ink: Int): String = "#${(ink and RGB_MASK).toString(HEX).padStart(HEX_DIGITS, '0')}"

  private fun alphasOf(codes: ByteArray): List<Byte> = codes.filterIndexed { index, _ -> index % CHANNELS == ALPHA }

  private companion object {
    // Black, which a mask keyed to white covers least, and a mid colour apart in every channel. Both cover apart from
    // white in Chrome.
    val INKS = listOf(0xFF000000.toInt(), 0xFF3060C0.toInt())

    // The mid colour at half alpha, whose alpha the fill leaves out.
    const val TRANSLUCENT_INK = 0x803060C0.toInt()

    const val WHITE = 0xFFFFFFFF.toInt()

    // Two wrapped lines at a fractional origin, so glyph edges land between pixels and on both sides of a tile seam.
    val SIZE = Size(360, 120)
    val WIDE = SIZE.width.toLong()
    val TALL = SIZE.height.toLong()
    val LINES = listOf("Hamburgefonts", "quick jigsaw")
    const val FONT = "40px sans-serif"
    const val ALPHABETIC = "alphabetic"
    const val LINE_X = 3.25
    const val LINE_Y = 44.5
    const val LINE_ADVANCE = 52.0

    // Small and dividing neither side evenly, so tile seams cross every glyph and the last tiles are partial.
    const val TINY_TILE_SIDE = 7

    const val TALL_COLUMN = 9000

    val FRAME = Size(1280, 720)

    // Ten thousand lines of one small letter stand many tiles tall, a few pixels wide and well under the ceiling, and
    // past any texture's side.
    val LONG_STYLE = TextStyle(fontSize = 0.01f)
    val LONG_TEXT = List(10_000) { "i" }.joinToString("\n")

    const val NO_SIDE_LIMIT = Int.MAX_VALUE

    const val CHANNELS = 4
    const val ALPHA = 3
    const val BYTE = 0xFF
    const val RGB_MASK = 0xFFFFFF
    const val HEX = 16
    const val HEX_DIGITS = 6
    const val SHOWN = 8
  }
}

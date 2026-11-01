package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.style.TextStyle
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Which lines each tile of the browser's text raster draws.
 *
 * A tile draws only the lines whose glyphs can reach it, so the ranges stay near one tile tall however many lines the
 * text holds, and text drawn in many tiles matches it drawn in one.
 */
class WebTextCullingTest {
  @Test
  fun eachTileTakesOnlyTheLinesThatReachIt() {
    val height = ceil(FIRST_BASELINE + LINES * ADVANCE).toInt()
    val reach = maxOf(REACH_UP, REACH_DOWN)
    val widest = TILE / ADVANCE + 2 * (reach + INK_MARGIN) / ADVANCE + 2

    var taken = 0L
    for (top in 0 until height step TILE) {
      val bottom = minOf(top + TILE, height)
      val range = linesInTile(top, bottom, FIRST_BASELINE, ADVANCE, REACH_UP, REACH_DOWN, LINES)
      taken += range.count()

      assertTrue(range.count() <= widest, "the tile from row $top took ${range.count()} lines, past $widest")
      val missed =
        (0 until LINES).filter { line ->
          val baseline = FIRST_BASELINE + line * ADVANCE
          baseline + REACH_DOWN + INK_MARGIN > top && baseline - REACH_UP - INK_MARGIN < bottom && line !in range
        }
      assertTrue(missed.isEmpty(), "the tile from row $top left out lines ${missed.take(SHOWN)} that reach it")
    }
    assertTrue(taken <= 2L * LINES, "the tiles took $taken lines of $LINES")
  }

  @Test
  fun textThatOverhangsItsLinesDrawsTheSameInSmallTiles() {
    val whole = rasterOf(TEXT_TILE_SIDE)
    assertTrue(whole.size.width <= TEXT_TILE_SIDE && whole.size.height <= TEXT_TILE_SIDE, "${whole.size} is one tile")
    assertTrue(whole.samples.any { it != 0.toShort() }, "the overhanging text drew nothing")

    for (tileSide in SEAM_TILE_SIDES) {
      assertContentEquals(whole.samples, rasterOf(tileSide).samples, "the text drawn in $tileSide pixel tiles")
    }
  }

  @Test
  fun aHundredThousandLinesDrawInkInTheFirstMiddleAndLastTiles() {
    val text = List(LINES) { "i" }.joinToString("\n")

    val raster = assertIs<TextRaster.Drawn<LaidOutText>>(rasterizeText(text, LONG_STYLE, FRAME, Int.MAX_VALUE)).pixels

    val (width, height) = raster.size.width to raster.size.height
    val tops = (0 until height step TEXT_TILE_SIDE).toList()
    assertTrue(tops.size > 2, "the text stands $height tall")
    for (top in listOf(tops.first(), tops[tops.size / 2], tops.last())) {
      val rows = top until minOf(top + TEXT_TILE_SIDE, height)
      val inked = rows.any { y -> (0 until width).any { raster.samples[(y * width + it) * CHANNELS + ALPHA] != ZERO } }
      assertTrue(inked, "the tile from row $top holds no text")
    }
  }

  private fun rasterOf(tileSide: Int): LaidOutText {
    val drawn = rasterizeText(OVERHANGING, SEAM_STYLE, FRAME, Int.MAX_VALUE, tileSide = tileSide)
    return assertIs<TextRaster.Drawn<LaidOutText>>(drawn, "the text in $tileSide pixel tiles").pixels
  }

  private companion object {
    val FRAME = Size(1280, 720)

    // A fractional advance and baseline, so line tops fall between rows and land on both sides of a tile edge.
    const val LINES = 100_000
    const val TILE = 2048
    const val ADVANCE = 10.75
    const val FIRST_BASELINE = 8.5
    const val REACH_UP = 9.25
    const val REACH_DOWN = 3.5

    // Stacked combining marks, a ring above a capital, descenders, CJK and an emoji, each reaching past its line box.
    val OVERHANGING =
      listOf(
        "Z̷̡͎͑̈́a͛͂l̷g̓o",
        "Å",
        "gy",
        "漢字",
        "😀",
      ).joinToString("\n")
    val SEAM_STYLE = TextStyle(fontSize = 0.06f, maxWidth = 1f)

    // Small and dividing neither side evenly, so tile edges cross every line and the marks above and below it.
    val SEAM_TILE_SIDES = listOf(7, 13)

    val LONG_STYLE = TextStyle(fontSize = 0.01f)

    const val CHANNELS = 4
    const val ALPHA = 3
    const val ZERO: Short = 0
    const val SHOWN = 8
  }
}

package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.style.TextStyle
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.ceil
import kotlin.test.Test

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

      withClue("the tile from row $top took ${range.count()} lines, past $widest") {
        (range.count() <= widest) shouldBe true
      }
      val missed =
        (0 until LINES).filter { line ->
          val baseline = FIRST_BASELINE + line * ADVANCE
          baseline + REACH_DOWN + INK_MARGIN > top && baseline - REACH_UP - INK_MARGIN < bottom && line !in range
        }
      withClue("the tile from row $top left out lines ${missed.take(SHOWN)} that reach it") {
        missed.isEmpty() shouldBe true
      }
    }
    withClue("the tiles took $taken lines of $LINES") { (taken <= 2L * LINES) shouldBe true }
  }

  @Test
  fun textThatOverhangsItsLinesDrawsTheSameInSmallTiles() {
    val whole = rasterOf(TEXT_TILE_SIDE)
    withClue("${whole.size} is one tile") {
      (whole.size.width <= TEXT_TILE_SIDE && whole.size.height <= TEXT_TILE_SIDE) shouldBe true
    }
    withClue("the overhanging text drew nothing") { whole.samples.any { it != 0.toShort() } shouldBe true }

    for (tileSide in SEAM_TILE_SIDES) {
      withClue("the text drawn in $tileSide pixel tiles") { rasterOf(tileSide).samples shouldBe whole.samples }
    }
  }

  @Test
  fun aHundredThousandLinesDrawInkInTheFirstMiddleAndLastTiles() {
    val text = List(LINES) { "i" }.joinToString("\n")

    val raster =
      rasterizeText(
        text,
        LONG_STYLE,
        FRAME,
        Int.MAX_VALUE,
      ).shouldBeInstanceOf<TextRaster.Drawn<LaidOutText>>().pixels

    val (width, height) = raster.size.width to raster.size.height
    val tops = (0 until height step TEXT_TILE_SIDE).toList()
    withClue("the text stands $height tall") { (tops.size > 2) shouldBe true }
    for (top in listOf(tops.first(), tops[tops.size / 2], tops.last())) {
      val rows = top until minOf(top + TEXT_TILE_SIDE, height)
      val inked = rows.any { y -> (0 until width).any { raster.samples[(y * width + it) * CHANNELS + ALPHA] != ZERO } }
      withClue("the tile from row $top holds no text") { inked shouldBe true }
    }
  }

  private fun rasterOf(tileSide: Int): LaidOutText {
    val drawn = rasterizeText(OVERHANGING, SEAM_STYLE, FRAME, Int.MAX_VALUE, tileSide = tileSide)
    return withClue("the text in $tileSide pixel tiles") {
      drawn.shouldBeInstanceOf<TextRaster.Drawn<LaidOutText>>()
    }.pixels
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

package dev.jordond.filmstrip.effects.filters.pixel

import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The pixel signal's grid, dither and colour cuts against their definitions.
 */
class PixelSignalTest {
  @Test
  fun theColumnsAreTheNearestWholeCountToSquareCells() {
    PixelSignal.columns(1920, 1080, 144) shouldBe 256
    PixelSignal.columns(1366, 768, 90) shouldBe 160
    PixelSignal.columns(1080, 1920, 144) shouldBe 81
    PixelSignal.columns(8, 1080, 8) shouldBe 1
  }

  @Test
  fun everyPixelSitsInTheCellThatSpansIt() {
    listOf(1080 to 144, 768 to 90, 360 to 144, 534 to 377).forEach { (size, count) ->
      (0 until size).forEach { pixel ->
        val cell = PixelSignal.cellOf(pixel, count, size)
        val centre = (pixel + 0.5) / size
        withClue("$size pixels, $count cells, pixel $pixel") {
          centre shouldBeGreaterThan cell.toDouble() / count - EDGE
          centre shouldBeLessThan (cell + 1.0) / count
        }
      }
    }
  }

  @Test
  fun aCellReadsThePixelUnderItsCentre() {
    listOf(1080 to 144, 768 to 90, 360 to 144, 1080 to 135).forEach { (size, count) ->
      (0 until count).forEach { cell ->
        val centre = (cell + 0.5) * size / count
        val pixel = PixelSignal.centreOf(cell, count, size)
        withClue("$size pixels, $count cells, cell $cell") {
          centre shouldBeGreaterThan pixel - EDGE
          centre shouldBeLessThan pixel + 1.0
        }
      }
    }
  }

  @Test
  fun aCentreOnAPixelEdgeReadsThePixelAfterIt() {
    // Eight pixels a cell puts every centre on the edge between the fourth and fifth pixel of the cell.
    PixelSignal.centreOf(0, 135, 1080) shouldBe 4
    PixelSignal.centreOf(1, 135, 1080) shouldBe 12
  }

  @Test
  fun moreCellsThanPixelsStillReadInsideTheFrame() {
    val pixels = (0 until Pixel.MAX_CELLS).map { PixelSignal.centreOf(it, Pixel.MAX_CELLS, 360) }

    pixels.min() shouldBe 0
    pixels.max() shouldBe 359
  }

  @Test
  fun theBayerThresholdsAverageToZeroOverEveryBlock() {
    listOf(0 to 0, 4 to 8, 13 to 6).forEach { (left, top) ->
      val thresholds = (0 until 16).map { PixelSignal.bayer(left + it % 4, top + it / 4) }
      thresholds.sum() shouldBe (0.0 plusOrMinus TOLERANCE)
      thresholds.toSet().size shouldBe 16
      thresholds.max() shouldBeLessThan 0.5
      thresholds.min() shouldBeGreaterThan -0.5
    }
  }

  @Test
  fun quantiseCutsAMidCodeToItsNearestStep() {
    PixelSignal.quantise(0.4, 5, 0.0) shouldBe 0.5
    PixelSignal.quantise(0.3, 5, 0.0) shouldBe 0.25
    PixelSignal.quantise(0.3, 5, 0.3) shouldBe 0.5
    PixelSignal.quantise(0.3, 5, -0.3) shouldBe 0.25
  }

  @Test
  fun quantiseRoundsAHalfUp() {
    PixelSignal.quantise(0.375, 5, 0.0) shouldBe 0.5
    PixelSignal.quantise(0.125, 5, 0.0) shouldBe 0.25
  }

  @Test
  fun aFullDitherKeepsEveryCodeInRange() {
    val offsets = (0 until 16).map { PixelSignal.bayer(it % 4, it / 4) }
    listOf(0.0, 1.0).forEach { code ->
      offsets.forEach { offset ->
        PixelSignal.quantise(code, 4, offset) shouldBe code
      }
    }
  }

  @Test
  fun nearestTakesTheClosestEntryAndTheFirstOnATie() {
    val palette = listOf(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(1.0, 1.0, 1.0), doubleArrayOf(0.5, 0.0, 0.0))

    PixelSignal.nearest(doubleArrayOf(0.4, 0.05, 0.0), palette) shouldBe 2
    PixelSignal.nearest(doubleArrayOf(0.5, 0.5, 0.5), palette.take(2)) shouldBe 0
    PixelSignal.nearest(doubleArrayOf(0.5, 0.5, 0.5), palette.take(2).reversed()) shouldBe 0
  }

  @Test
  fun aPaletteReadsAsCodesOfItsBytesWithAlphaIgnored() {
    PixelSignal
      .codesOf(PixelPalette(listOf(0xFF336699.toInt(), 0x336699)))
      .forEach { it.toList() shouldBe listOf(0x33 / 255.0, 0x66 / 255.0, 0x99 / 255.0) }
  }

  @Test
  fun pixelAtReadsTheCellCentreAndCutsItWithTheCellsThreshold() {
    val pixel = Pixel(cells = 90, levels = 6, dither = 0.5f)
    val x = 700
    val y = 333
    val columns = PixelSignal.columns(WIDTH, HEIGHT, 90)
    val cellX = PixelSignal.cellOf(x, columns, WIDTH)
    val cellY = PixelSignal.cellOf(y, 90, HEIGHT)
    val read = picture(PixelSignal.centreOf(cellX, columns, WIDTH), PixelSignal.centreOf(cellY, 90, HEIGHT))

    val drawn = PixelSignal.pixelAt(x, y, WIDTH, HEIGHT, pixel, ::picture)

    drawn.toList() shouldBe read.map { PixelSignal.quantise(it, 6, 0.5 * PixelSignal.bayer(cellX, cellY)) }
  }

  @Test
  fun everyPixelInACellDrawsTheSame() {
    val pixel = Pixel(cells = 90, levels = 6, dither = 0.5f)
    val columns = PixelSignal.columns(WIDTH, HEIGHT, 90)
    val drawn = (0 until WIDTH).map { PixelSignal.pixelAt(it, 400, WIDTH, HEIGHT, pixel, ::picture).toList() }

    (0 until WIDTH).groupBy { PixelSignal.cellOf(it, columns, WIDTH) }.values.forEach { span ->
      span.map { drawn[it] }.toSet().size shouldBe 1
    }
  }

  @Test
  fun aPaletteEndsEveryPixelOnAnEntryMovedByTheDither() {
    val pixel = Pixel(cells = 90, dither = 0.5f, palette = PixelPalette.GameBoy)
    val codes = PixelSignal.codesOf(PixelPalette.GameBoy)
    val shift = 0.5 * PixelSignal.bayer(2, 3) * PixelSignal.paletteSpread(codes.size)
    val rgb = doubleArrayOf(0.3, 0.45, 0.1)

    PixelSignal.colourOf(rgb, pixel, PixelSignal.bayer(2, 3)).toList() shouldBe
      codes[PixelSignal.nearest(DoubleArray(3) { rgb[it] + shift }, codes)].toList()
  }

  @Test
  fun aPaletteOverridesTheLevels() {
    val rgb = doubleArrayOf(0.3, 0.45, 0.1)

    PixelSignal.colourOf(rgb, Pixel(levels = 4, palette = PixelPalette.Cga), 0.0).toList() shouldBe
      PixelSignal.colourOf(rgb, Pixel(palette = PixelPalette.Cga), 0.0).toList()
  }

  @Test
  fun withNeitherLevelsNorAPaletteTheColourIsKept() {
    val rgb = doubleArrayOf(0.3, 0.45, 0.1)

    PixelSignal.colourOf(rgb, Pixel(dither = 1f), 0.4).toList() shouldBe rgb.toList()
  }

  private fun picture(
    x: Int,
    y: Int,
  ): DoubleArray = doubleArrayOf(x / WIDTH.toDouble(), y / HEIGHT.toDouble(), (x + y) % 255 / 255.0)
}

private const val WIDTH = 1366
private const val HEIGHT = 768
private const val TOLERANCE = 1e-12

// Room for a centre exactly on an edge, which belongs to the cell or pixel after it.
private const val EDGE = 1e-12

package dev.jordond.filmstrip.effects.filters.crt

import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.test.Test

/**
 * The CRT signal against a numeric integral of its own pattern, and the curve against its formula.
 */
class CrtSignalTest {
  @Test
  fun aScanlineIsTheMeanOfItsPatternOverAPixelAtEveryHeight() {
    val depth = CrtParams.at(1.0).scanlineDepth
    listOf(1080, 720, 534, 360).forEach { rows ->
      val footprint = 1.0 / rows
      val pointwise = { y: Double -> CrtSignal.scanlineAt(y, 0.0, depth) }
      var worst = 0.0
      var strongest = 0.0
      (0 until rows step ROW_STRIDE).forEach { row ->
        val y = (row + 0.5) / rows
        worst = max(worst, abs(CrtSignal.scanlineAt(y, footprint, depth) - mean(y, footprint, pointwise)))
        strongest = max(strongest, abs(pointwise(y) - mean(y, footprint, pointwise)))
      }
      withClue("$rows rows") {
        worst shouldBeLessThan INTEGRAL_TOLERANCE
        strongest shouldBeGreaterThan INTEGRAL_TOLERANCE * MUCH_LARGER
      }
    }
  }

  @Test
  fun theMaskIsTheMeanOfItsPatternOverAPixelInEachChannelAtEveryWidth() {
    val depth = CrtParams.at(1.0).maskDepth
    listOf(1920, 1366, 1280, 640).forEach { columns ->
      val footprint = 1.0 / columns
      for (channel in 0 until CHANNELS) {
        val pointwise = { x: Double -> CrtSignal.maskAt(x, channel, 0.0, depth) }
        var worst = 0.0
        var strongest = 0.0
        (0 until columns step ROW_STRIDE).forEach { column ->
          val x = (column + 0.5) / columns
          worst = max(worst, abs(CrtSignal.maskAt(x, channel, footprint, depth) - mean(x, footprint, pointwise)))
          strongest = max(strongest, abs(pointwise(x) - mean(x, footprint, pointwise)))
        }
        withClue("$columns columns, channel $channel") {
          worst shouldBeLessThan INTEGRAL_TOLERANCE
          strongest shouldBeGreaterThan INTEGRAL_TOLERANCE * MUCH_LARGER
        }
      }
    }
  }

  @Test
  fun aFootprintOfWholeCyclesAveragesThePatternAway() {
    val depth = CrtParams.at(1.0).scanlineDepth
    val footprint = 1.0 / CrtSignal.LINES

    CrtSignal.footprintAverage(CrtSignal.LINES, footprint) shouldBe (0.0 plusOrMinus 1e-12)
    CrtSignal.scanlineAt(CREST_FREE_Y, footprint, depth) shouldBe (1.0 - depth / 2.0 plusOrMinus 1e-12)
  }

  @Test
  fun theScanlinesReadTheSameFromEitherEndOfTheFrame() {
    val depth = CrtParams.at(1.0).scanlineDepth

    listOf(0.1, 0.37, 0.61).forEach { y ->
      val fromBelow = CrtSignal.scanlineAt(1.0 - y, 1.0 / 1080, depth)
      CrtSignal.scanlineAt(y, 1.0 / 1080, depth) shouldBe (fromBelow plusOrMinus 1e-9)
    }
  }

  @Test
  fun eachChannelsStripeSitsAThirdOfATriadAlongFromTheLast() {
    val depth = CrtParams.at(1.0).maskDepth
    val triad = 1.0 / CrtSignal.TRIADS

    for (channel in 0 until CHANNELS) {
      val crest = (TRIADS_IN + channel / 3.0) * triad
      CrtSignal.maskAt(crest, channel, 0.0, depth) shouldBe (1.0 plusOrMinus 1e-9)
    }
    CrtSignal.maskAt(TRIADS_IN * triad, 1, 0.0, depth) shouldBeLessThan 1.0 - depth / 2.0
  }

  @Test
  fun theCurveFixesTheCentreAndMovesAMidRadiusByItsFormula() {
    val k = CrtParams.at(1.0).barrelK
    val centre = CrtSignal.barrelMap(0.5, 0.5, 1920, 1080, k)
    centre[0] shouldBe 0.5
    centre[1] shouldBe 0.5

    // Half way out along the long side sits at a squared distance of a quarter.
    val across = CrtSignal.barrelMap(0.75, 0.5, 1920, 1080, k)
    across[0] shouldBe (0.5 + 0.25 * (1.0 + k * 0.25) plusOrMinus 1e-12)
    across[1] shouldBe 0.5

    // The same fraction down the short side is nearer, at 1080 over 1920 of it.
    val shortSide = 1080.0 / 1920.0
    val down = CrtSignal.barrelMap(0.5, 0.75, 1920, 1080, k)
    down[1] shouldBe (0.5 + 0.25 * (1.0 + k * 0.25 * shortSide * shortSide) plusOrMinus 1e-12)
  }

  @Test
  fun theCurveReadsTheSameOnAFrameTurnedOnItsSide() {
    val k = CrtParams.at(1.0).barrelK
    val wide = CrtSignal.barrelMap(0.2, 0.7, 1920, 1080, k)
    val tall = CrtSignal.barrelMap(0.7, 0.2, 1080, 1920, k)

    tall[0] shouldBe (wide[1] plusOrMinus 1e-12)
    tall[1] shouldBe (wide[0] plusOrMinus 1e-12)
  }

  @Test
  fun theCoverageIsFullOverThePictureAndNothingPastTheCurve() {
    val k = CrtParams.at(1.0).barrelK

    CrtSignal.coverageAt(0.5, 0.5, 1920, 1080, k) shouldBe 1.0
    CrtSignal.coverageAt(0.25, 0.75, 1920, 1080, k) shouldBe 1.0
    CrtSignal.coverageAt(0.0, 0.0, 1920, 1080, k) shouldBe 0.0
    CrtSignal.coverageAt(1.0, 1.0, 1366, 768, k) shouldBe 0.0
  }

  @Test
  fun aFlatFrameCoversItsOwnEdgePixelsInFull() {
    listOf(0.5 / 1920, 1919.5 / 1920).forEach { u ->
      CrtSignal.coverageAt(u, 0.5 / 1080, 1920, 1080, 0.0) shouldBe (1.0 plusOrMinus 1e-9)
    }
  }

  @Test
  fun theCoverageIsTheShareOfAPixelInsideTheFrameAcrossTheCurvesEdge() {
    val k = CrtParams.at(1.0).barrelK
    listOf(1920 to 1080, 1366 to 768, 1080 to 1920, 1280 to 720, 640 to 360).forEach { (width, height) ->
      var straddled = 0
      coverageReads(width, height).forEach { (column, row) ->
        val read = CrtSignal.coverageAt((column + 0.5) / width, (row + 0.5) / height, width, height, k)
        withClue("$width by $height at $column $row") {
          read shouldBe (shareInside(column, row, width, height, k) plusOrMinus COVERAGE_TOLERANCE)
        }
        if (read > 0.1 && read < 0.9) straddled++
      }
      withClue("$width by $height has to read pixels the edge crosses for this to tell") {
        (straddled > 0) shouldBe true
      }
    }
  }

  @Test
  fun aPixelTheEdgeCrossesFadesByItsShare() {
    val params = CrtParams.at(0.5)
    val codes = doubleArrayOf(0.2, 0.4, 0.6)
    val (column, row) =
      coverageReads(1366, 768).first { (column, row) ->
        CrtSignal.coverageAt((column + 0.5) / 1366, (row + 0.5) / 768, 1366, 768, params.barrelK) in 0.2..0.8
      }
    val u = (column + 0.5) / 1366
    val v = (row + 0.5) / 768
    val source = CrtSignal.barrelMap(u, v, 1366, 768, params.barrelK)

    val read = CrtSignal.pixelAt(u, v, 1366, 768, params) { _, _ -> codes }

    for (channel in 0 until CHANNELS) {
      val expected =
        codes[channel] * CrtSignal.scanlineAt(source[1], 1.0 / 768, params.scanlineDepth) *
          CrtSignal.maskAt(source[0], channel, 1.0 / 1366, params.maskDepth) *
          CrtSignal.vignetteAt(u, v, 1366, 768, params.vignette) * params.gain *
          CrtSignal.coverageAt(u, v, 1366, 768, params.barrelK)
      read[channel] shouldBe (expected plusOrMinus 1e-12)
    }
  }

  @Test
  fun theCurvesReachIsHowFarACornerMoves() {
    val k = CrtParams.at(1.0).barrelK

    val corner = CrtSignal.barrelMap(0.0, 0.0, 1366, 768, k)

    CrtSignal.curveReach(1366, 768, k) shouldBe (-corner[0] plusOrMinus 1e-12)
    CrtSignal.curveReach(1366, 768, k) shouldBe (-corner[1] plusOrMinus 1e-12)
  }

  @Test
  fun theFallOffIsFullInACornerAndHalfWayAtHalfTheSquaredDistance() {
    val strength = CrtParams.at(1.0).vignette

    CrtSignal.vignetteAt(0.5, 0.5, 1920, 1080, strength) shouldBe 1.0
    CrtSignal.vignetteAt(0.0, 1.0, 1920, 1080, strength) shouldBe (1.0 - strength plusOrMinus 1e-12)
    val half = 0.5 + 0.5 / sqrt(2.0)
    CrtSignal.vignetteAt(half, half, 1920, 1080, strength) shouldBe (1.0 - strength / 2.0 plusOrMinus 1e-12)
  }

  @Test
  fun everyStrengthIsLinearInTheIntensity() {
    val full = CrtParams.at(1.0)
    val half = CrtParams.at(0.5)

    half.scanlineDepth shouldBe (full.scanlineDepth / 2.0 plusOrMinus 1e-12)
    half.maskDepth shouldBe (full.maskDepth / 2.0 plusOrMinus 1e-12)
    half.barrelK shouldBe (full.barrelK / 2.0 plusOrMinus 1e-12)
    half.vignette shouldBe (full.vignette / 2.0 plusOrMinus 1e-12)
    half.gain - 1.0 shouldBe ((full.gain - 1.0) / 2.0 plusOrMinus 1e-12)
  }

  @Test
  fun zeroIntensityLeavesEveryPixelAlone() {
    val codes = doubleArrayOf(0.2, 0.4, 0.6)
    val params = CrtParams.at(0.0)

    val read = CrtSignal.pixelAt(0.03, 0.97, 1920, 1080, params) { _, _ -> codes }

    codes.indices.forEach { read[it] shouldBe (codes[it] plusOrMinus 1e-12) }
  }

  @Test
  fun aPixelIsTheProductOfTheParts() {
    val params = CrtParams.at(0.5)
    val codes = doubleArrayOf(0.2, 0.4, 0.6)
    val source = CrtSignal.barrelMap(0.3, 0.6, 1366, 768, params.barrelK)

    val read = CrtSignal.pixelAt(0.3, 0.6, 1366, 768, params) { _, _ -> codes }

    for (channel in 0 until CHANNELS) {
      val expected =
        codes[channel] * CrtSignal.scanlineAt(source[1], 1.0 / 768, params.scanlineDepth) *
          CrtSignal.maskAt(source[0], channel, 1.0 / 1366, params.maskDepth) *
          CrtSignal.vignetteAt(0.3, 0.6, 1366, 768, params.vignette) * params.gain
      read[channel] shouldBe (expected plusOrMinus 1e-12)
    }
  }

  @Test
  fun aPixelKeepsTheReadsAlphaAndLooksOnlyAtItsColour() {
    val params = CrtParams.at(0.5)
    val opaque = CrtSignal.pixelAt(0.3, 0.6, 1366, 768, params) { _, _ -> doubleArrayOf(0.2, 0.4, 0.6) }

    val read = CrtSignal.pixelAt(0.3, 0.6, 1366, 768, params) { _, _ -> doubleArrayOf(0.2, 0.4, 0.6, 0.5) }

    read[3] shouldBe (0.5 plusOrMinus 1e-12)
    for (channel in 0 until CHANNELS) read[channel] shouldBe (opaque[channel] plusOrMinus 1e-12)
  }

  @Test
  fun aPixelPastTheCurveIsBlack() {
    val white = doubleArrayOf(1.0, 1.0, 1.0)

    CrtSignal.pixelAt(0.0, 0.0, 1920, 1080, CrtParams.at(1.0)) { _, _ -> white }.toList() shouldBe listOf(0.0, 0.0, 0.0)
  }

  @Test
  fun theGainStopsAtFullScale() {
    val params = CrtParams.at(1.0)
    val unclamped =
      CrtSignal.scanlineAt(0.5, 1.0 / 1080, params.scanlineDepth) *
        CrtSignal.maskAt(0.5, 0, 1.0 / 1920, params.maskDepth) * params.gain
    withClue("the gain has to pass full scale at the centre for this to tell") {
      unclamped shouldBeGreaterThan 1.0
    }

    CrtSignal.pixelAt(0.5, 0.5, 1920, 1080, params) { _, _ -> doubleArrayOf(1.0, 1.0, 1.0) }[0] shouldBe 1.0
  }

  @Test
  fun theCurvesLocalStretchStaysInsideItsBoundAndCostsLittleOfThePatternItLeavesOut() {
    val params = CrtParams.at(1.0)
    val fit = CrtSignal.tubeFit(1920, 1080)
    val cornerRadius = fit[0] * fit[0] + fit[1] * fit[1]
    val acrossBound = 1.0 + params.barrelK * (cornerRadius + 2.0 * fit[0] * fit[0])
    val downBound = 1.0 + params.barrelK * (cornerRadius + 2.0 * fit[1] * fit[1])

    val stretch = worstStretch(1920, 1080, params.barrelK)
    stretch[0] shouldBeLessThan acrossBound + STRETCH_SLACK
    stretch[1] shouldBeLessThan downBound + STRETCH_SLACK

    listOf(1080, 768, 720, 534, CrtSignal.LINES, 360).forEach { rows ->
      val carried = CrtSignal.scanlineAt(0.0, 1.0 / rows, params.scanlineDepth)
      val stretched = CrtSignal.scanlineAt(0.0, downBound / rows, params.scanlineDepth)
      withClue("$rows rows") { abs(carried - stretched) shouldBeLessThan params.scanlineDepth * STRETCH_SHARE }
    }
    listOf(1920, 1366, 1280, 1024, CrtSignal.TRIADS, 640).forEach { columns ->
      val carried = CrtSignal.maskAt(0.0, 0, 1.0 / columns, params.maskDepth)
      val stretched = CrtSignal.maskAt(0.0, 0, acrossBound / columns, params.maskDepth)
      withClue("$columns columns") { abs(carried - stretched) shouldBeLessThan params.maskDepth * STRETCH_SHARE }
    }
  }

  // Every column along a few rows and every row along a few columns, so a read crosses each side and nears a corner.
  private fun coverageReads(
    width: Int,
    height: Int,
  ): List<Pair<Int, Int>> {
    val rows = listOf(height / 2, height / 3, height / 5, height / 9, height - height / 7)
    val columns = listOf(width / 2, width / 3, width / 5, width / 9, width - width / 7)
    return rows.flatMap { row -> (0 until width).map { it to row } } +
      columns.flatMap { column -> (0 until height).map { column to it } }
  }

  // The share of a grid of points over a pixel whose source lands inside the frame. A pixel whose corners all agree is
  // on one side of the edge, so only a pixel the edge crosses counts the whole grid.
  private fun shareInside(
    column: Int,
    row: Int,
    width: Int,
    height: Int,
    k: Double,
  ): Double {
    fun lands(
      across: Double,
      down: Double,
    ): Boolean {
      val source = CrtSignal.barrelMap((column + across) / width, (row + down) / height, width, height, k)
      return source[0] in 0.0..1.0 && source[1] in 0.0..1.0
    }

    val corner = lands(0.0, 0.0)
    if (lands(1.0, 0.0) == corner && lands(0.0, 1.0) == corner && lands(1.0, 1.0) == corner) {
      return if (corner) 1.0 else 0.0
    }
    var inside = 0
    for (i in 0 until COVERAGE_GRID) {
      for (j in 0 until COVERAGE_GRID) {
        if (lands((i + 0.5) / COVERAGE_GRID, (j + 0.5) / COVERAGE_GRID)) inside++
      }
    }
    return inside.toDouble() / (COVERAGE_GRID * COVERAGE_GRID)
  }

  private fun mean(
    at: Double,
    footprint: Double,
    pattern: (Double) -> Double,
  ): Double {
    val step = footprint / INTEGRAL_STEPS
    var sum = 0.0
    for (index in 0 until INTEGRAL_STEPS) sum += pattern(at - footprint / 2.0 + (index + 0.5) * step)
    return sum / INTEGRAL_STEPS
  }

  // The largest stretch of the source against the frame over a grid, across and then down.
  private fun worstStretch(
    width: Int,
    height: Int,
    k: Double,
  ): DoubleArray {
    var across = 0.0
    var down = 0.0
    for (i in 0..STRETCH_GRID) {
      for (j in 0..STRETCH_GRID) {
        val u = i.toDouble() / STRETCH_GRID
        val v = j.toDouble() / STRETCH_GRID
        val here = CrtSignal.barrelMap(u, v, width, height, k)
        val right = CrtSignal.barrelMap(u + STRETCH_DELTA, v, width, height, k)
        val below = CrtSignal.barrelMap(u, v + STRETCH_DELTA, width, height, k)
        across = max(across, (right[0] - here[0]) / STRETCH_DELTA)
        down = max(down, (below[1] - here[1]) / STRETCH_DELTA)
      }
    }
    return doubleArrayOf(across, down)
  }
}

private const val CHANNELS = 3
private const val ROW_STRIDE = 7
private const val INTEGRAL_STEPS = 2000
private const val INTEGRAL_TOLERANCE = 1e-6
private const val MUCH_LARGER = 1000.0
private const val CREST_FREE_Y = 0.123
private const val TRIADS_IN = 300
private const val COVERAGE_GRID = 40

// How close the ramp lands on a pixel's true share. It takes the edge as straight over one pixel, and the grid counting
// the true share is one part in 40 per side.
private const val COVERAGE_TOLERANCE = 0.05
private const val STRETCH_GRID = 40
private const val STRETCH_DELTA = 1e-6
private const val STRETCH_SLACK = 1e-3

// The most the unwarped footprint can put a corner off by, as a share of the pattern's full depth. The worst counts
// sit where a pixel holds most of a cycle. Measured at 0.12 on the mask at 1024 columns.
private const val STRETCH_SHARE = 0.125

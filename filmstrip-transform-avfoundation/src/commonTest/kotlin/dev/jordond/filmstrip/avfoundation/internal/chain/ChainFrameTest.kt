package dev.jordond.filmstrip.avfoundation.internal.chain

import dev.jordond.filmstrip.color.linearDimGain
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.transform.internal.geometry.coverScale
import dev.jordond.filmstrip.transform.internal.geometry.sigmaFor
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIColor
import platform.CoreImage.CIImage
import kotlin.math.abs
import kotlin.test.Test

/**
 * The Core Image steps a chain lays every frame through: the baked rotation, the fit into the output rect, the fill
 * behind it and the blend that paints the fill in after a grade.
 */
@OptIn(ExperimentalForeignApi::class)
class ChainFrameTest {
  @Test
  fun `a whole number of turns leaves the frame alone`() {
    val frame = solid(RED, 0.0, 0.0, 40.0, 20.0)

    frame.rotated(0) shouldBeSameInstanceAs frame
    frame.rotated(360) shouldBeSameInstanceAs frame
    frame.rotated(-360) shouldBeSameInstanceAs frame
  }

  @Test
  fun `a quarter turn swaps the sides and lands back at the origin`() {
    assertNear(solid(RED, 0.0, 0.0, 40.0, 20.0).rotated(90).rect(), listOf(0.0, 0.0, 20.0, 40.0))
    assertNear(solid(RED, 0.0, 0.0, 40.0, 20.0).rotated(270).rect(), listOf(0.0, 0.0, 20.0, 40.0))
  }

  @Test
  fun `a half turn mirrors the frame across its width`() {
    val turned = split(width = 40.0, height = 20.0, redWidth = 10.0).rotated(180)
    val pixels = turned.linearPixels(40, 20)

    assertNear(turned.rect(), listOf(0.0, 0.0, 40.0, 20.0))
    assertPixel(pixels.at(5, 10), BLUE, "the left edge after a half turn")
    assertPixel(pixels.at(35, 10), RED, "the right edge after a half turn")
  }

  @Test
  fun `contain shrinks a wide frame to the output width and centres it`() {
    val laid = split(width = 200.0, height = 100.0, redWidth = 50.0, x = 100.0, y = 50.0).laidInto(SQUARE, Fit.Contain)
    val pixels = laid.linearPixels(100, 100)

    assertNear(laid.rect(), listOf(0.0, 25.0, 100.0, 50.0))
    assertPixel(pixels.at(10, 50), RED, "the frame's red quarter")
    assertPixel(pixels.at(40, 50), BLUE, "the frame's blue remainder")
  }

  @Test
  fun `crop covers the output and cuts the sides of a wide frame`() {
    val laid = split(width = 200.0, height = 100.0, redWidth = 50.0).laidInto(SQUARE, Fit.Crop)
    val pixels = laid.linearPixels(100, 100)

    assertNear(laid.rect(), listOf(0.0, 0.0, 100.0, 100.0))
    assertPixel(pixels.at(2, 50), BLUE, "the left edge, once the red quarter is cropped away")
  }

  @Test
  fun `stretch fills the output on both axes`() {
    val laid = split(width = 200.0, height = 100.0, redWidth = 50.0).laidInto(SQUARE, Fit.Stretch)
    val pixels = laid.linearPixels(100, 100)

    assertNear(laid.rect(), listOf(0.0, 0.0, 100.0, 100.0))
    assertPixel(pixels.at(10, 50), RED, "the red quarter squeezed to a quarter of the output")
    assertPixel(pixels.at(40, 50), BLUE, "past the red quarter")
  }

  @Test
  fun `a fitted frame covers the whole output with the fill in its bars`() {
    val fitted = solid(GREEN, 0.0, 0.0, 50.0, 100.0).fittedTo(WIDE, Fit.Contain, Fill.Solid(RED_ARGB), transfer = null)
    val pixels = fitted.linearPixels(200, 100)

    assertNear(fitted.rect(), listOf(0.0, 0.0, 200.0, 100.0))
    assertPixel(pixels.at(30, 50), RED, "the left bar")
    assertPixel(pixels.at(100, 50), GREEN, "the frame")
    assertPixel(pixels.at(170, 50), RED, "the right bar")
  }

  @Test
  fun `the blend shows the background wherever the mask is clear`() {
    val graded = solid(GREEN, 0.0, 0.0, 100.0, 100.0)
    val mask = solid(BLUE, 50.0, 0.0, 50.0, 100.0)
    val background = CIImage(color = ciColor(RED))

    val blended = graded.over(background, mask, SQUARE)
    val pixels = blended.linearPixels(100, 100)

    assertNear(blended.rect(), listOf(0.0, 0.0, 100.0, 100.0))
    assertPixel(pixels.at(20, 50), RED, "outside the mask")
    assertPixel(pixels.at(80, 50), GREEN, "inside the mask")
  }

  @Test
  fun `a solid fill paints its own color and a blurred one the frame's`() {
    val frame = solid(BLUE, 0.0, 0.0, 64.0, 36.0)

    assertPixel(frame.fillImage(Fill.Solid(RED_ARGB), SQUARE, null).linearPixels(100, 100).at(50, 50), RED, "solid")
    assertPixel(frame.fillImage(Fill.Black, SQUARE, null).linearPixels(100, 100).at(50, 50), BLACK, "black")
    assertPixel(frame.fillImage(Fill.Blurred(), SQUARE, null).linearPixels(100, 100).at(50, 50), BLUE, "blurred")
  }

  @Test
  fun `a blurred cover reaches every edge of the output without darkening`() {
    val covered = solid(RED, 0.0, 0.0, 64.0, 36.0).blurredCover(SQUARE, Fill.Blurred(radius = 0.04f))
    val pixels = covered.linearPixels(100, 100)

    val (x, y, width, height) = covered.rect()
    withClue("the cover left a gap") {
      (x <= 0.0 && y <= 0.0 && x + width >= 100.0 && y + height >= 100.0).shouldBeTrue()
    }
    assertPixel(pixels.at(50, 50), RED, "the centre")
    assertPixel(pixels.at(0, 0), RED, "a corner")
    assertPixel(pixels.at(99, 50), RED, "the right edge")
  }

  @Test
  fun `a dimmed blurred cover carries the shared linear gain`() {
    listOf(0.25f, 0.5f).forEach { dim ->
      val covered = solid(WHITE, 0.0, 0.0, 64.0, 36.0).blurredCover(SQUARE, Fill.Blurred(dim = dim))
      val gain = linearDimGain(dim)

      assertPixel(covered.linearPixels(100, 100).at(50, 50), listOf(gain, gain, gain, 1f), "dimmed by $dim")
    }
  }

  @Test
  fun `a frame with nothing in it covers with black`() {
    val covered = CIImage.emptyImage().blurredCover(SQUARE, Fill.Blurred())

    assertPixel(covered.linearPixels(100, 100).at(50, 50), BLACK, "an empty frame's cover")
  }

  // The edge sits off the centre the cover is cropped about, so a cover drawn at any other scale moves it, and the
  // radius is mid-range, where a blur of the wrong size spreads it measurably wider or narrower. Red falls to the
  // Gaussian's one-sigma tails one sigma either side of the edge.
  @Test
  fun `a blurred cover spreads an edge by the shared sigma at the shared cover scale`() {
    val source = Size(64, 32)
    val fill = Fill.Blurred(radius = 0.06f)
    val covered = split(width = 64.0, height = 32.0, redWidth = EDGE).blurredCover(SQUARE, fill)
    val pixels = covered.linearPixels(100, 100)
    val red = (0 until 100).map { pixels.at(it, 50)[0] }

    val scale = coverScale(source, SQUARE)
    val edge = EDGE * scale - (source.width * scale - SQUARE.width) / 2
    val sigma = fill.sigmaFor(SQUARE)
    val landed = red.crossing(0.5f)
    val spread = (red.crossing(ONE_SIGMA_LOW) - red.crossing(ONE_SIGMA_HIGH)) / 2

    assertNear(covered.rect(), listOf(0.0, 0.0, 100.0, 100.0))
    withClue("the edge landed at $landed, not $edge") { (abs(landed - edge) < 0.5).shouldBeTrue() }
    withClue("the edge spread by $spread, not $sigma") { (abs(spread - sigma) < sigma * 0.1).shouldBeTrue() }
  }

  @Test
  fun `dimming scales color by the shared linear gain and keeps alpha`() {
    listOf(0.25f, 0.5f, 0.75f).forEach { dim ->
      val gain = linearDimGain(dim)
      val pixel = solid(WHITE, 0.0, 0.0, 10.0, 10.0).dimmed(dim).linearPixels(10, 10).at(5, 5)

      assertPixel(pixel, listOf(gain, gain, gain, 1f), "white dimmed by $dim")
    }
  }

  @Test
  fun `a cleared frame keeps its extent and draws nothing`() {
    val cleared = solid(RED, 5.0, 5.0, 30.0, 20.0).cleared()

    assertNear(cleared.rect(), listOf(5.0, 5.0, 30.0, 20.0))
    assertPixel(cleared.linearPixels(40, 30).at(20, 15), listOf(0f, 0f, 0f, 0f), "a cleared pixel")
  }

  @Test
  fun `a frame in negative space is moved back to the origin`() {
    assertNear(solid(RED, -30.0, -20.0, 40.0, 10.0).atOrigin().rect(), listOf(0.0, 0.0, 40.0, 10.0))
  }

  private fun solid(
    color: List<Float>,
    x: Double,
    y: Double,
    width: Double,
    height: Double,
  ): CIImage = CIImage(color = ciColor(color)).imageByCroppingToRect(CGRectMake(x, y, width, height))

  // A red band redWidth wide down the left of a blue frame.
  private fun split(
    width: Double,
    height: Double,
    redWidth: Double,
    x: Double = 0.0,
    y: Double = 0.0,
  ): CIImage = solid(RED, x, y, redWidth, height).imageByCompositingOverImage(solid(BLUE, x, y, width, height))

  // Where red first falls to level along a row, read between the centres of the two pixels either side of it.
  private fun List<Float>.crossing(level: Float): Double {
    val after = (1 until size).first { this[it] <= level }
    val before = this[after - 1]
    return after - 0.5 + (before - level) / (before - this[after])
  }

  private fun ciColor(color: List<Float>): CIColor =
    CIColor.colorWithRed(color[0].toDouble(), green = color[1].toDouble(), blue = color[2].toDouble(), alpha = 1.0)

  private companion object {
    val RED = listOf(1f, 0f, 0f, 1f)
    val GREEN = listOf(0f, 1f, 0f, 1f)
    val BLUE = listOf(0f, 0f, 1f, 1f)
    val WHITE = listOf(1f, 1f, 1f, 1f)
    val BLACK = listOf(0f, 0f, 0f, 1f)
    const val RED_ARGB = 0xFFFF0000.toInt()
    val SQUARE = Size(100, 100)
    val WIDE = Size(200, 100)
    const val EDGE = 24.0
    const val ONE_SIGMA_HIGH = 0.8413f
    const val ONE_SIGMA_LOW = 0.1587f
  }
}

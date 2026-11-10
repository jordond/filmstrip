package dev.jordond.filmstrip.avfoundation.internal.chain

import dev.jordond.filmstrip.color.HDR_REFERENCE_WHITE_NITS
import dev.jordond.filmstrip.color.blurPlane
import dev.jordond.filmstrip.color.linearDimGain
import dev.jordond.filmstrip.color.sdrSignalFromNits
import dev.jordond.filmstrip.effects.color.createCodeSpace
import dev.jordond.filmstrip.effects.color.inCodesOf
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.KenBurns
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.transform.internal.geometry.blendOverFill
import dev.jordond.filmstrip.transform.internal.geometry.blurPlan
import dev.jordond.filmstrip.transform.internal.geometry.coverScale
import dev.jordond.filmstrip.transform.internal.geometry.placeIn
import dev.jordond.filmstrip.transform.internal.geometry.placementOf
import dev.jordond.filmstrip.transform.internal.geometry.sigmaFor
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIColor
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.CoreImage.CIVector
import platform.CoreImage.createCGImage
import kotlin.math.abs
import kotlin.test.Test

/**
 * The Core Image steps a chain lays every frame through: the baked rotation, the canvas a crop builds, the placement in
 * the output rect, the fill behind it and the blend that paints the fill in after a grade.
 */
@OptIn(ExperimentalForeignApi::class)
class ChainFrameHardwareTest {
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
    val rect = placeIn(Size(200, 100), SQUARE, Fit.Contain)
    val laid = split(width = 200.0, height = 100.0, redWidth = 50.0, x = 100.0, y = 50.0).laidAt(SQUARE, rect)
    val pixels = laid.linearPixels(100, 100)

    assertNear(laid.rect(), rect.inCoreImage(SQUARE))
    assertPixel(pixels.at(10, 50), RED, "the frame's red quarter")
    assertPixel(pixels.at(40, 50), BLUE, "the frame's blue remainder")
  }

  @Test
  fun `crop covers the output and cuts the sides of a wide frame`() {
    val laid =
      split(
        width = 200.0,
        height = 100.0,
        redWidth = 50.0,
      ).laidAt(SQUARE, placeIn(Size(200, 100), SQUARE, Fit.Crop))
    val pixels = laid.linearPixels(100, 100)

    assertNear(laid.rect(), listOf(0.0, 0.0, 100.0, 100.0))
    assertPixel(pixels.at(2, 50), BLUE, "the left edge, once the red quarter is cropped away")
  }

  @Test
  fun `stretch fills the output on both axes`() {
    val laid =
      split(
        width = 200.0,
        height = 100.0,
        redWidth = 50.0,
      ).laidAt(SQUARE, placeIn(Size(200, 100), SQUARE, Fit.Stretch))
    val pixels = laid.linearPixels(100, 100)

    assertNear(laid.rect(), listOf(0.0, 0.0, 100.0, 100.0))
    assertPixel(pixels.at(10, 50), RED, "the red quarter squeezed to a quarter of the output")
    assertPixel(pixels.at(40, 50), BLUE, "past the red quarter")
  }

  // Four by five into 1366 by 768 lands its sides between whole pixels. Core Image reports the extent as the whole
  // pixels the frame touches, so what is read is how much of a row the frame covers, which a rect rounded on its way
  // in moves by at least 0.4 of a pixel.
  @Test
  fun `a frame is laid at the shared rect with no rounding`() {
    val rect = placeIn(PORTRAIT, MID_RANGE, Fit.Contain)
    val pixels =
      solid(
        GREEN,
        0.0,
        0.0,
        40.0,
        50.0,
      ).laidAt(MID_RANGE, rect).linearPixels(MID_RANGE.width, MID_RANGE.height)

    val covered = (0 until MID_RANGE.width).sumOf { pixels.at(it, MID_RANGE.height / 2)[1].toDouble() }
    val expected = rect.width * MID_RANGE.width.toDouble()
    withClue("the frame covered $covered pixels of a row, not $expected") {
      (abs(covered - expected) < COVERAGE_SLACK).shouldBeTrue()
    }
  }

  @Test
  fun `a fitted frame covers the whole output with the fill in its bars`() {
    val fitted =
      solid(GREEN, 0.0, 0.0, 50.0, 100.0)
        .fittedTo(
          WIDE,
          placeIn(Size(50, 100), WIDE, Fit.Contain),
          Fill.Solid(RED_ARGB),
          transfer = null,
          colorSpace = ColorSpace.Bt709,
        )
    val pixels = fitted.linearPixels(200, 100)

    assertNear(fitted.rect(), listOf(0.0, 0.0, 200.0, 100.0))
    assertPixel(pixels.at(30, 50), RED, "the left bar")
    assertPixel(pixels.at(100, 50), GREEN, "the frame")
    assertPixel(pixels.at(170, 50), RED, "the right bar")
  }

  @Test
  fun `a canvas is as tall as the frame and holds it where the placement puts it`() {
    val crop = Crop(AspectRatio(16, 9), Fit.Contain)
    val placement =
      placementOf(PORTRAIT, listOf(listOf(crop, KenBurns(from = KEN_BURNS_FROM, to = NormalizedRect.Full))), WIDE)
        .canvases
        .single()
    val canvasWidth = PORTRAIT.height * placement.aspect.toDouble()
    val content = placement.contentInCanvas

    val canvas = solid(GREEN, 0.0, 0.0, 40.0, 50.0).inCanvas(placement, ciColor(RED))
    val pixels = canvas.linearPixels(canvasWidth.toInt(), PORTRAIT.height)

    assertNear(canvas.rect(), listOf(0.0, 0.0, canvasWidth, PORTRAIT.height.toDouble()), tolerance = FLOAT_SLACK)
    assertPixel(pixels.at((canvasWidth * content.left / 2).toInt(), 25), RED, "the left bar")
    assertPixel(pixels.at((canvasWidth * (content.left + content.right) / 2).toInt(), 25), GREEN, "the frame")
    assertPixel(pixels.at((canvasWidth * (1 + content.right) / 2).toInt(), 25), RED, "the right bar")
  }

  @Test
  fun `a clear canvas leaves its bars for the fill`() {
    val crop = Crop(AspectRatio(16, 9), Fit.Contain)
    val placement =
      placementOf(PORTRAIT, listOf(listOf(crop, KenBurns(from = KEN_BURNS_FROM, to = NormalizedRect.Full))), WIDE)
        .canvases
        .single()
    val canvasWidth = PORTRAIT.height * placement.aspect.toDouble()

    val canvas = solid(GREEN, 0.0, 0.0, 40.0, 50.0).inCanvas(placement, CIColor.clearColor)

    assertPixel(canvas.linearPixels(canvasWidth.toInt(), PORTRAIT.height).at(1, 25), listOf(0f, 0f, 0f, 0f), "a bar")
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

    assertPixel(
      frame.fillImage(Fill.Solid(RED_ARGB), SQUARE, null, ColorSpace.Bt709).linearPixels(100, 100).at(50, 50),
      RED,
      "solid",
    )
    assertPixel(
      frame.fillImage(Fill.Black, SQUARE, null, ColorSpace.Bt709).linearPixels(100, 100).at(50, 50),
      BLACK,
      "black",
    )
    assertPixel(
      frame.fillImage(Fill.Blurred(), SQUARE, null, ColorSpace.Bt709).linearPixels(100, 100).at(50, 50),
      BLUE,
      "blurred",
    )
  }

  @Test
  fun `a blurred cover reaches every edge of the output without darkening`() {
    val covered =
      solid(
        RED,
        0.0,
        0.0,
        64.0,
        36.0,
      ).blurredCover(SQUARE, Fill.Blurred(radius = 0.04f), null, ColorSpace.Bt709)
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
      val covered =
        solid(
          WHITE,
          0.0,
          0.0,
          64.0,
          36.0,
        ).blurredCover(SQUARE, Fill.Blurred(dim = dim), null, ColorSpace.Bt709)
      val gain = linearDimGain(dim)

      assertPixel(covered.linearPixels(100, 100).at(50, 50), listOf(gain, gain, gain, 1f), "dimmed by $dim")
    }
  }

  // The cover crops this 64 by 32 frame at 16 and 48 pixels across it. Blue runs from just outside the left crop line
  // to an edge, so a blur held at the frame's edge takes in the red past the line and reads apart from one held at the
  // crop's. Read on blue against the shared plan run on the plain cover.
  @Test
  fun `a blurred cover lands on the shared plan mid-frame and at the cover crop edge`() {
    val frame =
      solid(BLUE, STRIP_FROM, 0.0, STRIP_TO - STRIP_FROM, 32.0)
        .imageByCompositingOverImage(solid(GREEN, STRIP_TO, 0.0, 48.0 - STRIP_TO, 32.0))
        .imageByCompositingOverImage(solid(RED, 0.0, 0.0, 64.0, 32.0))
    val fill = Fill.Blurred(radius = MID_RADIUS)
    val plan = fill.blurPlan(SQUARE)
    val sigma = plan.sigmaPx / plan.width
    val cover =
      frame
        .coverOnCodes(SQUARE)
        .codePixels(100, 100)
        .blue()
    val expected = blurPlane(cover, DoubleArray(cover.size) { 1.0 }, 100, 100, sigma)
    val drawn = frame.blurredCover(SQUARE, fill, null, ColorSpace.Bt709).codePixels(100, 100).blue()
    val scale = coverScale(Size(64, 32), SQUARE).toDouble()
    val whole =
      frame
        .imageByClampingToExtent()
        .imageByApplyingTransform(CGAffineTransformMakeScale(scale, scale))
        .imageByCroppingToRect(CGRectMake(0.0, 0.0, 64.0 * scale, 100.0))
        .codePixels(WHOLE_WIDTH, 100)
        .blue()
    val heldAtFrame = blurPlane(whole, DoubleArray(whole.size) { 1.0 }, WHOLE_WIDTH, 100, sigma * 100 / WHOLE_WIDTH)
    val row = 50 * 100
    val step = ((STRIP_TO - 16.0) * scale).toInt()

    val apart = abs(heldAtFrame[50 * WHOLE_WIDTH + (WHOLE_WIDTH - 100) / 2] - expected[row])
    withClue("a blur held at the frame's edge lands $apart from one held at the crop's, too close to tell") {
      (apart > 2 * BLUR_LEVEL).shouldBeTrue()
    }
    val spread = (row until row + 100).maxOf { abs(expected[it] - cover[it]) }
    withClue("the shared blur moves the strip's edge by $spread, so the read is not of a sharp frame") {
      (spread > 10 * BLUR_LEVEL).shouldBeTrue()
    }
    val worst = (row until row + 100).maxBy { abs(drawn[it] - expected[it]) }
    withClue("the cover is ${abs(drawn[worst] - expected[worst])} off the shared plan at ${worst - row}") {
      (abs(drawn[worst] - expected[worst]) <= BLUR_LEVEL).shouldBeTrue()
    }
  }

  @Test
  fun `a frame with nothing in it covers with black`() {
    val covered = CIImage.emptyImage().blurredCover(SQUARE, Fill.Blurred(), null, ColorSpace.Bt709)

    assertPixel(covered.linearPixels(100, 100).at(50, 50), BLACK, "an empty frame's cover")
  }

  // The edge sits off the centre the cover is cropped about, so a cover drawn at any other scale moves it, and the
  // radius is mid-range, where a blur of the wrong size spreads it measurably wider or narrower. Red falls to the
  // Gaussian's one-sigma tails one sigma either side of the edge.
  @Test
  fun `a blurred cover spreads an edge by the shared sigma at the shared cover scale`() {
    val source = Size(64, 32)
    val fill = Fill.Blurred(radius = 0.06f)
    val frame = split(width = 64.0, height = 32.0, redWidth = EDGE).materialised()
    val covered = frame.blurredCover(SQUARE, fill, null, ColorSpace.Bt709)
    val pixels = covered.codePixels(100, 100)
    val red = (0 until 100).map { pixels.at(it, 50)[0] }

    val scale = coverScale(source, SQUARE)
    val placed = EDGE * scale - (source.width * scale - SQUARE.width) / 2
    val sigma = fill.sigmaFor(SQUARE)
    val plan = fill.blurPlan(SQUARE)
    val plain = frame.coverOnCodes(SQUARE).codePixels(100, 100).red()
    val shared = blurPlane(plain, DoubleArray(plain.size) { 1.0 }, 100, 100, plan.sigmaPx / plan.width)
    val edge = (0 until 100).map { shared[50 * 100 + it].toFloat() }.crossing(0.5f)
    val landed = red.crossing(0.5f)
    val spread = (red.crossing(ONE_SIGMA_LOW) - red.crossing(ONE_SIGMA_HIGH)) / 2

    assertNear(covered.rect(), listOf(0.0, 0.0, 100.0, 100.0))
    withClue("the edge sits at $edge, not $placed") { (abs(edge - placed) < 0.5).shouldBeTrue() }
    withClue("the edge landed at $landed, not $edge on the shared plan") { (abs(landed - edge) < 0.5).shouldBeTrue() }
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

  // This rect in a frame of size, as the x, y, width and height Core Image measures from the bottom left.
  private fun NormalizedRect.inCoreImage(size: Size): List<Double> =
    listOf(
      left * size.width.toDouble(),
      (1 - bottom) * size.height.toDouble(),
      width * size.width.toDouble(),
      height * size.height.toDouble(),
    )

  private fun ciColor(color: List<Float>): CIColor =
    CIColor.colorWithRed(color[0].toDouble(), green = color[1].toDouble(), blue = color[2].toDouble(), alpha = 1.0)

  private companion object {
    val RED = listOf(1f, 0f, 0f, 1f)
    val GREEN = listOf(0f, 1f, 0f, 1f)
    val BLUE = listOf(0f, 0f, 1f, 1f)
    val WHITE = listOf(1f, 1f, 1f, 1f)
    val BLACK = listOf(0f, 0f, 0f, 1f)
    const val RED_ARGB = 0xFFFF0000.toInt()

    // A sigma of 12 pixels on the square, which the plan boxes down by 2 onto whole pixels.
    const val MID_RADIUS = 0.12f
    const val STRIP_FROM = 14.0
    const val STRIP_TO = 24.0
    const val WHOLE_WIDTH = 200

    // Two eight bit codes, as a GaussianBlur on Core Image is held to.
    const val BLUR_LEVEL = 2.0 / 255.0

    val SQUARE = Size(100, 100)
    val WIDE = Size(200, 100)
    val PORTRAIT = Size(40, 50)
    val MID_RANGE = Size(1366, 768)
    const val FLOAT_SLACK = 1e-3
    const val COVERAGE_SLACK = 0.1
    val KEN_BURNS_FROM = NormalizedRect(0f, 0f, 0.5f, 0.5f)
    const val EDGE = 24.0
    const val ONE_SIGMA_HIGH = 0.8413f
    const val ONE_SIGMA_LOW = 0.1587f
  }
}

// The pixels as the codes of the frame's SDR space, which is what the SDR cover is blurred on.
internal fun CIImage.codePixels(
  width: Int,
  height: Int,
): LinearPixels {
  var codes = this
  inCodesOf(ColorSpace.Bt709) { toCodes ->
    codes = toCodes(this)
    this
  }
  return codes.linearPixels(width, height)
}

// The cover scaled on the codes of the frame's SDR space, the way the SDR cover is drawn before its blur.
private fun CIImage.coverOnCodes(size: Size): CIImage =
  inCodesOf(ColorSpace.Bt709) { toCodes -> toCodes(this).coverOf(size).shouldNotBeNull() }

// This image as the pixels of a decoded frame, so a step on it reads a frame and not a graph of constant colours.
@OptIn(ExperimentalForeignApi::class)
private fun CIImage.materialised(): CIImage {
  val image = CIContext().createCGImage(this, fromRect = extent).shouldNotBeNull()
  try {
    return CIImage(image)
  } finally {
    CGImageRelease(image)
  }
}

private fun LinearPixels.blue(): DoubleArray = DoubleArray(width * height) { at(it % width, it / width)[2].toDouble() }

internal fun LinearPixels.red(): DoubleArray = DoubleArray(width * height) { at(it % width, it / width)[0].toDouble() }

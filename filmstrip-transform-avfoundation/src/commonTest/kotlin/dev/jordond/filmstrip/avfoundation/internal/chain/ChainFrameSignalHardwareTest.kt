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
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIColor
import platform.CoreImage.CIImage
import platform.CoreImage.CIVector
import kotlin.math.abs
import kotlin.test.Test

/**
 * The chain's steps read on a signal rather than on light: the blurred cover of a kept HDR grade on its SDR signal, and
 * a half transparent frame blended over the fill on the stored codes.
 */
@OptIn(ExperimentalForeignApi::class)
class ChainFrameSignalHardwareTest {
  // The cover of a frame on a kept PQ grade, a bright strip over a dim base, blurred on the SDR signal. The strip
  // starts just outside the left crop line, so a blur held at the frame's edge reads apart from one held at the crop's.
  @Test
  fun `a blurred cover on a kept PQ grade lands on the shared plan on the sdr signal`() {
    val frame =
      grey(BRIGHT_LIGHT, STRIP_FROM, 0.0, STRIP_TO - STRIP_FROM, 32.0)
        .imageByCompositingOverImage(grey(DIM_LIGHT, 0.0, 0.0, 64.0, 32.0))
    val fill = Fill.Blurred(radius = KEPT_RADIUS, dim = 0f)
    val plan = fill.blurPlan(SQUARE)
    val sigma = plan.sigmaPx / plan.width
    val cover =
      frame
        .coverOf(SQUARE)
        .shouldNotBeNull()
        .linearPixels(100, 100)
        .red()
    val opaque = DoubleArray(cover.size) { 1.0 }
    val onSignal = blurPlane(DoubleArray(cover.size) { signalOf(cover[it]) }, opaque, 100, 100, sigma)
    val onLight = blurPlane(cover, opaque, 100, 100, sigma).map(::signalOf)
    val drawn =
      frame
        .blurredCover(SQUARE, fill, HdrTransfer.Pq, ColorSpace.Bt709)
        .linearPixels(100, 100)
        .red()
        .map(::signalOf)
    val scale = coverScale(Size(64, 32), SQUARE).toDouble()
    val whole =
      frame
        .imageByClampingToExtent()
        .imageByApplyingTransform(CGAffineTransformMakeScale(scale, scale))
        .imageByCroppingToRect(CGRectMake(0.0, 0.0, 64.0 * scale, 100.0))
        .linearPixels(WHOLE_WIDTH, 100)
        .red()
    val wholeSignal = DoubleArray(whole.size) { signalOf(whole[it]) }
    val heldAtFrame =
      blurPlane(
        wholeSignal,
        DoubleArray(whole.size) { 1.0 },
        WHOLE_WIDTH,
        100,
        sigma * 100 / WHOLE_WIDTH,
      )
    val row = 50 * 100

    val apart = (row until row + 100).maxOf { abs(onLight[it] - onSignal[it]) }
    withClue("a blur of the light lands at most $apart from the sdr signal's, too close to tell apart") {
      (apart > 2 * KEPT_BLUR_LEVEL).shouldBeTrue()
    }
    val edge = abs(heldAtFrame[50 * WHOLE_WIDTH + (WHOLE_WIDTH - 100) / 2] - onSignal[row])
    withClue("a blur held at the frame's edge lands $edge from one held at the crop's, too close to tell") {
      (edge > 2 * KEPT_BLUR_LEVEL).shouldBeTrue()
    }
    val worst = (row until row + 100).maxBy { abs(drawn[it] - onSignal[it]) }
    val off = abs(drawn[worst] - onSignal[worst])
    withClue("the cover is $off off the shared plan on the sdr signal at ${worst - row}") {
      (off <= KEPT_BLUR_LEVEL).shouldBeTrue()
    }
  }

  @Test
  fun `a half transparent frame blends over a fill on the shared codes`() {
    val source = listOf(224, 44, 29)
    val fill = listOf(49, 78, 110)
    val alpha = HALF_CODE_ALPHA
    val layer = codeColour(source, alpha).imageByCroppingToRect(CGRectMake(0.0, 0.0, 10.0, 10.0))
    val background = codeColour(fill, 255).imageByCroppingToRect(CGRectMake(0.0, 0.0, 10.0, 10.0))

    val pixel = layer.blendedInCodes(background) { imageByCompositingOverImage(it) }.codePixels(10, 10).at(5, 5)

    val off =
      (0 until 3).maxOf { abs(pixel[it] * 255.0 - blendOverFill(source[it], alpha, fill[it])) }
    withClue("the chain's blend reads ${pixel.take(3).map { it * 255 }}, off by $off codes") {
      (off <= CODE_BLEND_LEVEL).shouldBeTrue()
    }
  }

  private fun codeColour(
    codes: List<Int>,
    alpha: Int,
  ): CIImage {
    val space = ColorSpace.Bt709.createCodeSpace()
    return CIImage(
      color =
        CIColor.colorWithRed(
          codes[0] / 255.0,
          green = codes[1] / 255.0,
          blue = codes[2] / 255.0,
          alpha = alpha / 255.0,
          colorSpace = space,
        )!!,
    )
  }

  // A grey of [level] over the rect, as display light that may run past one.
  private fun grey(
    level: Double,
    x: Double,
    y: Double,
    width: Double,
    height: Double,
  ): CIImage =
    CIImage(color = CIColor.colorWithRed(1.0, green = 1.0, blue = 1.0, alpha = 1.0))
      .imageByCroppingToRect(CGRectMake(x, y, width, height))
      .imageByApplyingFilter(
        "CIColorMatrix",
        mapOf(
          "inputRVector" to CIVector.vectorWithX(level, 0.0, 0.0, 0.0),
          "inputGVector" to CIVector.vectorWithX(0.0, level, 0.0, 0.0),
          "inputBVector" to CIVector.vectorWithX(0.0, 0.0, level, 0.0),
        ),
      )

  private companion object {
    // Core Image's Gaussian against the plan on the SDR signal a kept grade is blurred on, held to two eight bit codes
    // as a GaussianBlur on Core Image is.
    const val KEPT_BLUR_LEVEL = 2.0 / 255.0
    const val HALF_CODE_ALPHA = 128
    const val CODE_BLEND_LEVEL = 1.0

    // A sigma of 16 pixels on the square, the size the Core Image blur tests of a kept grade run at.
    const val KEPT_RADIUS = 0.16f

    // Display light with reference white at one, the levels the Core Image blur tests of a kept grade use.
    const val BRIGHT_LIGHT = 1.6
    const val DIM_LIGHT = 0.1
    const val STRIP_FROM = 14.0
    const val STRIP_TO = 24.0
    const val WHOLE_WIDTH = 200
    val SQUARE = Size(100, 100)
  }
}

// The SDR signal of display light with reference white at one.
private fun signalOf(light: Double): Double = sdrSignalFromNits(light * HDR_REFERENCE_WHITE_NITS)

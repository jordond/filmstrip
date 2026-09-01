package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.ExperimentalFilmstripApi
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.FrameInfo
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.motion.Easing
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIColor
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.CoreImage.kCIFormatRGBA8
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * What the Apple lowering actually draws for a pan, read off the pixels.
 *
 * The frame is red on the left and blue on the right, and the pan travels from a window inside the
 * red half to one inside the blue half. How much red survives is therefore a direct reading of
 * which region was cut out, and it is compared against [regionAt] rather than against a copied
 * number, so a lowering that eased a curve of its own fails here.
 *
 * Measured away from both ends, which agree under every reading of the travel and so prove nothing
 * on their own, and on a curve as well as on a constant rate.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalFilmstripApi::class)
class KenBurnsStepTest {
  private val resolver = BuiltInEffectResolver()

  @Test
  fun `cuts out the region the shared interpolation names`() {
    val context = renderingContext() ?: return

    listOf(0.0, 0.4, 0.5, 0.6, 1.0).forEach { fraction -> assertRegion(PAN, fraction, context) }
  }

  /**
   * The same reading on a pan paced by a curve.
   *
   * A lowering that interpolated the two regions itself instead of reading [regionAt] draws the
   * straight-line position whatever curve it was handed, and at every fraction here the curve is
   * further from that position than the reading's own slack.
   */
  @Test
  fun `paces the region along the curve it was given`() {
    val context = renderingContext() ?: return

    CURVES.forEach { easing ->
      val pan = KenBurns(CURVED_FROM, CURVED_TO, easing)

      CURVED_FRACTIONS.forEach { fraction -> assertRegion(pan, fraction, context) }
    }
  }

  /**
   * A vertex transform on the other backend leaves the frame the size it arrived at, so this one
   * has to as well. A pan that resized the frame would move the size stage.
   */
  @Test
  fun `hands back a frame the size of the one it was given`() {
    val step = stepFor(PAN)

    listOf(0.0, 0.4, 0.6, 1.0).forEach { fraction ->
      val drawn = step.apply(frame(), FrameInfo(ATTRIBUTES, SPAN_START + SPAN_LENGTH * fraction))

      drawn.extent.useContents {
        assertTrue(
          abs(size.width - FRAME.width) < 1.0 && abs(size.height - FRAME.height) < 1.0,
          "the pan handed back ${size.width}x${size.height} at $fraction",
        )
      }
    }
  }

  @Test
  fun `refuses a region that leaves the frame`() {
    val outside = KenBurns(NormalizedRect.Full, NormalizedRect(0.5f, 0f, 1.5f, 1f))

    assertIs<EffectResolution.Unsupported>(resolver.resolve(outside, CAPABILITIES, ATTRIBUTES))
  }

  @Test
  fun `refuses a region with no area`() {
    val empty = KenBurns(NormalizedRect.Full, NormalizedRect(0.5f, 0.5f, 0.5f, 0.5f))

    assertIs<EffectResolution.Unsupported>(resolver.resolve(empty, CAPABILITIES, ATTRIBUTES))
  }

  private fun assertRegion(
    pan: KenBurns,
    fraction: Double,
    context: CIContext,
  ) {
    val time = SPAN_START + SPAN_LENGTH * fraction
    val region = pan.regionAt(time, SPAN)
    val expected = ((BOUNDARY - region.left) / region.width).coerceIn(0f, 1f)

    val drawn = stepFor(pan).apply(frame(), FrameInfo(ATTRIBUTES, time))
    val measured = redFraction(drawn, context)

    assertTrue(
      abs(measured - expected) < TOLERANCE,
      "at $fraction of a ${pan.easing} pan the region $region should leave $expected red, measured $measured",
    )
  }

  private fun stepFor(spec: KenBurns) =
    assertIs<EffectResolution.Resolved>(resolver.resolve(spec, CAPABILITIES, ATTRIBUTES)).effect.step

  /**
   * A context to read pixels through, or null when this process cannot build one.
   */
  private fun renderingContext(): CIContext? =
    try {
      CIContext()
    } catch (absent: NullPointerException) {
      null
    }

  private fun frame(): CIImage {
    val whole = CGRectMake(0.0, 0.0, FRAME.width.toDouble(), FRAME.height.toDouble())
    val left = CGRectMake(0.0, 0.0, FRAME.width * BOUNDARY.toDouble(), FRAME.height.toDouble())
    val blue = CIImage(color = CIColor.blueColor).imageByCroppingToRect(whole)
    return CIImage(color = CIColor.redColor).imageByCroppingToRect(left).imageByCompositingOverImage(blue)
  }

  /**
   * The share of the drawn frame that came out of the red half.
   */
  private fun redFraction(
    image: CIImage,
    context: CIContext,
  ): Float =
    memScoped {
      val rowBytes = FRAME.width * CHANNELS
      val pixels = allocArray<UByteVar>(rowBytes * FRAME.height)
      val space = CGColorSpaceCreateDeviceRGB()
      context.render(
        image = image,
        toBitmap = pixels,
        rowBytes = rowBytes.toLong(),
        bounds = CGRectMake(0.0, 0.0, FRAME.width.toDouble(), FRAME.height.toDouble()),
        format = kCIFormatRGBA8,
        colorSpace = space,
      )
      CGColorSpaceRelease(space)

      var red = 0
      for (row in 0 until FRAME.height) {
        for (column in 0 until FRAME.width) {
          val offset = row * rowBytes + column * CHANNELS
          if (pixels[offset].toInt() > HALF_BYTE && pixels[offset + 2].toInt() < HALF_BYTE) red++
        }
      }
      red.toFloat() / (FRAME.width * FRAME.height)
    }

  private companion object {
    val FRAME = Size(400, 200)

    /**
     * Where the red half gives way to the blue one, as a fraction of the frame.
     */
    const val BOUNDARY = 0.5f

    // Both windows are a quarter of the frame wide and neither straddles the boundary, so at each
    // end the frame is one flat colour and every reading in between is a real measurement.
    val PAN =
      KenBurns(
        from = NormalizedRect(0f, 0f, 0.25f, 1f),
        to = NormalizedRect(0.75f, 0f, 1f, 1f),
        easing = Easing.Linear,
      )

    // A window travelling exactly its own width, so the red left is the share of the travel still
    // to come and every reading lands inside the travel rather than against a flat end.
    val CURVED_FROM = NormalizedRect(0.25f, 0f, 0.5f, 1f)
    val CURVED_TO = NormalizedRect(0.5f, 0f, 0.75f, 1f)

    val CURVES = Easing.entries.filter { it != Easing.Linear }

    // Off the halfway point, which the symmetric curve rejoins the straight line at, and off the
    // ends. The closest any curve comes to a straight line here is four times TOLERANCE away.
    val CURVED_FRACTIONS = listOf(0.25, 0.4, 0.6, 0.75)

    val SPAN_START = 2.seconds
    val SPAN_LENGTH = 4.seconds
    val SPAN = TimeRange.of(SPAN_START, SPAN_START + SPAN_LENGTH)

    // One column of slack on each edge of a 400 wide frame, for where the boundary resamples.
    const val TOLERANCE = 0.02f
    const val CHANNELS = 4
    const val HALF_BYTE = 128

    val ATTRIBUTES =
      Attributes(
        inputSize = FRAME,
        outputSize = FRAME,
        layoutSize = FRAME,
        colorSpace = ColorSpace.Bt709,
        hdrTransfer = null,
        frameRate = 30f,
        span = SPAN,
      )

    val CAPABILITIES =
      RenderCapabilities(
        api = RenderApi.Metal,
        supportsFragmentShader = true,
        supportsComputeShader = true,
        supportsHdr = false,
        colorSpaces = setOf(ColorSpace.Bt709),
        maxTextureSize = 8_192,
        features = emptySet(),
      )
  }
}

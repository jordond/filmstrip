package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.effects.overlay.OverlayFixtures.BASELINE
import dev.jordond.filmstrip.effects.overlay.OverlayFixtures.CHANNELS
import dev.jordond.filmstrip.effects.overlay.OverlayFixtures.FRAME
import dev.jordond.filmstrip.effects.overlay.OverlayFixtures.FRAME_RECT
import dev.jordond.filmstrip.effects.overlay.OverlayFixtures.RASTER
import dev.jordond.filmstrip.effects.overlay.OverlayFixtures.SPAN
import dev.jordond.filmstrip.effects.overlay.OverlayFixtures.at
import dev.jordond.filmstrip.effects.overlay.OverlayFixtures.decodeHex
import dev.jordond.filmstrip.effects.overlay.OverlayFixtures.pixels
import dev.jordond.filmstrip.effects.overlay.OverlayFixtures.stepFor
import dev.jordond.filmstrip.effects.renderingContext
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.media.ImageSource
import io.kotest.assertions.withClue
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.kCGColorSpaceSRGB
import platform.CoreImage.CIColor
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import kotlin.math.abs
import kotlin.test.Test

/**
 * How an overlay blends onto an SDR frame, read off the pixels.
 *
 * An SDR overlay blends on the codes of the frame's own BT.709 color space, the values an encoder
 * writes and the ones the other backends blend on. Each case draws a raster with a half alpha band
 * over a flat fill and reads that band, so an edge that fringes or a blend worked on sRGB's codes
 * lands outside the tolerance.
 */
@OptIn(ExperimentalForeignApi::class)
class OverlayBlendHardwareTest {
  // The raster's right half is drawn at half alpha, the way an antialiased glyph or image edge is.
  // That band lands its own alpha times the opacity of the way from the fill to the overlay's color,
  // at every opacity, so a blend that reads a premultiplied edge as a color fringes here.
  @Test
  fun `blends a soft edge by its own alpha`() {
    val context = renderingContext()

    listOf(1f, HALF, QUARTER_OPACITY).forEach { opacity ->
      val band = softBand(opacity, context)
      band.expected.indices.forEach { channel ->
        withClue("a soft edge at $opacity drew ${band.drawn} where its own alpha asks for ${band.expected}") {
          band.drawn[channel] shouldBe (band.expected[channel] plusOrMinus CODE_DRIFT)
        }
      }
    }
  }

  // The same blend worked on sRGB's codes lands further from the frame's own than the tolerance
  // reaches, so the soft edge case above says which of the two ran. Away from black the two curves
  // are one straight line apart and a blend reads the same in either, so it takes a channel heading
  // for zero and enough coverage to open the gap.
  @Test
  fun `blends on the frame's own codes rather than sRGB's`() {
    val context = renderingContext()

    listOf(1f, HALF).forEach { opacity ->
      val band = softBand(opacity, context)
      withClue(
        "at $opacity the frame's codes ask for ${band.expected} and sRGB's for ${band.inSrgb}, too close to tell",
      ) {
        band.expected.indices.any { abs(band.expected[it] - band.inSrgb[it]) > CODE_DRIFT } shouldBe true
      }
    }
  }

  /**
   * What the half alpha band of [SOFT] drew at [opacity] over a flat fill, and what the blend asks
   * for there.
   *
   * The overlay's color is read off the raster's opaque half, drawn fully opaque, so it carries the
   * codes it lands with rather than the ones it was authored with.
   */
  private fun softBand(
    opacity: Float,
    context: CIContext,
  ): SoftBand {
    val overlay = ImageOverlay(SOFT, Corner.TopStart, opacity = opacity)
    val rect = overlay.placedOn(FRAME, RASTER).rectOn(FRAME)
    val middle = (rect.top + rect.bottom) / 2f
    val band = offsetOf(rect.left + rect.width * THREE_QUARTERS_ACROSS, middle)
    val solid = offsetOf(rect.left + rect.width * QUARTER_ACROSS, middle)
    val sampled = withClue("the watermark was not drawn") { overlay.frameAt(BASELINE, SPAN).shouldNotBeNull() }.opacity
    val coverage = SOFT_ALPHA * sampled

    val opaqueStep = stepFor(ImageOverlay(SOFT, Corner.TopStart))
    val plain = pixels(fill(), context)
    val opaque = pixels(opaqueStep.apply(fill(), at(BASELINE)), context)
    val drawn = pixels(stepFor(overlay).apply(fill(), at(BASELINE)), context)
    val plainSrgb = pixels(fill(), context, kCGColorSpaceSRGB)
    val opaqueSrgb = pixels(opaqueStep.apply(fill(), at(BASELINE)), context, kCGColorSpaceSRGB)
    val blendedSrgb =
      List(COLOR_CHANNELS) { plainSrgb[band + it] + coverage * (opaqueSrgb[solid + it] - plainSrgb[band + it]) }

    return SoftBand(
      drawn = List(COLOR_CHANNELS) { drawn[band + it] },
      expected = List(COLOR_CHANNELS) { plain[band + it] + coverage * (opaque[solid + it] - plain[band + it]) },
      inSrgb = codesOf(blendedSrgb, context),
    )
  }

  private class SoftBand(
    val drawn: List<Float>,
    val expected: List<Float>,
    val inSrgb: List<Float>,
  )

  private fun offsetOf(
    xFraction: Float,
    yFraction: Float,
  ): Int = ((yFraction * FRAME.height).toInt() * FRAME.width + (xFraction * FRAME.width).toInt()) * CHANNELS

  /**
   * The frame's own codes for a color given in sRGB's.
   */
  private fun codesOf(
    srgb: List<Float>,
    context: CIContext,
  ): List<Float> {
    val space = CGColorSpaceCreateWithName(kCGColorSpaceSRGB)
    val color =
      CIColor.colorWithRed(
        srgb[0].toDouble(),
        green = srgb[1].toDouble(),
        blue = srgb[2].toDouble(),
        alpha = 1.0,
        colorSpace = space,
      )
    CGColorSpaceRelease(space)
    val flat = CIImage(color = withClue("no sRGB color") { color.shouldNotBeNull() }).imageByCroppingToRect(FRAME_RECT)

    return pixels(flat, context).take(COLOR_CHANNELS)
  }

  private fun fill(): CIImage = CIImage(color = FILL).imageByCroppingToRect(FRAME_RECT)

  private companion object {
    const val COLOR_CHANNELS = 3
    const val HALF = 0.5f
    const val QUARTER_OPACITY = 0.25f

    // Where across the soft raster its opaque half and its half alpha band are read, each between
    // the centres of its own two columns so the upscale's blur across the seam never reaches it.
    const val QUARTER_ACROSS = 0.25f
    const val THREE_QUARTERS_ACROSS = 0.75f

    // A code value or two of a half float working format. Nothing encodes a frame here.
    const val CODE_DRIFT = 2f / 255f

    // The flat fill the soft edge is drawn over, off black on every channel so the blend moves each.
    val FILL: CIColor = CIColor.colorWithRed(0.2, 0.45, 0.3)

    // A four by two red PNG, opaque in its left two columns and at alpha 0x80 in its right two. Green
    // and blue blend towards zero, which is where sRGB's curve and BT.709's part company.
    val SOFT =
      ImageSource.ofBytes(
        (
          "89504e470d0a1a0a0000000d49484452000000040000000208060000007fa87d63000000144944415478da63f8cf" +
            "c0f01f8a1b4098015d0000f8400df5d23cb3380000000049454e44ae426082"
        ).decodeHex(),
      )
    const val SOFT_ALPHA = 0x80 / 255f
  }
}

package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.avfoundation.internal.chain.fillCIColor
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.FrameInfo
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.color.createCodeSpace
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.CoreImage.kCIContextWorkingFormat
import platform.CoreImage.kCIFormatRGBAf
import platform.Foundation.NSMutableDictionary
import platform.Foundation.NSNumber
import platform.Foundation.numberWithInt
import platform.Foundation.setValue
import kotlin.test.fail
import kotlin.time.Duration

/**
 * An SDR BT.709 frame of [size] filled with [fill] with [overlays] drawn over it by the built-in resolver at [at] of
 * [span], the image Core Image hands an SDR export's encoder.
 *
 * Each overlay is paired with the one cause it must resolve degraded with, or null when it must resolve exact. An
 * overlay that resolves any other way fails, and a degraded one is drawn the way it resolved.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun drawnSdrFrame(
  size: Size,
  fill: Int,
  overlays: List<Pair<ImageOverlay, String?>>,
  span: TimeRange,
  at: Duration,
): CIImage {
  val attributes =
    Attributes(
      inputSize = size,
      outputSize = size,
      layoutSize = size,
      colorSpace = ColorSpace.Bt709,
      hdrTransfer = null,
      frameRate = FRAME_RATE,
      span = span,
    )
  val capabilities =
    RenderCapabilities(
      api = RenderApi.CoreImage,
      supportsFragmentShader = true,
      supportsComputeShader = true,
      supportsHdr = false,
      colorSpaces = setOf(ColorSpace.Bt709),
      maxTextureSize = MAX_TEXTURE,
      features = emptySet(),
    )
  val bounds = CGRectMake(0.0, 0.0, size.width.toDouble(), size.height.toDouble())
  val resolver = BuiltInEffectResolver()
  val frame = CIImage(color = fillCIColor(fill, transfer = null)).imageByCroppingToRect(bounds)
  return overlays.fold(frame) { image, (overlay, degradedBy) ->
    val effect =
      when (val resolved = resolver.resolve(overlay, capabilities, attributes).shouldNotBeNull()) {
        is EffectResolution.Resolved -> {
          withClue("$overlay resolved exact, where it degrades with: $degradedBy") { degradedBy.shouldBeNull() }
          resolved.effect
        }
        is EffectResolution.Degraded -> {
          withClue("$overlay degraded with other causes") {
            resolved.causes.map { it.message } shouldBe listOf(degradedBy)
          }
          resolved.effect
        }
        is EffectResolution.Unsupported -> {
          fail("an overlay drew nothing: ${resolved.message}")
        }
        is EffectResolution.Failed -> {
          fail("an overlay failed to resolve: ${resolved.error.message}")
        }
      }
    effect.step.apply(image, FrameInfo(attributes, at))
  }
}

/**
 * A context with a float working format to read pixels through.
 *
 * Fails the calling test when this process cannot build one.
 */
@Suppress("CAST_NEVER_SUCCEEDS", "UNCHECKED_CAST")
@OptIn(ExperimentalForeignApi::class)
internal fun floatContext(): CIContext {
  val options = NSMutableDictionary()
  kCIContextWorkingFormat?.let { options.setValue(NSNumber.numberWithInt(kCIFormatRGBAf), forKey = it) }
  return try {
    CIContext.contextWithOptions(options as Map<Any?, *>)
  } catch (absent: NullPointerException) {
    fail("this process built no Core Image context with a float working format: ${absent.message}")
  }
}

/**
 * The unrounded red, green and blue codes of the pixel holding ([xFraction], [yFraction]) of this image of [size],
 * read in the BT.709 code space an SDR export's buffer is written in, `0..255`.
 *
 * Fractions run from the top-left, the way filmstrip's geometry does.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CIImage.sdrCodesAt(
  context: CIContext,
  size: Size,
  xFraction: Float,
  yFraction: Float,
): List<Float> =
  memScoped {
    val x = (size.width * xFraction).toInt().coerceIn(0, size.width - 1)
    val row = (size.height * yFraction).toInt().coerceIn(0, size.height - 1)
    val pixel = allocArray<FloatVar>(CHANNELS)
    val space = checkNotNull(ColorSpace.Bt709.createCodeSpace())
    context.render(
      image = this@sdrCodesAt,
      toBitmap = pixel,
      rowBytes = (CHANNELS * Float.SIZE_BYTES).toLong(),
      bounds = CGRectMake(x.toDouble(), (size.height - 1 - row).toDouble(), 1.0, 1.0),
      format = kCIFormatRGBAf,
      colorSpace = space,
    )
    CGColorSpaceRelease(space)
    List(RGB) { pixel[it] / pixel[RGB] * MAX_CODE }
  }

private const val CHANNELS = 4
private const val RGB = 3
private const val MAX_CODE = 255f
private const val FRAME_RATE = 30f
private const val MAX_TEXTURE = 8_192

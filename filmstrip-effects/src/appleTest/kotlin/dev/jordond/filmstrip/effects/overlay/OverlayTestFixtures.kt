package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.FrameInfo
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.RenderFeature
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.color.createCodeSpace
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import platform.CoreFoundation.CFStringRef
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.CoreImage.kCIFormatRGBAf
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The frame, span, resolver and pixel readers the Apple overlay tests share.
 *
 * Tests import these by name, so a test's own constant of the same name stays its own.
 */
internal object OverlayFixtures {
  val FRAME = Size(640, 360)

  @OptIn(ExperimentalForeignApi::class)
  val FRAME_RECT = CGRectMake(0.0, 0.0, FRAME.width.toDouble(), FRAME.height.toDouble())

  val RASTER = Size(4, 2)
  val SPAN = TimeRange.of(Duration.ZERO, 10.seconds)

  // Where a draw carrying no animation is sampled. Any frame of the run does, since nothing about
  // such a draw varies with time.
  val BASELINE = 1.seconds

  const val CHANNELS = 4

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
      features = setOf(RenderFeature.TextRendering),
    )

  private const val HEX = 16

  fun stepFor(spec: EffectSpec) =
    assertIs<EffectResolution.Resolved>(
      BuiltInEffectResolver().resolve(spec, CAPABILITIES, ATTRIBUTES),
    ).effect.step

  fun at(time: Duration) = FrameInfo(ATTRIBUTES, time)

  /**
   * A context to read pixels through, or null when this process cannot build one.
   *
   * An Objective-C `init` that answers nil reaches Kotlin as a non-null reference that is not
   * there, and Kotlin/Native raises at the constructor, not at the first use.
   */
  fun renderingContext(): CIContext? =
    try {
      CIContext()
    } catch (absent: NullPointerException) {
      null
    }

  /**
   * The whole of [FRAME] in the codes of the colour space [spaceName] names, four floats per pixel
   * from the top row down.
   *
   * With no [spaceName] that is the frame's own BT.709 code space, the codes an encoder writes and the
   * ones an SDR overlay blends on.
   */
  @OptIn(ExperimentalForeignApi::class)
  fun pixels(
    image: CIImage,
    context: CIContext,
    spaceName: CFStringRef? = null,
  ): FloatArray =
    memScoped {
      val count = FRAME.width * FRAME.height * CHANNELS
      val buffer = allocArray<FloatVar>(count)
      val space = spaceName?.let { CGColorSpaceCreateWithName(it) } ?: ColorSpace.Bt709.createCodeSpace()
      context.render(
        image = image,
        toBitmap = buffer,
        rowBytes = (FRAME.width * CHANNELS * Float.SIZE_BYTES).toLong(),
        bounds = FRAME_RECT,
        format = kCIFormatRGBAf,
        colorSpace = space,
      )
      CGColorSpaceRelease(space)
      FloatArray(count) { buffer[it] }
    }

  fun String.decodeHex(): ByteArray =
    ByteArray(length / 2) { index ->
      ((this[index * 2].digitToInt(HEX) shl 4) or this[index * 2 + 1].digitToInt(HEX)).toByte()
    }
}

package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.FrameInfo
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.RenderFeature
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.style.TextStyle
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIColor
import platform.CoreImage.CIImage
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Overlays that appear for part of the composition.
 *
 * A step is asked for a frame and a [FrameInfo], and the time in it is the composition's timeline
 * position, which is the same base media3 hands its overlays and the same one ffmpeg's `enable`
 * gates on. Outside its window a step hands the frame straight back, so the assertion is identity.
 * That says the overlay drew nothing at all, not that it drew something invisible.
 */
@OptIn(ExperimentalForeignApi::class)
class TimedOverlayTest {
  private val resolver = BuiltInEffectResolver()

  @Test
  fun `draws a watermark inside its window and not outside`() {
    val step = stepFor(ImageOverlay(RED, Corner.BottomEnd, visibleDuring = WINDOW))
    val frame = background()

    assertNotSame(frame, step.apply(frame, at(1.5.seconds)), "the watermark is absent inside its window")
    assertSame(frame, step.apply(frame, at(3.seconds)), "the watermark is still drawn after its window")
    assertSame(frame, step.apply(frame, at(0.5.seconds)), "the watermark is drawn before its window")
  }

  @Test
  fun `draws text inside its window and not outside`() {
    val step = stepFor(TextOverlay("caption", TextStyle(), visibleDuring = WINDOW))
    val frame = background()

    assertNotSame(frame, step.apply(frame, at(1.5.seconds)), "the text is absent inside its window")
    assertSame(frame, step.apply(frame, at(3.seconds)), "the text is still drawn after its window")
  }

  // The window is half-open, the same as every other TimeRange in filmstrip.
  @Test
  fun `includes the window's start and excludes its end`() {
    val step = stepFor(ImageOverlay(RED, Corner.TopStart, visibleDuring = WINDOW))
    val frame = background()

    assertNotSame(frame, step.apply(frame, at(1.seconds)), "the start of the window is excluded")
    assertSame(frame, step.apply(frame, at(2.seconds)), "the end of the window is included")
  }

  @Test
  fun `draws an untimed overlay on every frame`() {
    val step = stepFor(ImageOverlay(RED, Corner.BottomEnd))
    val frame = background()

    listOf(0.seconds, 1.5.seconds, 99.seconds).forEach { time ->
      assertNotSame(frame, step.apply(frame, at(time)), "an untimed overlay was absent at $time")
    }
  }

  private fun stepFor(spec: dev.jordond.filmstrip.effect.EffectSpec) =
    assertIs<EffectResolution.Resolved>(
      resolver.resolve(spec, CAPABILITIES, ATTRIBUTES),
    ).effect.step

  private fun at(time: kotlin.time.Duration) = FrameInfo(ATTRIBUTES, time)

  private fun background(): CIImage =
    CIImage(color = CIColor.blackColor)
      .imageByCroppingToRect(CGRectMake(0.0, 0.0, FRAME.width.toDouble(), FRAME.height.toDouble()))

  private companion object {
    val FRAME = Size(640, 360)
    val WINDOW = TimeRange(1.seconds, 2.seconds)

    // A four by two opaque red PNG, so the height following the image's own aspect is exercised.
    val RED =
      ImageSource.ofBytes(
        (
          "89504e470d0a1a0a0000000d4948445200000004000000020806000000" +
            "7fa87d630000001249444154789c63f8cfc0f01f1933a00b00000f210ff1" +
            "0437c69f0000000049454e44ae426082"
        ).decodeHex(),
      )

    val ATTRIBUTES =
      Attributes(
        inputSize = FRAME,
        outputSize = FRAME,
        layoutSize = FRAME,
        colorSpace = ColorSpace.Bt709,
        hdrTransfer = null,
        frameRate = 30f,
        span = TimeRange.of(Duration.ZERO, 10.seconds),
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

    const val HEX = 16

    fun String.decodeHex(): ByteArray =
      ByteArray(length / 2) { index ->
        ((this[index * 2].digitToInt(HEX) shl 4) or this[index * 2 + 1].digitToInt(HEX)).toByte()
      }
  }
}

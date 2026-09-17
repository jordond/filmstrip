package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.CoreImageEffect
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
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIColor
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.CoreImage.kCIFormatRGBAf
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Overlays that appear for part of the composition, and how they are drawn across it.
 *
 * A step is asked for a frame and a [FrameInfo], and the time in it is the composition's timeline
 * position, which is the same base media3 hands its overlays and the same one ffmpeg's `enable`
 * gates on. Outside its window a step hands the frame straight back, so the assertion is identity.
 * That says the overlay drew nothing at all, not that it drew something invisible.
 *
 * The animated cases read the middle of a ramp rather than its ends, since both ends agree under an
 * additive and a multiplicative reading, and every expectation comes from [frameAt] and [animatedBy]
 * rather than from a number copied out of them.
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

  // An overlay that named no window is drawn on every frame it is handed, the span's own bounds
  // included, so a timestamp landing on one never drops a frame nothing asked to hide.
  @Test
  fun `draws an untimed overlay on every frame`() {
    val step = stepFor(ImageOverlay(RED, Corner.BottomEnd))
    val frame = background()

    listOf(Duration.ZERO, 1.5.seconds, 99.seconds).forEach { time ->
      assertNotSame(frame, step.apply(frame, at(time)), "an untimed overlay was absent at $time")
    }
  }

  // The authored opacity is the one frameAt folds in, so the backend applies it once and a
  // watermark authored at half covers half of what an opaque one covers.
  @Test
  fun `draws an authored opacity as the coverage it asks for`() {
    val context = renderingContext() ?: return
    val overlay = ImageOverlay(RED, Corner.TopStart, opacity = HALF)
    val plain = pixels(background(), context)
    val whole = movedBy(stepFor(ImageOverlay(RED, Corner.TopStart)), BASELINE, context, plain)
    assertTrue(whole > FLOOR, "the opaque watermark only moved the frame by $whole, so nothing here measures an alpha")

    val opacity = assertNotNull(overlay.frameAt(BASELINE, SPAN), "the watermark was not drawn").opacity
    val moved = movedBy(stepFor(overlay), BASELINE, context, plain)

    assertEquals(opacity * whole, moved, whole * DRIFT, "a half alpha watermark moved $moved of a full $whole")
  }

  @Test
  fun `fades a watermark in across the middle of its ramp`() {
    val context = renderingContext() ?: return
    val overlay = ImageOverlay(RED, Corner.TopStart, animation = fadeIn(RAMP))
    val faded = stepFor(overlay)
    val plain = pixels(background(), context)
    val whole = movedBy(stepFor(ImageOverlay(RED, Corner.TopStart)), BASELINE, context, plain)
    assertTrue(whole > FLOOR, "the unfaded watermark only moved the frame by $whole, so nothing here measures a fade")

    val drawn =
      RAMP_POINTS.map { time ->
        val opacity = assertNotNull(overlay.frameAt(time, SPAN), "the watermark was not drawn at $time").opacity
        val moved = movedBy(faded, time, context, plain)
        assertEquals(opacity * whole, moved, whole * DRIFT, "a fade at $time moved $moved of a full $whole")
        moved
      }

    assertTrue(drawn[0] < drawn[1], "the two points of the ramp drew the same picture: $drawn")
    assertTrue(drawn[1] < whole, "three quarters of the ramp drew as much as the unfaded watermark")
  }

  // TextOverlay carries no authored opacity, so everything it fades by comes from the animation. The
  // style carries a plate because CoreText draws the glyphs themselves black over a black frame.
  @Test
  fun `fades text in across the middle of its ramp`() {
    val context = renderingContext() ?: return
    val overlay = TextOverlay(CAPTION, PLATED, animation = fadeIn(RAMP))
    val faded = stepFor(overlay)
    val plain = pixels(background(), context)
    val whole = movedBy(stepFor(TextOverlay(CAPTION, PLATED)), BASELINE, context, plain)
    assertTrue(whole > FLOOR, "the unfaded text only moved the frame by $whole, so nothing here measures a fade")

    RAMP_POINTS.forEach { time ->
      val opacity = assertNotNull(overlay.frameAt(time, SPAN), "the text was not drawn at $time").opacity
      val moved = movedBy(faded, time, context, plain)
      assertEquals(opacity * whole, moved, whole * DRIFT, "a fade at $time moved $moved of a full $whole")
    }
  }

  // The only case that reaches animatedBy, since a fade leaves the geometry where it was authored.
  @Test
  fun `slides a watermark across the middle of its ramp`() {
    val context = renderingContext() ?: return
    val overlay = ImageOverlay(RED, Corner.TopStart, animation = slideIn(TRAVEL, RAMP))
    val step = stepFor(overlay)
    val still = ImageOverlay(RED, Corner.TopStart)
    // Upscaling a four pixel wide raster leaves a soft edge, so the column the watermark first
    // reads on sits a few pixels inside its rectangle. The same few on every frame, so an
    // unanimated draw is what the animated ones are measured against.
    val resting = leftEdgeOf(pixels(stepFor(still).apply(background(), at(BASELINE)), context))
    val restingLeft = still.placedOn(FRAME, RASTER).rectOn(FRAME).left * FRAME.width

    RAMP_POINTS.forEach { time ->
      val sampled = assertNotNull(overlay.frameAt(time, SPAN), "the watermark was not drawn at $time")
      val expected =
        overlay
          .placedOn(FRAME, RASTER)
          .animatedBy(sampled)
          .rectOn(FRAME)
          .left * FRAME.width
      val drawn = leftEdgeOf(pixels(step.apply(background(), at(time)), context))

      assertEquals(
        expected - restingLeft,
        (drawn - resting).toFloat(),
        EDGE_DRIFT,
        "a slide at $time moved the watermark ${drawn - resting} pixels from where it was authored",
      )
    }
  }

  @Test
  fun `draws nothing outside the window an animation ramps over`() {
    val overlay = ImageOverlay(RED, Corner.TopStart, visibleDuring = WINDOW, animation = fadeIn(RAMP))
    val step = stepFor(overlay)
    val frame = background()

    assertSame(frame, step.apply(frame, at(0.5.seconds)), "an animated watermark was drawn before its window")
    assertSame(frame, step.apply(frame, at(3.seconds)), "an animated watermark was drawn after its window")
  }

  // The ramp is measured off the window rather than off the span, so a one second fade inside a one
  // second window is a quarter done a quarter of a second in, not a fortieth of the way up the span.
  @Test
  fun `ramps from the window's own start`() {
    val context = renderingContext() ?: return
    val overlay = ImageOverlay(RED, Corner.TopStart, visibleDuring = WINDOW, animation = fadeIn(WINDOW_RAMP))
    val step = stepFor(overlay)
    val plain = pixels(background(), context)
    val whole = movedBy(stepFor(ImageOverlay(RED, Corner.TopStart)), BASELINE, context, plain)
    assertTrue(whole > FLOOR, "the unfaded watermark only moved the frame by $whole, so nothing here measures a fade")
    val time = WINDOW.start + INTO_WINDOW

    val opacity = assertNotNull(overlay.frameAt(time, SPAN), "the watermark was not drawn at $time").opacity
    val moved = movedBy(step, time, context, plain)

    assertEquals(opacity * whole, moved, whole * DRIFT, "a fade at $time moved $moved of a full $whole")
  }

  private fun stepFor(spec: dev.jordond.filmstrip.effect.EffectSpec) =
    assertIs<EffectResolution.Resolved>(
      resolver.resolve(spec, CAPABILITIES, ATTRIBUTES),
    ).effect.step

  private fun at(time: kotlin.time.Duration) = FrameInfo(ATTRIBUTES, time)

  private fun background(): CIImage =
    CIImage(color = CIColor.blackColor)
      .imageByCroppingToRect(CGRectMake(0.0, 0.0, FRAME.width.toDouble(), FRAME.height.toDouble()))

  /**
   * A context to read pixels through, or null when this process cannot build one.
   */
  private fun renderingContext(): CIContext? =
    try {
      CIContext()
    } catch (absent: NullPointerException) {
      null
    }

  /**
   * The whole frame as linear light, four floats per pixel.
   *
   * Read in the context's own working space rather than an encoded one, because everything measured
   * here is a sum over the frame and a sum is only proportional to the opacity where the pixels are
   * linear in it.
   */
  private fun pixels(
    image: CIImage,
    context: CIContext,
  ): FloatArray =
    memScoped {
      val count = FRAME.width * FRAME.height * CHANNELS
      val buffer = allocArray<FloatVar>(count)
      context.render(
        image = image,
        toBitmap = buffer,
        rowBytes = (FRAME.width * CHANNELS * Float.SIZE_BYTES).toLong(),
        bounds = CGRectMake(0.0, 0.0, FRAME.width.toDouble(), FRAME.height.toDouble()),
        format = kCIFormatRGBAf,
        colorSpace = null,
      )
      FloatArray(count) { buffer[it] }
    }

  /**
   * How far [step] moved the frame away from [plain] at [time], summed over every channel of every
   * pixel.
   *
   * Compositing over a fixed background moves each channel by the overlay's opacity times something
   * the opacity does not appear in, so the total is that opacity times what a fully opaque draw
   * would have moved. Whatever the overlay is and wherever it lands cancels out of the ratio.
   */
  private fun movedBy(
    step: CoreImageEffect,
    time: Duration,
    context: CIContext,
    plain: FloatArray,
  ): Float {
    val drawn = pixels(step.apply(background(), at(time)), context)
    var total = 0f
    for (index in drawn.indices) total += abs(drawn[index] - plain[index])
    return total
  }

  /**
   * The leftmost column of [pixels] the red watermark covers, or -1 when it covers none.
   */
  private fun leftEdgeOf(pixels: FloatArray): Int {
    for (column in 0 until FRAME.width) {
      for (row in 0 until FRAME.height) {
        val offset = (row * FRAME.width + column) * CHANNELS
        if (pixels[offset] > RED_FLOOR && pixels[offset + 1] < GREEN_CEILING) return column
      }
    }
    return -1
  }

  private companion object {
    val FRAME = Size(640, 360)
    val RASTER = Size(4, 2)
    val WINDOW = TimeRange(1.seconds, 2.seconds)
    val SPAN = TimeRange.of(Duration.ZERO, 10.seconds)

    val RAMP = 2.seconds
    val QUARTER = 500.milliseconds
    val THREE_QUARTERS = 1_500.milliseconds
    val RAMP_POINTS = listOf(QUARTER, THREE_QUARTERS)

    // Where a draw carrying no animation is sampled. Any frame of the run does, since nothing about
    // such a draw varies with time.
    val BASELINE = 1.seconds

    val WINDOW_RAMP = 1.seconds
    val INTO_WINDOW = 250.milliseconds

    // Half the frame's width, which keeps the watermark on frame at both points of the ramp.
    val TRAVEL = OverlayOffset(0.5f, 0f)

    const val CAPTION = "caption"

    // A flat mid blue, far enough off black that a quarter of it is still well clear of the floor.
    val PLATED = TextStyle(backgroundColor = 0xFF6699CC.toInt())

    const val CHANNELS = 4
    const val HALF = 0.5f
    const val DRIFT = 0.02f
    const val EDGE_DRIFT = 2f
    const val FLOOR = 100f
    const val RED_FLOOR = 0.5f
    const val GREEN_CEILING = 0.25f

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

    const val HEX = 16

    fun String.decodeHex(): ByteArray =
      ByteArray(length / 2) { index ->
        ((this[index * 2].digitToInt(HEX) shl 4) or this[index * 2 + 1].digitToInt(HEX)).toByte()
      }
  }
}

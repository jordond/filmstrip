@file:OptIn(BetaInteropApi::class)

package dev.jordond.filmstrip.effects.filters.nightvision

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.FrameInfo
import dev.jordond.filmstrip.effects.color.createCodeSpace
import dev.jordond.filmstrip.effects.filters.film.FilmSignal
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGAffineTransformMakeTranslation
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.CoreImage.kCIContextWorkingFormat
import platform.CoreImage.kCIFormatRGBAf
import platform.Foundation.NSData
import platform.Foundation.NSDictionary
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.dictionaryWithObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Reads the kernel's own output, with no encoder in the way, against [NightVisionSignal.pixelAt].
 *
 * A frame goes in and comes out in the codes of the BT.709 buffer, the way the chain hands it over and takes it back.
 * Rows of a render and of a bitmap both run from the top, the way the signal counts them.
 */
@OptIn(ExperimentalForeignApi::class, InternalFilmstripApi::class)
@Suppress("UNCHECKED_CAST")
class NightVisionCoreImageHardwareTest {
  @Test
  fun `half intensity lands on the signal`() = assertLandsOnTheSignal(WIDTH, HEIGHT)

  @Test
  fun `a smaller frame lands on the signal at its own size`() = assertLandsOnTheSignal(WIDTH / 2, HEIGHT / 2)

  @Test
  fun `a three quarter frame lands on the signal at its own size`() =
    assertLandsOnTheSignal(
      WIDTH * 3 / 4,
      HEIGHT * 3 / 4,
    )

  @Test
  fun `the same time draws the same frame after a seek`() {
    val first = drawn(ramps(WIDTH, HEIGHT), WIDTH, HEIGHT, SECONDS)
    val elsewhere = drawn(ramps(WIDTH, HEIGHT), WIDTH, HEIGHT, OTHER_SECONDS)
    val again = drawn(ramps(WIDTH, HEIGHT), WIDTH, HEIGHT, SECONDS)

    withClue("the grain moves between the two times") { elsewhere.toList() shouldNotBe first.toList() }
    again.toList() shouldBe first.toList()
  }

  @Test
  fun `a frame away from the origin draws the same look`() {
    val moved = ramps(WIDTH, HEIGHT).imageByApplyingTransform(CGAffineTransformMakeTranslation(MOVED_X, MOVED_Y))
    val output = nightVisionStep(HALF_LOOK).apply(moved, FrameInfo(attributes(WIDTH, HEIGHT), SECONDS.seconds))
    val back = output.imageByApplyingTransform(CGAffineTransformMakeTranslation(-MOVED_X, -MOVED_Y))

    val frame = rendered(back, WIDTH, HEIGHT)
    val still = drawn(ramps(WIDTH, HEIGHT), WIDTH, HEIGHT, SECONDS)

    var worst = 0.0
    for (index in frame.indices) worst = max(worst, abs(frame[index] - still[index]).toDouble())
    worst shouldBeLessThan TOLERANCE
  }

  @Test
  fun `a clear bar stays clear`() {
    val image = imageOf(WIDTH, HEIGHT) { x, y -> if (x < BAR) FloatArray(CHANNELS) else rampsAt(x, y) }

    val frame = drawn(image, WIDTH, HEIGHT, SECONDS, NightVision())

    pixel(frame, WIDTH, 0, HEIGHT / 2).toList() shouldBe listOf(0f, 0f, 0f, 0f)
  }

  @Test
  fun `this device compiles the kernel`() {
    nightVisionKernelLimit().shouldBeNull()
  }

  @Test
  fun `a device with no kernels is refused by name`() {
    nightVisionKernelLimit(kernelsExist = false) shouldBe NIGHT_VISION_KERNEL_LIMIT
    nightVisionKernelLimit(kernelsExist = true, compiles = { false }) shouldBe NIGHT_VISION_KERNEL_LIMIT

    val refused =
      nightVisionResolution(NightVision(), NIGHT_VISION_KERNEL_LIMIT)
        .shouldBeInstanceOf<EffectResolution.Unsupported>()
    refused.specId shouldBe NightVision.ID
    refused.message shouldBe NIGHT_VISION_KERNEL_LIMIT
  }

  private fun assertLandsOnTheSignal(
    width: Int,
    height: Int,
  ) {
    val frame = drawn(ramps(width, height), width, height, SECONDS)
    val params = NightVisionParams.of(HALF_LOOK)
    val tick = FilmSignal.grainTickAt(SECONDS)

    var worst = 0.0
    reads(height).forEach { y ->
      reads(width).forEach { x ->
        val shown = rampsAt(x, y)
        val wanted =
          NightVisionSignal.pixelAt(
            shown[0].toDouble(),
            shown[1].toDouble(),
            shown[2].toDouble(),
            x,
            y,
            width,
            height,
            tick,
            params,
          )
        val read = pixel(frame, width, x, y)
        for (channel in 0 until RGB) worst = max(worst, abs(read[channel] - wanted[channel]))
      }
    }
    withClue("$width by $height") { worst shouldBeLessThan TOLERANCE }
  }

  private fun reads(size: Int): List<Int> = ((0 until size step SPREAD_STRIDE) + (size / 2) + (size - 1)).distinct()

  private fun pixel(
    frame: FloatArray,
    width: Int,
    x: Int,
    y: Int,
  ): FloatArray = FloatArray(CHANNELS) { frame[(y * width + x) * CHANNELS + it] }

  private fun drawn(
    image: CIImage,
    width: Int,
    height: Int,
    seconds: Double,
    look: NightVision = HALF_LOOK,
  ): FloatArray =
    rendered(nightVisionStep(look).apply(image, FrameInfo(attributes(width, height), seconds.seconds)), width, height)

  private fun rendered(
    image: CIImage,
    width: Int,
    height: Int,
  ): FloatArray {
    val values = FloatArray(width * height * CHANNELS)
    val space = ColorSpace.Bt709.createCodeSpace()
    try {
      values.usePinned { pinned ->
        floatContext().render(
          image,
          toBitmap = pinned.addressOf(0),
          rowBytes = (width * CHANNELS * Float.SIZE_BYTES).toLong(),
          bounds = CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()),
          format = kCIFormatRGBAf,
          colorSpace = space,
        )
      }
    } finally {
      CGColorSpaceRelease(space)
    }
    return values
  }

  // Core Image works in half floats unless told otherwise, which would round a code before the look reads it.
  private fun floatContext(): CIContext {
    val options =
      NSDictionary.dictionaryWithObject(
        NSNumber(int = kCIFormatRGBAf),
        forKey = NSString.create(string = checkNotNull(kCIContextWorkingFormat)),
      )
    return CIContext.contextWithOptions(options)
  }

  private fun ramps(
    width: Int,
    height: Int,
  ): CIImage = imageOf(width, height, ::rampsAt)

  private fun imageOf(
    width: Int,
    height: Int,
    pixel: (Int, Int) -> FloatArray,
  ): CIImage {
    val values = FloatArray(width * height * CHANNELS)
    for (y in 0 until height) {
      for (x in 0 until width) {
        pixel(x, y).copyInto(values, (y * width + x) * CHANNELS)
      }
    }
    val space = ColorSpace.Bt709.createCodeSpace()
    try {
      val data =
        values.usePinned { pinned ->
          NSData.create(bytes = pinned.addressOf(0), length = (values.size * Float.SIZE_BYTES).convert())
        }
      return CIImage.imageWithBitmapData(
        data,
        bytesPerRow = (width * CHANNELS * Float.SIZE_BYTES).convert(),
        size = CGSizeMake(width.toDouble(), height.toDouble()),
        format = kCIFormatRGBAf,
        colorSpace = space,
      )
    } finally {
      CGColorSpaceRelease(space)
    }
  }

  // Red runs across and green runs down, each on a step a float holds exactly.
  private fun rampsAt(
    x: Int,
    y: Int,
  ): FloatArray = floatArrayOf(RAMP_START + x / RAMP_STEPS, RAMP_START + y / RAMP_STEPS, BLUE, 1f)

  private fun attributes(
    width: Int,
    height: Int,
  ): Attributes {
    val size = Size(width, height)
    return Attributes(
      inputSize = size,
      outputSize = size,
      layoutSize = size,
      colorSpace = ColorSpace.Bt709,
      hdrTransfer = null,
      frameRate = 30f,
      span = TimeRange(0.seconds, 4.seconds),
    )
  }

  private companion object {
    val HALF_LOOK = NightVision(intensity = 0.5f, seed = 7)
    const val WIDTH = 1280
    const val HEIGHT = 720
    const val CHANNELS = 4
    const val RGB = 3
    const val SECONDS = 1.5
    const val OTHER_SECONDS = 0.25
    const val SPREAD_STRIDE = 37
    const val MOVED_X = 40.0
    const val MOVED_Y = 25.0
    const val BAR = 160
    const val RAMP_START = 0.03125f
    const val RAMP_STEPS = 2048f
    const val BLUE = 0.375f

    // The GPU's single precision against the signal's doubles, well under a code.
    const val TOLERANCE = 1e-3
  }
}

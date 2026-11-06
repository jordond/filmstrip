package dev.jordond.filmstrip.effects.filters.lut

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.FrameInfo
import dev.jordond.filmstrip.effects.color.createCodeSpace
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
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
 * Reads the Core Image step's own output, in code values with no encoder in the way, against [CubeLut.at].
 */
@OptIn(ExperimentalForeignApi::class, InternalFilmstripApi::class)
class LutCoreImageTest {
  private val curved = cubeOf(5)

  @Test
  fun everyPixelReadsLikeTheTable() {
    val gap = worstGap(drawn(Lut(curved), WIDTH, HEIGHT), WIDTH, HEIGHT, curved)
    gap shouldBeLessThan TOLERANCE
  }

  @Test
  fun aPreviewHeightReadsLikeTheTable() {
    worstGap(drawn(Lut(curved), WIDTH / 3, HEIGHT / 3), WIDTH / 3, HEIGHT / 3, curved) shouldBeLessThan TOLERANCE
  }

  @Test
  fun aFineTableReadsLikeTheTable() {
    val table = cubeOf(33)
    worstGap(drawn(Lut(table), WIDTH, HEIGHT), WIDTH, HEIGHT, table) shouldBeLessThan TOLERANCE
  }

  @Test
  fun theLargestTableReadsLikeTheTable() {
    val table = cubeOf(CubeLut.MAX_SIZE)
    val gap = worstGap(drawn(Lut(table), WIDTH, HEIGHT), WIDTH, HEIGHT, table)
    gap shouldBeLessThan TOLERANCE
  }

  @Test
  fun intensityReadsLikeTheBlendedTable() {
    val frame = drawn(Lut(curved, 0.5f), WIDTH, HEIGHT)

    worstGap(frame, WIDTH, HEIGHT, curved.blended(0.5f)) shouldBeLessThan TOLERANCE
    withClue("the full table has to read apart from the blend for this to tell") {
      worstGap(frame, WIDTH, HEIGHT, curved) shouldBeGreaterThan TOLERANCE * 10
    }
  }

  // r*g*b on two points, which a tetrahedral read has as its smallest channel, far from the product.
  @Test
  fun theTableReadsTrilinearlyAndNotTetrahedrally() {
    val cross = cubeOf(2) { red, green, blue -> doubleArrayOf(red, red * green * blue, blue) }
    val image = imageOf(WIDTH, HEIGHT) { _, _ -> floatArrayOf(0.75f, 0.5f, 0.25f, 1f) }

    val read =
      rendered(lutStep(Lut(cross)).apply(image, FrameInfo(attributes(WIDTH, HEIGHT), 1.seconds)), WIDTH, HEIGHT)

    val green = read[(HEIGHT / 2 * WIDTH + WIDTH / 2) * CHANNELS + 1].toDouble()
    abs(green - cross.at(0.75f, 0.5f, 0.25f)[1]) shouldBeLessThan TOLERANCE
  }

  @Test
  fun aPartAlphaPixelReadsItsStraightColourAndAClearBarStaysClear() {
    val image =
      imageOf(WIDTH, HEIGHT) { x, _ ->
        if (x < WIDTH / 2) {
          FloatArray(CHANNELS)
        } else {
          floatArrayOf(HALF_ALPHA * GREY, HALF_ALPHA * GREY, HALF_ALPHA * GREY, HALF_ALPHA)
        }
      }
    val read =
      rendered(lutStep(Lut(curved)).apply(image, FrameInfo(attributes(WIDTH, HEIGHT), 1.seconds)), WIDTH, HEIGHT)

    val bar = (HEIGHT / 2 * WIDTH + WIDTH / 4) * CHANNELS
    (0 until CHANNELS).forEach { abs(read[bar + it]).toDouble() shouldBeLessThan TOLERANCE }
    val picture = (HEIGHT / 2 * WIDTH + WIDTH * 3 / 4) * CHANNELS
    val want = curved.at(GREY, GREY, GREY)
    (0 until RGB).forEach {
      abs(read[picture + it] - want[it] * HALF_ALPHA).toDouble() shouldBeLessThan TOLERANCE
    }
    abs(read[picture + RGB] - HALF_ALPHA).toDouble() shouldBeLessThan TOLERANCE
  }

  // An 8 bit table lands within half a code of this figure and no closer, so a 16 bit one has to read finer.
  @Test
  fun aTableFinerThanEightBitsReadsToTheTable() {
    val fine = cubeOf(2) { _, _, _ -> doubleArrayOf(0.50123, 0.25037, 0.75003) }

    val gap = worstGap(drawn(Lut(fine), WIDTH, HEIGHT), WIDTH, HEIGHT, fine)

    gap shouldBeLessThan TOLERANCE
  }

  @Test
  fun aDeviceThatCannotCompileIsRefusedByName() {
    lutKernelLimit(kernelsExist = false) shouldBe LUT_KERNEL_LIMIT
    lutKernelLimit(kernelsExist = true, compiles = { false }) shouldBe LUT_KERNEL_LIMIT
    lutKernelLimit(kernelsExist = true, compiles = { true }).shouldBeNull()
    lutKernelLimit().shouldBeNull()
  }

  @Test
  fun aRefusedDeviceResolvesToUnsupportedAndNamesTheFilter() {
    val refused = lutResolution(Lut(curved), LUT_KERNEL_LIMIT).shouldBeInstanceOf<EffectResolution.Unsupported>()

    refused.specId shouldBe Lut.ID
    refused.message shouldBe LUT_KERNEL_LIMIT
    lutResolution(Lut(curved), null).shouldBeInstanceOf<EffectResolution.Resolved>()
  }

  private fun worstGap(
    frame: FloatArray,
    width: Int,
    height: Int,
    want: CubeLut,
  ): Double {
    var worst = 0.0
    for (y in 0 until height) {
      for (x in 0 until width) {
        val colour = rampPixel(x, y, width, height)
        val read = want.at(colour[0], colour[1], colour[2])
        for (channel in 0 until RGB) {
          worst = max(worst, abs(frame[(y * width + x) * CHANNELS + channel] - read[channel]).toDouble())
        }
      }
    }
    return worst
  }

  private fun drawn(
    lut: Lut,
    width: Int,
    height: Int,
  ): FloatArray =
    rendered(
      lutStep(lut).apply(
        imageOf(width, height) { x, y -> rampPixel(x, y, width, height) },
        FrameInfo(attributes(width, height), 1.seconds),
      ),
      width,
      height,
    )

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

  // Core Image works in half floats unless told otherwise, whose rounding around 0.5 is wider than a read of 1e-4.
  private fun floatContext(): CIContext {
    val options =
      NSDictionary.dictionaryWithObject(NSNumber(int = kCIFormatRGBAf), forKey = kCIContextWorkingFormat as NSString)
    return CIContext.contextWithOptions(options as Map<Any?, *>)
  }

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

  private fun rampPixel(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
  ): FloatArray {
    val across = 0.125f + 0.75f * x / width
    return floatArrayOf(across, 0.25f + 0.5f * y / height, 1f - across, 1f)
  }

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

  private fun cubeOf(
    size: Int,
    entry: (red: Double, green: Double, blue: Double) -> DoubleArray = { red, green, blue ->
      doubleArrayOf(
        red * red * 0.7 + blue * 0.2,
        green * (1.0 - red * 0.4) + blue * blue * 0.1,
        (1.0 - blue) * 0.5 + green * red * 0.4,
      )
    },
  ): CubeLut =
    CubeLut.parse(
      buildString {
        append("LUT_3D_SIZE ").append(size).append('\n')
        val last = (size - 1).toDouble()
        for (blue in 0 until size) {
          for (green in 0 until size) {
            for (red in 0 until size) {
              val row = entry(red / last, green / last, blue / last)
              append(row[0])
                .append(' ')
                .append(row[1])
                .append(' ')
                .append(row[2])
                .append('\n')
            }
          }
        }
      },
    )

  private companion object {
    const val WIDTH = 256
    const val HEIGHT = 144
    const val CHANNELS = 4
    const val RGB = 3
    const val GREY = 0.4375f
    const val HALF_ALPHA = 0.5f
    const val TOLERANCE = 1e-4
  }
}

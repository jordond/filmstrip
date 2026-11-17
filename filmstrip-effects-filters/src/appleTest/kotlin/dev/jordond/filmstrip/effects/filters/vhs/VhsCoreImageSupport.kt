@file:OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)

package dev.jordond.filmstrip.effects.filters.vhs

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.FrameInfo
import dev.jordond.filmstrip.effects.color.createCodeSpace
import dev.jordond.filmstrip.effects.filters.betamax.Betamax
import dev.jordond.filmstrip.effects.filters.hi8.Hi8
import dev.jordond.filmstrip.effects.filters.internal.KB
import dev.jordond.filmstrip.effects.filters.internal.KR
import dev.jordond.filmstrip.effects.filters.tape.TAPE_PICTURE_KERNEL_LIMIT
import dev.jordond.filmstrip.effects.filters.tape.TapeLook
import dev.jordond.filmstrip.effects.filters.tape.TapePictureFrame
import dev.jordond.filmstrip.effects.filters.tape.TapePictureParams
import dev.jordond.filmstrip.effects.filters.tape.TapePictureRamp
import dev.jordond.filmstrip.effects.filters.tape.TapePictureSignal
import dev.jordond.filmstrip.effects.filters.tape.TapeSettle
import dev.jordond.filmstrip.effects.filters.tape.tapePictureKernelLimit
import dev.jordond.filmstrip.effects.filters.tape.tapePictureResolution
import dev.jordond.filmstrip.effects.filters.tape.tapePictureStep
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.motion.Easing
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
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
import platform.CoreImage.CIColor
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.CoreImage.kCIContextWorkingFormat
import platform.CoreImage.kCIFormatRGBA8
import platform.CoreImage.kCIFormatRGBAf
import platform.Foundation.NSData
import platform.Foundation.NSDictionary
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.create
import platform.Foundation.dictionaryWithObject
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

// What the Core Image tape tests share: a frame drawn through the kernel and read back, and the signal it reads.
internal fun assertRowMatches(
  frame: FloatArray,
  vhs: TapeLook,
  y: Int,
  height: Int,
  width: Int,
  source: (Int) -> DoubleArray,
  columns: List<Int> = READ_COLUMNS,
  seconds: Double = SECONDS,
  span: TimeRange = SPAN,
) {
  val at = TapePictureFrame.of(vhs, seconds, span, null)
  val params = at.params
  val tick = at.tick
  val row = TapePictureSignal.virtualRow(y, height)
  columns.forEach { column ->
    val x = (column + 0.5) / width
    val expected = TapePictureSignal.pixelAt(x, row, tick, vhs.seed, width, params, source)
    val at = (y * width + column) * CHANNELS
    expected.forEachIndexed { channel, value ->
      withClue("row $y column $column channel $channel") {
        frame[at + channel].toDouble() shouldBe (value plusOrMinus TOLERANCE)
      }
    }
  }
}

internal fun drawn(
  vhs: TapeLook,
  image: CIImage,
  width: Int,
  height: Int,
  seconds: Double = SECONDS,
  span: TimeRange = SPAN,
): FloatArray =
  rendered(
    tapePictureStep(vhs).apply(image, FrameInfo(attributes(width, height, span), seconds.seconds)),
    width,
    height,
  )

internal fun rendered(
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
internal fun floatContext(): CIContext {
  val options =
    NSDictionary.dictionaryWithObject(
      NSNumber(int = kCIFormatRGBAf),
      forKey = NSString.create(string = checkNotNull(kCIContextWorkingFormat)),
    )
  return CIContext.contextWithOptions(options)
}

internal fun imageOf(
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

internal fun ramp(
  width: Int,
  height: Int,
): CIImage = imageOf(width, height) { x, _ -> rampPixel(x, width) }

internal fun rampPixel(
  column: Int,
  width: Int,
): FloatArray {
  val t = column.toFloat() / (width - 1)
  return floatArrayOf(RAMP_RED + RAMP_SPAN * t, RAMP_GREEN - RAMP_SPAN * t / 2, RAMP_BLUE + RAMP_SPAN * t, 1f)
}

internal fun rampTexel(column: Int): DoubleArray = DoubleArray(RGB) { rampPixel(column, WIDTH)[it].toDouble() }

internal fun attributes(
  width: Int,
  height: Int,
  span: TimeRange = SPAN,
): Attributes {
  val size = Size(width, height)
  return Attributes(
    inputSize = size,
    outputSize = size,
    layoutSize = size,
    colorSpace = ColorSpace.Bt709,
    hdrTransfer = null,
    frameRate = 30f,
    span = span,
  )
}

internal const val WIDTH = 640
internal const val HEIGHT = 360
internal const val CHANNELS = 4
internal const val RGB = 3
internal const val SECONDS = 1.5
internal const val TOLERANCE = 1e-4
internal val READ_COLUMNS = listOf(0, WIDTH / 4, WIDTH / 2, WIDTH * 3 / 4, WIDTH - 1)
internal val READ_ROWS = listOf(40, HEIGHT / 2, HEIGHT - 3, HEIGHT - 1)
internal val SPAN = TimeRange(0.seconds, 4.seconds)
private const val RAMP_RED = 0.25f
private const val RAMP_GREEN = 0.6f
private const val RAMP_BLUE = 0.3f
private const val RAMP_SPAN = 0.4f

package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.geometry.Size
import kotlin.math.abs
import kotlin.math.log10

/**
 * A frame held as tightly packed RGBA_8888, row-major, with no row padding.
 *
 * @property pixels The frame's bytes, four per pixel.
 * @property size The frame's dimensions in pixels.
 * @throws IllegalArgumentException When [pixels] is not `width * height * 4` bytes long.
 */
public class TestFrame(
  public val pixels: ByteArray,
  public val size: Size,
) {
  init {
    require(pixels.size == size.width * size.height * CHANNELS) {
      "A ${size.width}x${size.height} RGBA frame is ${size.width * size.height * CHANNELS} bytes, " +
        "but ${pixels.size} were given."
    }
  }

  internal companion object {
    const val CHANNELS = 4
  }
}

/**
 * The outcome of comparing two frames.
 *
 * @property psnrDb Peak signal-to-noise ratio in decibels. Infinite when the frames are identical.
 * @property ssim Structural similarity in `0.0..1.0`, where 1.0 is identical.
 * @property meanAbsoluteDifference Mean absolute per-channel difference, in `0.0..255.0`.
 */
public class FrameComparison(
  public val psnrDb: Double,
  public val ssim: Double,
  public val meanAbsoluteDifference: Double,
) {
  override fun toString(): String =
    "PSNR ${format(psnrDb)} dB, SSIM ${format(ssim)}, mean |diff| ${format(meanAbsoluteDifference)}"

  private fun format(value: Double): String {
    if (value.isInfinite()) return "inf"
    val scaled = (value * PRECISION).toLong()
    return "${scaled / PRECISION}.${(abs(scaled) % PRECISION).toString().padStart(DIGITS, '0')}"
  }

  private companion object {
    const val PRECISION = 1000L
    const val DIGITS = 3
  }
}

/**
 * Compares two frames of the same size, perceptually rather than byte for byte.
 *
 * @return The PSNR, SSIM and mean absolute difference between the two frames.
 * @throws IllegalArgumentException When the two frames differ in size.
 */
public fun compareFrames(
  expected: TestFrame,
  actual: TestFrame,
): FrameComparison {
  require(expected.size == actual.size) {
    "Frames differ in size: ${expected.size} against ${actual.size}."
  }

  var squaredError = 0.0
  var absoluteError = 0.0
  val samples = expected.pixels.size.toDouble()

  for (index in expected.pixels.indices) {
    val difference = (expected.pixels[index].toChannel() - actual.pixels[index].toChannel()).toDouble()
    squaredError += difference * difference
    absoluteError += abs(difference)
  }

  val meanSquaredError = squaredError / samples
  val psnr =
    if (meanSquaredError == 0.0) {
      Double.POSITIVE_INFINITY
    } else {
      DECIBEL_SCALE * log10(MAX_CHANNEL * MAX_CHANNEL / meanSquaredError)
    }

  return FrameComparison(
    psnrDb = psnr,
    ssim = structuralSimilarity(expected, actual),
    meanAbsoluteDifference = absoluteError / samples,
  )
}

// Windowed rather than global, so a small badly wrong region is not averaged away.
private fun structuralSimilarity(
  expected: TestFrame,
  actual: TestFrame,
): Double {
  val width = expected.size.width
  val height = expected.size.height
  val expectedLuma = expected.luma()
  val actualLuma = actual.luma()

  var total = 0.0
  var windows = 0

  var top = 0
  while (top + WINDOW <= height) {
    var left = 0
    while (left + WINDOW <= width) {
      total += windowSimilarity(expectedLuma, actualLuma, width, left, top)
      windows++
      left += WINDOW
    }
    top += WINDOW
  }

  // Fall back to the whole frame when it is smaller than one window.
  if (windows == 0) return windowSimilarity(expectedLuma, actualLuma, width, 0, 0)
  return total / windows
}

private fun windowSimilarity(
  expected: DoubleArray,
  actual: DoubleArray,
  width: Int,
  left: Int,
  top: Int,
): Double {
  var expectedSum = 0.0
  var actualSum = 0.0
  var count = 0

  for (y in top until top + WINDOW) {
    for (x in left until left + WINDOW) {
      val index = y * width + x
      if (index >= expected.size) continue
      expectedSum += expected[index]
      actualSum += actual[index]
      count++
    }
  }
  if (count == 0) return 1.0

  val expectedMean = expectedSum / count
  val actualMean = actualSum / count

  var expectedVariance = 0.0
  var actualVariance = 0.0
  var covariance = 0.0

  for (y in top until top + WINDOW) {
    for (x in left until left + WINDOW) {
      val index = y * width + x
      if (index >= expected.size) continue
      val expectedDelta = expected[index] - expectedMean
      val actualDelta = actual[index] - actualMean
      expectedVariance += expectedDelta * expectedDelta
      actualVariance += actualDelta * actualDelta
      covariance += expectedDelta * actualDelta
    }
  }

  expectedVariance /= count
  actualVariance /= count
  covariance /= count

  val numerator = (2 * expectedMean * actualMean + C1) * (2 * covariance + C2)
  val denominator =
    (expectedMean * expectedMean + actualMean * actualMean + C1) *
      (expectedVariance + actualVariance + C2)
  return if (denominator == 0.0) 1.0 else numerator / denominator
}

// Rec. 709 luma, the transfer both platforms' pipelines are normalised to.
private fun TestFrame.luma(): DoubleArray {
  val out = DoubleArray(size.width * size.height)
  for (pixel in out.indices) {
    val base = pixel * TestFrame.CHANNELS
    out[pixel] =
      RED_WEIGHT * pixels[base].toChannel() +
      GREEN_WEIGHT * pixels[base + 1].toChannel() +
      BLUE_WEIGHT * pixels[base + 2].toChannel()
  }
  return out
}

private fun Byte.toChannel(): Int = toInt() and CHANNEL_MASK

private const val CHANNEL_MASK = 0xFF
private const val MAX_CHANNEL = 255.0
private const val DECIBEL_SCALE = 10.0
private const val WINDOW = 8
private const val RED_WEIGHT = 0.2126
private const val GREEN_WEIGHT = 0.7152
private const val BLUE_WEIGHT = 0.0722

// SSIM's stabilising constants, for an 8-bit dynamic range.
private val C1 = (0.01 * MAX_CHANNEL) * (0.01 * MAX_CHANNEL)
private val C2 = (0.03 * MAX_CHANNEL) * (0.03 * MAX_CHANNEL)

package dev.jordond.filmstrip.color

import dev.jordond.filmstrip.InternalFilmstripApi
import kotlin.math.floor

/**
 * Blurs one [plane] of a [width] by [height] frame by the [BlurPlan] for [sigmaFraction], keeping the picture apart
 * from what has no alpha, and answers the blurred plane.
 *
 * This is the blur every backend draws, worked out on the CPU so a backend test can read against it. [plane] holds
 * straight values in the domain the blur runs in, rows from the top, and [alpha] holds each pixel's alpha. Each value
 * is multiplied by its alpha, then the frame is boxed down to the plan's size, blurred across and down by the plan's
 * weights and read back up linearly, with every read held at the frame's edge. The result is divided by the blurred
 * alpha, so a pixel at the edge of the picture keeps the picture's level instead of taking in the empty pixels beside
 * it. A pixel the blurred alpha never reaches answers 0.
 *
 * Alpha is not blurred, a pixel keeps the alpha it came with. The box down spreads its taps evenly over the frame
 * pixels one blurred pixel covers, so a width the plan's factor does not divide reads between pixels.
 *
 * @throws IllegalArgumentException when [plane] or [alpha] does not hold [width] by [height] values.
 */
@InternalFilmstripApi
public fun blurPlane(
  plane: DoubleArray,
  alpha: DoubleArray,
  width: Int,
  height: Int,
  sigmaFraction: Double,
): DoubleArray {
  require(plane.size == width * height && alpha.size == width * height) {
    "A $width by $height plane needs ${width * height} values and alphas, not ${plane.size} and ${alpha.size}."
  }
  val plan = BlurPlan.of(sigmaFraction, width)
  val premultiplied = DoubleArray(plane.size) { plane[it] * alpha[it] }
  val color = plan.blurred(premultiplied, width, height)
  val coverage = plan.blurred(alpha, width, height)
  return DoubleArray(plane.size) { if (coverage[it] > 0.0) color[it] / coverage[it] else 0.0 }
}

private fun BlurPlan.blurred(
  values: DoubleArray,
  width: Int,
  height: Int,
): DoubleArray {
  val blurredHeight = heightOf(height)
  val down =
    Plane(values, width, height)
      .mapRows(this.width) { row -> boxedDown(row, this.width) }
      .mapColumns(blurredHeight) { column -> boxedDown(column, blurredHeight) }
  return down
    .mapRows(this.width) { row -> gaussian(row) }
    .mapColumns(blurredHeight) { column -> gaussian(column) }
    .mapColumns(height) { column -> linearUp(column, height) }
    .mapRows(width) { row -> linearUp(row, width) }
    .values
}

private fun BlurPlan.boxedDown(
  line: DoubleArray,
  size: Int,
): DoubleArray {
  val share = line.size.toDouble() / size
  return DoubleArray(size) { j ->
    (0 until factor).sumOf { k -> line.linearAt((j + (k + 0.5) / factor) * share - 0.5) } /
      factor
  }
}

private fun BlurPlan.gaussian(line: DoubleArray): DoubleArray =
  DoubleArray(line.size) { j ->
    weights[0] * line[j] +
      (1..taps).sumOf { weights[it] * (line[(j + it).coerceAtMost(line.size - 1)] + line[(j - it).coerceAtLeast(0)]) }
  }

private fun linearUp(
  line: DoubleArray,
  size: Int,
): DoubleArray {
  val share = size.toDouble() / line.size
  return DoubleArray(size) { x -> line.linearAt((x + 0.5) / share - 0.5) }
}

// Read linearly at [at], counted in pixels from the centre of the first, and held at both ends.
private fun DoubleArray.linearAt(at: Double): Double {
  val left = floor(at)
  val t = at - left
  val from = this[left.toInt().coerceIn(0, size - 1)]
  val to = this[(left.toInt() + 1).coerceIn(0, size - 1)]
  return from + (to - from) * t
}

private class Plane(
  val values: DoubleArray,
  val width: Int,
  val height: Int,
) {
  fun mapRows(
    newWidth: Int,
    transform: (DoubleArray) -> DoubleArray,
  ): Plane {
    val out = DoubleArray(newWidth * height)
    for (y in 0 until height) {
      transform(values.copyOfRange(y * width, (y + 1) * width)).copyInto(out, y * newWidth)
    }
    return Plane(out, newWidth, height)
  }

  fun mapColumns(
    newHeight: Int,
    transform: (DoubleArray) -> DoubleArray,
  ): Plane {
    val out = DoubleArray(width * newHeight)
    for (x in 0 until width) {
      val column = transform(DoubleArray(height) { values[it * width + x] })
      for (y in 0 until newHeight) out[y * width + x] = column[y]
    }
    return Plane(out, width, newHeight)
  }
}

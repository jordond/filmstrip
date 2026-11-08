package dev.jordond.filmstrip.color

import dev.jordond.filmstrip.InternalFilmstripApi
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The sixteen-bit codes a picture described by [color] stores for the sRGB color [red], [green] and [blue], each a
 * sixteen-bit sRGB code.
 *
 * The codes are the ones whose light, as [pictureLight] reads them through [color], is the sRGB color's own light as
 * [authoredHdrNits] gives it. So a white fill on a PQ or HLG picture sits at [HDR_REFERENCE_WHITE_NITS]. They are found
 * by searching the codes against [pictureLight] itself, so every description it reads, ICC profiles of either kind and
 * CICP code points alike, is answered by the same evaluation the picture's own texels go through. An untagged picture
 * and one described as sRGB get the codes back unchanged.
 *
 * A color the picture's gamut cannot hold, which only happens on a picture narrower than sRGB, clamps to the code
 * whose light lies nearest to it in linear BT.2020, so its channels stop at the end of their range.
 *
 * @return Red, green and blue, each a sixteen-bit code.
 */
@InternalFilmstripApi
public fun pictureCodesFor(
  red: Int,
  green: Int,
  blue: Int,
  color: PictureColor?,
): IntArray {
  val fill = intArrayOf(red, green, blue)
  require(fill.all { it in 0..MAX_SAMPLE }) { "${fill.toList()} are not sixteen-bit codes" }
  if (taggedColor(color) == null) return fill
  val target = authoredHdrNits(red / MAX_SAMPLE_FLOAT, green / MAX_SAMPLE_FLOAT, blue / MAX_SAMPLE_FLOAT)
  val search = CodeSearch(color, DoubleArray(3) { target[it].toDouble() })
  return search.nearest(search.approached(fill))
}

/**
 * Finds the codes whose light through [color] lies nearest [target], in cd/m2.
 *
 * A damped Gauss-Newton walk on the codes as real numbers gets close, its slopes measured across [SLOPE_STEP] codes,
 * and a walk of single codes then settles on the nearest code. Each walk is held to a fixed number of steps, so a
 * profile whose light folds back on itself still ends.
 */
private class CodeSearch(
  private val color: PictureColor?,
  private val target: DoubleArray,
) {
  fun approached(start: IntArray): IntArray {
    val at = DoubleArray(3) { start[it].toDouble() }
    repeat(WALK_STEPS) {
      val codes = codesOf(at)
      val miss = missOf(codes)
      val slopes = slopesAt(codes)
      val step = solved(slopes, miss) ?: return codes
      for (channel in 0 until 3) at[channel] = (at[channel] - step[channel]).coerceIn(0.0, MAX_SAMPLE.toDouble())
      if (step.all { abs(it) < HALF_CODE }) return codesOf(at)
    }
    return codesOf(at)
  }

  fun nearest(start: IntArray): IntArray {
    val codes = start.copyOf()
    var error = errorOf(codes)
    var moved = true
    var rounds = 0
    while (moved && rounds++ < POLISH_ROUNDS) {
      moved = false
      for (channel in 0 until 3) {
        for (direction in DIRECTIONS) {
          val next = codes[channel] + direction
          if (next !in 0..MAX_SAMPLE) continue
          val tried = codes.copyOf().also { it[channel] = next }
          val tryError = errorOf(tried)
          if (tryError < error) {
            tried.copyInto(codes)
            error = tryError
            moved = true
          }
        }
      }
    }
    return codes
  }

  private fun lightOf(codes: IntArray): DoubleArray {
    val samples = ShortArray(4) { if (it < 3) codes[it].toShort() else MAX_SAMPLE.toShort() }
    val nits = pictureLight(color, samples)
    return DoubleArray(3) { nits[it] / HDR_REFERENCE_WHITE_NITS.toDouble() }
  }

  private fun missOf(codes: IntArray): DoubleArray {
    val light = lightOf(codes)
    return DoubleArray(3) { light[it] - target[it] / HDR_REFERENCE_WHITE_NITS }
  }

  private fun errorOf(codes: IntArray): Double = missOf(codes).sumOf { it * it }

  // Each column is how the light moves for one code of its channel, measured over [SLOPE_STEP] codes inward.
  private fun slopesAt(codes: IntArray): DoubleArray {
    val here = lightOf(codes)
    val slopes = DoubleArray(9)
    for (channel in 0 until 3) {
      val step = if (codes[channel] + SLOPE_STEP <= MAX_SAMPLE) SLOPE_STEP else -SLOPE_STEP
      val there = lightOf(codes.copyOf().also { it[channel] += step })
      for (row in 0 until 3) slopes[row * 3 + channel] = (there[row] - here[row]) / step
    }
    return slopes
  }

  // The step that takes [miss] to nothing through [slopes], damped so a channel the light does not move still solves.
  private fun solved(
    slopes: DoubleArray,
    miss: DoubleArray,
  ): DoubleArray? {
    val normal = DoubleArray(9) { at -> (0 until 3).sumOf { slopes[it * 3 + at / 3] * slopes[it * 3 + at % 3] } }
    val damping = (normal[0] + normal[4] + normal[8]) * DAMPING
    for (channel in 0 until 3) normal[channel * 4] += damping
    val pulled = DoubleArray(3) { column -> (0 until 3).sumOf { slopes[it * 3 + column] * miss[it] } }
    return invert(normal)?.let { apply(it, pulled) }?.takeIf { step -> step.all { it.isFinite() } }
  }

  private fun codesOf(at: DoubleArray): IntArray = IntArray(3) { at[it].roundToInt().coerceIn(0, MAX_SAMPLE) }
}

private const val MAX_SAMPLE = 0xFFFF
private const val MAX_SAMPLE_FLOAT = 65535f
private const val SLOPE_STEP = 256
private const val WALK_STEPS = 64
private const val POLISH_ROUNDS = 4096
private const val HALF_CODE = 0.5
private const val DAMPING = 1e-9
private val DIRECTIONS = intArrayOf(1, -1)

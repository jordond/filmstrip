package dev.jordond.filmstrip.picture.heif

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.PictureCrop
import dev.jordond.filmstrip.picture.PictureOrientation
import dev.jordond.filmstrip.picture.bytes.ByteView
import dev.jordond.filmstrip.picture.bytes.MalformedPicture
import kotlin.math.abs

// Reads an AVIF or HEIC clap the way libheif crops a decoded picture by it, with libheif's fraction arithmetic.

/**
 * The texels libheif keeps of a frame of [frame]'s size when it decodes it through this `clap`.
 *
 * @property left The first column kept, counted from the frame's left edge.
 * @property top The first row kept, counted from the frame's top edge.
 * @property right The last column kept.
 * @property bottom The last row kept.
 * @property clamped Whether an edge reached past the frame and was pulled back onto it, which leaves the crop smaller
 * than the size the clean aperture states.
 */
internal class ClapCrop(
  val left: Int,
  val top: Int,
  val right: Int,
  val bottom: Int,
  val clamped: Boolean,
)

/**
 * The [ClapCrop] libheif decodes a frame of [frame]'s size to through [clap], or null when libheif refuses it.
 *
 * The clean aperture sits on the frame's centre, moved by its offsets. Its left edge is rounded towards zero and its
 * top, right and bottom edges to the nearest texel with a half rounding up. Every fraction is first brought into
 * libheif's range, its numerator and denominator halved, dropping any remainder, while the denominator is over 65536
 * and then while the denominator is over 1 and the numerator over 65536. An edge past the frame is pulled back onto it.
 *
 * Null when a denominator is zero, a width or height term or an offset denominator does not fit an Int, the frame has
 * a zero side, or the left edge ends past the right or the top past the bottom. A clap too short for its eight fields
 * throws [MalformedPicture].
 */
internal fun ByteView.clapCrop(
  clap: Box,
  frame: Size,
): ClapCrop? {
  val fields = clapFields(clap) ?: return null
  if (frame.width <= 0 || frame.height <= 0) return null
  val width = narrow(fields[0], fields[1])
  val height = narrow(fields[2], fields[3])
  val horizontal = narrow(fields[4].toInt().toLong(), fields[5])
  val vertical = narrow(fields[6].toInt().toLong(), fields[7])
  val leftEdge = horizontal + narrow(frame.width - 1L, 2) - (width - 1) / 2
  val topEdge = vertical + narrow(frame.height - 1L, 2) - (height - 1) / 2
  val left = leftEdge.roundedDown()
  val top = topEdge.rounded()
  val right = (width - 1 + left).rounded()
  val bottom = (height - 1 + top).rounded()
  val kept =
    ClapCrop(
      left = maxOf(left, 0),
      top = maxOf(top, 0),
      right = minOf(right, frame.width - 1),
      bottom = minOf(bottom, frame.height - 1),
      clamped = left < 0 || top < 0 || right >= frame.width || bottom >= frame.height,
    )
  return kept.takeIf { it.left <= it.right && it.top <= it.bottom }
}

/**
 * This crop of a stored picture narrowed to [crop], which libheif took of the picture this crop holds shown in
 * [orientation] at [shown].
 *
 * Each corner of [crop] is read back to the stored texel it shows through [PictureOrientation.sourceOf], so the result
 * stays a rectangle of the stored picture.
 */
internal fun PictureCrop.narrowedTo(
  crop: ClapCrop,
  orientation: PictureOrientation,
  shown: Size,
): PictureCrop {
  val first = orientation.sourceOf(crop.left, crop.top, shown)
  val last = orientation.sourceOf(crop.right, crop.bottom, shown)
  return PictureCrop(
    left = left + minOf(first.x, last.x),
    top = top + minOf(first.y, last.y),
    size = Size(abs(last.x - first.x) + 1, abs(last.y - first.y) + 1),
  )
}

// A clap's eight fields as unsigned values, or null when one libheif refuses to parse.
private fun ByteView.clapFields(clap: Box): LongArray? {
  val box = within(clap.end)
  val fields = LongArray(CLAP_FIELDS) { box.u32(clap.start + it * 4) }
  if ((0 until CLAP_FIELDS step 2).any { fields[it + 1] == 0L }) return null
  if (CLAP_UNSIGNED_FIELDS.any { fields[it] > Int.MAX_VALUE }) return null
  return fields
}

// A fraction as libheif's Fraction holds it, each sum, difference and quotient brought back into range the way its
// 64-bit constructor does.
private class HeifFraction(
  val numerator: Long,
  val denominator: Long,
) {
  operator fun plus(other: HeifFraction): HeifFraction =
    if (denominator == other.denominator) {
      wide(numerator + other.numerator, denominator)
    } else {
      wide(numerator * other.denominator + other.numerator * denominator, denominator * other.denominator)
    }

  operator fun minus(other: HeifFraction): HeifFraction =
    if (denominator == other.denominator) {
      wide(numerator - other.numerator, denominator)
    } else {
      wide(numerator * other.denominator - other.numerator * denominator, denominator * other.denominator)
    }

  operator fun plus(value: Int): HeifFraction = wide(numerator + value * denominator, denominator)

  operator fun minus(value: Int): HeifFraction = wide(numerator - value * denominator, denominator)

  operator fun div(value: Int): HeifFraction = wide(numerator, denominator * value)

  fun roundedDown(): Int = (numerator / denominator).toInt()

  fun rounded(): Int = ((numerator + denominator / 2) / denominator).toInt()
}

// libheif's 32-bit constructor, which halves both terms, dropping any remainder, until they are inside its range.
private fun narrow(
  numerator: Long,
  denominator: Long,
): HeifFraction {
  var n = numerator
  var d = denominator
  while (d > FRACTION_RANGE || d < -FRACTION_RANGE) {
    n /= 2
    d /= 2
  }
  while (d > 1 && (n > FRACTION_RANGE || n < -FRACTION_RANGE)) {
    n /= 2
    d /= 2
  }
  return HeifFraction(n, d)
}

// libheif's 64-bit constructor, which halves both terms, rounding away from zero, until they fit an Int.
private fun wide(
  numerator: Long,
  denominator: Long,
): HeifFraction {
  var n = numerator
  var d = denominator
  while (n < Int.MIN_VALUE || n > Int.MAX_VALUE || d < Int.MIN_VALUE || d > Int.MAX_VALUE) {
    n = (n + if (n >= 0) 1 else -1) / 2
    d = (d + if (d >= 0) 1 else -1) / 2
  }
  return HeifFraction(n, d)
}

// A clap's width, height and two offsets, each a numerator and then a denominator. Every field but the two offset
// numerators is unsigned and has to fit an Int.
private const val CLAP_FIELDS = 8
private val CLAP_UNSIGNED_FIELDS = intArrayOf(0, 1, 2, 3, 5, 7)

// The bound libheif halves the terms of a fraction down to.
private const val FRACTION_RANGE = 0x10000

package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.color.YcbcrCoding
import dev.jordond.filmstrip.picture.heif.RestatedColor
import dev.jordond.filmstrip.picture.heif.RestatedHeif
import dev.jordond.filmstrip.picture.heif.heifWithRestatedColor

/**
 * The HEIF picture in [bytes] with its codestream and every `nclx` it carries restated to [matrix], through
 * [heifWithRestatedColor].
 *
 * The primaries stated beside [matrix] are the ones [coding] reads, or unspecified when those do not fit in a code
 * point.
 *
 * @param coding What core reads from [bytes] before the restate.
 * @param matrix The ITU-T H.273 matrix coefficients code point to state.
 * @param fullRange The range to state, or null to state the range the picture lands with.
 */
internal fun restated(
  bytes: ByteArray,
  coding: YcbcrCoding,
  matrix: Int,
  fullRange: Boolean? = null,
): RestatedHeif {
  val primaries = coding.primaries.takeIf { it in 0..MAX_CODE_POINT } ?: UNSPECIFIED
  val restate = RestatedColor(matrix, primaries, fullRange, nclx = true)
  return heifWithRestatedColor(bytes) { _, _, _ -> restate }
}

private const val MAX_CODE_POINT = 255
private const val UNSPECIFIED = 2

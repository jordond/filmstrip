package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi

/**
 * Coding-independent code points, the four numbers of ITU-T H.273 a PNG `cICP` chunk or a HEIF `colr nclx` box
 * describes a picture's color with.
 *
 * @property primaries The color primaries code.
 * @property transfer The transfer characteristics code.
 * @property matrix The matrix coefficients code. A PNG only allows 0, RGB as stored.
 * @property fullRange Whether the samples use the full code range rather than the narrow video range.
 */
@InternalFilmstripApi
public class CicpTag(
  public val primaries: Int,
  public val transfer: Int,
  public val matrix: Int,
  public val fullRange: Boolean,
) {
  override fun toString(): String = "$primaries/$transfer/$matrix ${if (fullRange) "full" else "limited"}"
}

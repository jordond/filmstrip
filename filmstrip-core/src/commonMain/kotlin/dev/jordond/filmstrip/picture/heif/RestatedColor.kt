package dev.jordond.filmstrip.picture.heif

import dev.jordond.filmstrip.InternalFilmstripApi

/**
 * The matrix coefficients, color primaries and range [heifWithRestatedColor] states in place of the ones a picture
 * lands with.
 *
 * @property matrix The ITU-T H.273 matrix coefficients code point to state.
 * @property primaries The ITU-T H.273 color primaries code point to state beside [matrix].
 * @property fullRange The range to state, true for full and false for limited, or null to state the range the picture
 * lands with.
 * @property nclx Whether every `colr nclx` the primary item and its tiles carry states [matrix], [primaries] and the
 * range too, for a decoder that reads them from the `nclx` rather than from the codestream.
 */
@InternalFilmstripApi
public data class RestatedColor(
  public val matrix: Int,
  public val primaries: Int,
  public val fullRange: Boolean? = null,
  public val nclx: Boolean = false,
)

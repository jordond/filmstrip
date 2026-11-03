package dev.jordond.filmstrip.color

import dev.jordond.filmstrip.InternalFilmstripApi

/**
 * The video signal an HEVC sequence parameter set carries in its video usability information, or an AV1 sequence
 * header in its color config, each field as the codestream states it.
 *
 * A field the codestream leaves out is null, and a decoder that reads the codestream applies its own default in its
 * place.
 *
 * @property primaries The color primaries code point, or null without a color description.
 * @property transfer The transfer characteristics code point, or null without a color description.
 * @property matrix The matrix coefficients code point, or null without a color description.
 * @property fullRange The full range flag, or null when an HEVC parameter set carries no video signal type. An AV1
 * sequence header always gives one.
 */
@InternalFilmstripApi
public data class CodestreamSignal(
  public val primaries: Int?,
  public val transfer: Int?,
  public val matrix: Int?,
  public val fullRange: Boolean?,
)

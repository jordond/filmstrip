package dev.jordond.filmstrip.effects.overlay

/**
 * Why Android's decoder hands back no frame for an overlay picture, each Android's limit rather than filmstrip's.
 *
 * @property message What the refused overlay reports, naming Android's decoder.
 */
internal enum class DecoderRefusal(
  val message: String,
) {
  /**
   * A picture in a format this API level does not decode, or one too damaged for its decoder to read.
   */
  NoFrame(
    "Android's decoder on this API level hands back no picture for the overlay image. It decodes PNG, JPEG, WebP, " +
      "GIF and BMP, HEIC from API 28 and AVIF from API 31, and nothing it cannot parse.",
  ),

  /**
   * A ten-bit AVIF decoded on API 33, where Android's AVIF decoder hands back no frame for it.
   */
  AvifTenBitNoFrame(
    "Android's AVIF decoder on API 33 hands back no frame for a ten-bit AVIF, so this overlay picture cannot be " +
      "drawn on this API level.",
  ),

  /**
   * A twelve-bit HEIC, which Android's HEIC decoder on API 30, 31 and 33 to 37 hands back no frame for. API 28 and 29
   * are given API 30's answer and API 32 is given API 31's.
   */
  HeicTwelveBitNoFrame(
    "Android's HEIC decoder hands back no frame for a twelve-bit HEIC on API 30, 31 and 33 to 37, and API 28, 29 and " +
      "32 are taken to do as those do, so this overlay picture cannot be drawn on this API level.",
  ),

  /**
   * A twelve-bit AVIF on any API level but 36. Android's AVIF decoder on API 31, 33 and 34 hands back no frame for it
   * and on API 35 and 37 hands back the wrong colors, so it is refused before the decoder sees it. API 32 is given API
   * 31's answer.
   */
  AvifTwelveBit(
    "Android's AVIF decoder draws a twelve-bit AVIF only on API 36. On API 31, 33 and 34 it hands back no frame " +
      "for one and on API 35 and 37 the wrong colors, and API 32 is taken to do as API 31 does, so this overlay " +
      "picture cannot be drawn on this API level.",
  ),
}

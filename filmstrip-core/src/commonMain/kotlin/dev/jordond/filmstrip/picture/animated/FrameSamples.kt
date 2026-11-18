package dev.jordond.filmstrip.picture.animated

import dev.jordond.filmstrip.InternalFilmstripApi

/**
 * The straight alpha samples of a rectangle of texels, the layout a frame is composited and handed to a backend in.
 *
 * [rgba] holds four unsigned 16-bit samples a texel, red, green, blue then alpha, row by row from the top left. Read
 * one with `toInt() and 0xFFFF`. A picture stored in 8 bits has every sample multiplied by 257, so its full range is
 * `0xFFFF` whatever the depth, and [sixteenBit] says whether the low byte carries anything.
 *
 * @property width The width in texels.
 * @property height The height in texels.
 * @property rgba The samples of every texel.
 * @property sixteenBit Whether the file the samples come from stores 16 bits a sample.
 * @throws IllegalArgumentException when [rgba] is not four samples for each of the [width] by [height] texels.
 */
@InternalFilmstripApi
public class FrameSamples(
  public val width: Int,
  public val height: Int,
  public val rgba: ShortArray,
  public val sixteenBit: Boolean,
) {
  init {
    require(width >= 0 && height >= 0 && rgba.size.toLong() == width.toLong() * height * FRAME_SAMPLES_PER_TEXEL) {
      "A $width by $height frame holds ${width.toLong() * height * FRAME_SAMPLES_PER_TEXEL} samples, not ${rgba.size}."
    }
  }
}

internal const val FRAME_SAMPLES_PER_TEXEL: Int = 4

package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.geometry.Size

/**
 * A run of text drawn at the size its glyphs cover, plus the plate around them, as straight sixteen-bit samples.
 *
 * @property size The pixel size of the raster.
 * @property samples Red, green, blue and alpha a texel row by row from the top left, as [platedSamples] lays them.
 */
internal class TextSamples(
  val size: Size,
  val samples: ShortArray,
)

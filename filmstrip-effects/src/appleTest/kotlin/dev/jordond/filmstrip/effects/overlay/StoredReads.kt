package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.geometry.Size
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage

/**
 * The stored samples of these pixels as straight sixteen-bit red, green, blue and alpha, top row first, read whole
 * through [codeReader], or null when no context can read them back.
 *
 * Every call reads the picture again, the way the tests check what a decode stored.
 */
internal val OverlayPixels.stored: ShortArray?
  get() = codeReader?.let { codes.storedSamples(size, it) }

/**
 * This image's stored samples as straight sixteen-bit red, green, blue and alpha, top row first, read through
 * [reader] by [StoredBands] and put together into one array.
 *
 * @param bandTexels How many texels one band holds at most. A band is always at least one row.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CIImage.storedSamples(
  size: Size,
  reader: CIContext,
  bandTexels: Int = BAND_TEXELS,
): ShortArray {
  val samples = ShortArray(size.width * size.height * CHANNELS)
  StoredBands(this, size, reader, bandTexels).forEach { row, band ->
    band.copyInto(samples, row * size.width * CHANNELS)
  }
  return samples
}

private const val CHANNELS = 4

package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.picturePeakBytes
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.plus
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGColorSpaceRef
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.CoreImage.kCIFormatRGBAf
import platform.Foundation.NSData
import platform.Foundation.dataWithBytesNoCopy
import platform.posix.free
import platform.posix.malloc
import platform.posix.memcpy
import kotlin.math.roundToInt

/**
 * Reads an image's stored samples back a band of rows at a time, as straight sixteen-bit red, green, blue and alpha,
 * top row first.
 *
 * The reader has no working space, so it hands back the premultiplied floats the image holds, and each color is
 * divided by its alpha here. Every sample comes back as the source holds it, a texel with no alpha coming back black,
 * and an eight-bit code comes back as that code times 257. One band of floats is held and reused across reads.
 *
 * @param bandTexels How many texels one band holds at most. A band is always at least one row.
 */
@OptIn(ExperimentalForeignApi::class)
internal class StoredBands(
  private val image: CIImage,
  private val size: Size,
  private val reader: CIContext,
  bandTexels: Int = BAND_TEXELS,
) {
  /**
   * How many rows one band holds, at least one and at most the image's height.
   */
  val rows: Int = bandRows(size, bandTexels)

  private val floats = FloatArray(rows * size.width * RGBA)
  private val left = image.extent.useContents { origin.x }
  private val top = image.extent.useContents { origin.y + size.height }

  /**
   * Calls [band] with each band's first row and its samples, top band first. The last band holds the rows left over.
   */
  fun forEach(band: (row: Int, samples: ShortArray) -> Unit) {
    var row = 0
    while (row < size.height) {
      val samples = read(row)
      band(row, samples)
      row += samples.size / (size.width * RGBA)
    }
  }

  /**
   * The samples of the band that starts at [row], as many rows as a band holds or as are left, whichever is fewer.
   */
  fun read(row: Int): ShortArray {
    val count = minOf(rows, size.height - row)
    floats.usePinned { pinned ->
      reader.render(
        image = image,
        toBitmap = pinned.addressOf(0),
        rowBytes = (size.width * RGBA * Float.SIZE_BYTES).toLong(),
        bounds = CGRectMake(left, top - row - count, size.width.toDouble(), count.toDouble()),
        format = kCIFormatRGBAf,
        colorSpace = null,
      )
    }
    val samples = ShortArray(count * size.width * RGBA)
    floats.straightInto(samples, 0, samples.size)
    return samples
  }
}

/**
 * Premultiplied red, green, blue and alpha floats for a whole picture, top row first, in memory Core Image is handed
 * without a copy.
 *
 * Bands are written into it a run of rows at a time. [image] hands the memory to the image it makes, which frees it
 * once Core Image lets the image go. [release] frees it when no image is made.
 *
 * @param ceiling The most bytes the draw may hold at its peak, named in the reason it fails with.
 * @throws PictureTooLargeToDraw When the floats cannot be allocated, with the reason [tooLargeToDraw] gives.
 */
@OptIn(ExperimentalForeignApi::class)
internal class PremultipliedFloats(
  private val size: Size,
  ceiling: Long,
) {
  private val bytes = size.width.toLong() * size.height * RGBA * Float.SIZE_BYTES
  private val floats: CPointer<FloatVar> =
    malloc(bytes.convert())?.reinterpret()
      ?: throw PictureTooLargeToDraw(tooLargeToDraw(size.width.toLong(), size.height.toLong(), ceiling))

  /**
   * Copies the first [count] floats of [band] in at the start of [row].
   */
  fun write(
    row: Int,
    band: FloatArray,
    count: Int = band.size,
  ) {
    if (count == 0) return
    band.usePinned { memcpy(floats + row.toLong() * size.width * RGBA, it.addressOf(0), count.toULong() * 4u) }
  }

  /**
   * The floats as a Core Image image in [colorSpace], or taken as the values they hold when [colorSpace] is null.
   */
  @OptIn(BetaInteropApi::class)
  fun image(colorSpace: CGColorSpaceRef?): CIImage =
    CIImage.imageWithBitmapData(
      data = NSData.dataWithBytesNoCopy(floats, bytes.toULong(), freeWhenDone = true),
      bytesPerRow = (size.width * RGBA * Float.SIZE_BYTES).toULong(),
      size = CGSizeMake(size.width.toDouble(), size.height.toDouble()),
      format = kCIFormatRGBAf,
      colorSpace = colorSpace,
    )

  /**
   * Frees the floats. Only called when [image] was not.
   */
  fun release() = free(floats)
}

/**
 * The bytes a picture of [width] by [height] texels holds at the peak of being drawn at its color, as
 * [picturePeakBytes] counts it, or null when that count does not fit in a [Long] or [held] is null.
 *
 * The [held] bytes of the codes it is read from stay held. One band of [StoredBands] is held on the way, the floats
 * read back and the samples made of them and then either the light its texels are written at or two runs of sRGB codes
 * and the floats they are premultiplied into, at most [BAND_TEXEL_BYTES] a texel. The premultiplied floats are made,
 * sixteen bytes a texel. At 48 MP the band and floats come to 818,304,000 bytes, and an eight-bit picture's codes,
 * four bytes a texel, bring the peak to 1,010,304,000.
 */
internal fun drawnPeakBytes(
  width: Long,
  height: Long,
  held: Long?,
  bandTexels: Int = BAND_TEXELS,
): Long? {
  if (width <= 0 || height <= 0) return picturePeakBytes(held, 0L, 0L)
  val band = drawnBandBytes(width, height, bandTexels) ?: return null
  if (width > Long.MAX_VALUE / FLOAT_TEXEL_BYTES / height) return null
  return picturePeakBytes(held = held, band = band, made = width * height * FLOAT_TEXEL_BYTES)
}

/**
 * The bytes one band of [StoredBands] holds while a picture of [width] by [height] texels is drawn at its color, at
 * most [BAND_TEXEL_BYTES] a texel, or null when the picture is too wide or too tall to read in bands.
 *
 * [drawnPeakBytes] counts this band, and a text raster passes it to [textRasterFits] so both count the same peak. A
 * picture with no texels holds no band.
 */
internal fun drawnBandBytes(
  width: Long,
  height: Long,
  bandTexels: Int = BAND_TEXELS,
): Long? {
  if (width <= 0 || height <= 0) return 0L
  if (width > Int.MAX_VALUE / RGBA || height > Int.MAX_VALUE) return null
  return bandRows(Size(width.toInt(), height.toInt()), bandTexels) * width * BAND_TEXEL_BYTES
}

/**
 * Thrown by a draw that would convert a picture too large to hold at its color, carrying the reason
 * [tooLargeToDraw] gives.
 *
 * It is thrown only where the conversion would run, so the step that draws the picture fails with its reason.
 */
internal class PictureTooLargeToDraw(
  reason: String,
) : IllegalStateException(reason)

/**
 * The reason a [width] by [height] picture is refused when drawing it at its color holds more than [ceiling] bytes,
 * or its floats cannot be allocated.
 */
internal fun tooLargeToDraw(
  width: Long,
  height: Long,
  ceiling: Long,
): String =
  "The $width by $height picture is too large to draw at its color. Its premultiplied floats, sixteen bytes a " +
    "texel, the band of samples they are made from and the decode they are read out of must fit in $ceiling bytes " +
    "and in the memory this process can allocate."

/**
 * Writes the first [count] of these premultiplied floats into [samples] at [offset] as straight sixteen-bit samples.
 *
 * A value is held between 0 and 1, and one that is not a number is written as 0, as is the color of a texel whose
 * alpha is 0 or not a number.
 */
internal fun FloatArray.straightInto(
  samples: ShortArray,
  offset: Int,
  count: Int,
) {
  for (texel in 0 until count step RGBA) {
    val alpha = this[texel + ALPHA]
    repeat(RGBA) { channel ->
      val value =
        when {
          channel == ALPHA -> alpha
          alpha > 0f -> this[texel + channel] / alpha
          else -> 0f
        }
      val held = if (value > 0f) minOf(value, 1f) else 0f
      samples[offset + texel + channel] = (held * TOP_SAMPLE).roundToInt().toShort()
    }
  }
}

private fun bandRows(
  size: Size,
  bandTexels: Int,
): Int = (bandTexels / size.width).coerceIn(1, size.height)

private const val RGBA = 4
private const val ALPHA = 3

// A band's floats read back and samples, 24 bytes a texel, then two sRGB codes and their floats, another 24.
private const val BAND_TEXEL_BYTES = 48L

// The texels read back in one band, sixteen megabytes of floats.
internal const val BAND_TEXELS: Int = 1 shl 20

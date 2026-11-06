package dev.jordond.filmstrip.effects.filters.lut

import dev.jordond.filmstrip.InternalFilmstripApi
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A [CubeLut] laid out flat in RGBA8, so any GL version can upload it as a plain 2D texture.
 *
 * Each blue slice is one [size] by [size] tile, in rows of [columns] tiles. The picture holds that grid twice side by
 * side, the left half with the high byte of each entry at 16 bits and the right half with the low byte, and alpha is
 * opaque. A table is read with nearest fetches, so nothing here relies on filtering a texture.
 *
 * @property size The table's grid points along each axis.
 * @property columns How many tiles run across each half.
 * @property width The atlas's width in texels.
 * @property height The atlas's height in texels.
 * @property bytes Four bytes a texel, with the first row at index 0.
 */
@InternalFilmstripApi
public class LutAtlas internal constructor(
  public val size: Int,
  public val columns: Int,
  public val width: Int,
  public val height: Int,
  public val bytes: ByteArray,
)

/**
 * Lays [table] out as a [LutAtlas].
 */
@InternalFilmstripApi
public fun lutAtlas(table: CubeLut): LutAtlas {
  val size = table.size
  val columns = lutAtlasColumns(size)
  val width = lutAtlasWidth(size)
  val height = lutAtlasHeight(size)
  val half = width / 2
  val bytes = ByteArray(width * height * RGBA_BYTES)
  forEachEntry(size) { index, red, green, blue ->
    val x = (blue % columns) * size + red
    val y = (blue / columns) * size + green
    val high = (y * width + x) * RGBA_BYTES
    val low = high + half * RGBA_BYTES
    for (channel in 0 until CHANNELS) {
      val code = (table.entries[index * CHANNELS + channel] * MAX_ENTRY).roundToInt()
      bytes[high + channel] = (code shr BITS_PER_BYTE).toByte()
      bytes[low + channel] = code.toByte()
    }
  }
  for (alpha in CHANNELS until bytes.size step RGBA_BYTES) bytes[alpha] = OPAQUE
  return LutAtlas(size, columns, width, height, bytes)
}

/**
 * The tiles across each half of the atlas of a table of [size] grid points.
 */
@InternalFilmstripApi
public fun lutAtlasColumns(size: Int): Int = ceil(sqrt(size.toDouble())).toInt()

/**
 * The width in texels of the atlas of a table of [size] grid points.
 */
@InternalFilmstripApi
public fun lutAtlasWidth(size: Int): Int = 2 * lutAtlasColumns(size) * size

/**
 * The height in texels of the atlas of a table of [size] grid points.
 */
@InternalFilmstripApi
public fun lutAtlasHeight(size: Int): Int {
  val columns = lutAtlasColumns(size)
  return (size + columns - 1) / columns * size
}

/**
 * Why the atlas of a table of [size] grid points does not fit a GL context's `MAX_TEXTURE_SIZE`, or null when it does.
 *
 * This is the GL context's limit, so it refuses by name rather than drawing a smaller table.
 */
@InternalFilmstripApi
public fun lutAtlasLimit(
  size: Int,
  maxTextureSize: Int,
): String? {
  val width = lutAtlasWidth(size)
  val height = lutAtlasHeight(size)
  return if (width <= maxTextureSize && height <= maxTextureSize) {
    null
  } else {
    "A LUT of size $size needs a ${width}x$height texture, and this GL context's MAX_TEXTURE_SIZE is $maxTextureSize."
  }
}

/**
 * What a table entry's 16 bits count up to.
 */
internal const val MAX_ENTRY: Float = 65535f

private const val RGBA_BYTES = 4
private const val BITS_PER_BYTE = 8
private const val OPAQUE: Byte = -1

package dev.jordond.filmstrip.picture

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size

/**
 * The way a picture's stored texels are turned and mirrored to show it the right way up, numbered as EXIF numbers
 * its orientation tag.
 *
 * [sourceOf] maps each shown texel back to the stored one it reads, and [orientedSamples] lays a whole picture out the
 * way it is shown through that map.
 *
 * @property exif The EXIF orientation tag value, 1 to 8.
 */
@InternalFilmstripApi
public enum class PictureOrientation(
  public val exif: Int,
) {
  /**
   * Shown as stored.
   */
  Normal(1),

  /**
   * Mirrored left to right.
   */
  FlipHorizontal(2),

  /**
   * Turned half a turn.
   */
  Rotate180(3),

  /**
   * Mirrored top to bottom.
   */
  FlipVertical(4),

  /**
   * Mirrored across the diagonal from the top left corner, so stored rows become shown columns.
   */
  Transpose(5),

  /**
   * Turned a quarter turn clockwise.
   */
  Rotate90(6),

  /**
   * Mirrored across the diagonal from the top right corner.
   */
  Transverse(7),

  /**
   * Turned a quarter turn anticlockwise.
   */
  Rotate270(8),
  ;

  /**
   * Whether the shown picture is the stored one with its width and height swapped, which is true for 5 to 8.
   */
  public val swapsAxes: Boolean get() = exif >= Transpose.exif

  /**
   * The size a picture stored at [stored] is shown at, with the sides swapped when [swapsAxes] is true.
   */
  public fun orientedSize(stored: Size): Size = if (swapsAxes) Size(stored.height, stored.width) else stored

  /**
   * The stored texel the shown texel at [x] and [y] reads, in a picture shown at [orientedSize].
   *
   * Both points count from the top left corner. [orientedSize] is the shown size, which is the stored size with its
   * sides swapped when [swapsAxes] is true.
   */
  public fun sourceOf(
    x: Int,
    y: Int,
    orientedSize: Size,
  ): TexelPosition {
    val width = orientedSize.width
    val height = orientedSize.height
    return TexelPosition(sourceX(x, y, width, height), sourceY(x, y, width, height))
  }

  internal fun sourceX(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
  ): Int =
    when (this) {
      Normal, FlipVertical -> x
      FlipHorizontal, Rotate180 -> width - 1 - x
      Transpose, Rotate90 -> y
      Transverse, Rotate270 -> height - 1 - y
    }

  internal fun sourceY(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
  ): Int =
    when (this) {
      Normal, FlipHorizontal -> y
      Rotate180, FlipVertical -> height - 1 - y
      Transpose, Rotate270 -> x
      Rotate90, Transverse -> width - 1 - x
    }

  @InternalFilmstripApi
  public companion object {
    /**
     * The orientation EXIF numbers [exif], or [Normal] for a value outside 1 to 8.
     */
    public fun ofExif(exif: Int): PictureOrientation = entries.firstOrNull { it.exif == exif } ?: Normal
  }
}

/**
 * A texel position in a picture, counted in whole texels from its top left corner.
 *
 * @property x The column.
 * @property y The row.
 */
@Poko
@InternalFilmstripApi
public class TexelPosition(
  public val x: Int,
  public val y: Int,
)

/**
 * The texels of a picture stored at [stored] as [argb], laid out the way [orientation] shows it.
 *
 * Each shown texel is the stored one [PictureOrientation.sourceOf] names, so the result is
 * [PictureOrientation.orientedSize] of [stored] in size and holds the same texels in another order.
 *
 * @param argb One packed texel per stored position, row by row.
 */
@InternalFilmstripApi
public fun orientedSamples(
  orientation: PictureOrientation,
  argb: IntArray,
  stored: Size,
): IntArray {
  require(argb.size == stored.width * stored.height) {
    "${argb.size} texels do not fill ${stored.width}x${stored.height}"
  }
  if (orientation == PictureOrientation.Normal) return argb.copyOf()
  val shown = orientation.orientedSize(stored)
  val oriented = IntArray(argb.size)
  var at = 0
  for (y in 0 until shown.height) {
    for (x in 0 until shown.width) {
      val source = orientation.sourceIndex(x, y, shown, stored)
      oriented[at++] = argb[source]
    }
  }
  return oriented
}

/**
 * The samples of a picture stored at [stored] as [rgba], laid out the way [orientation] shows it.
 *
 * The same reordering the packed [orientedSamples] runs, moving the four samples of each texel together.
 *
 * @param rgba Four samples per stored position, row by row, in the order red, green, blue and alpha.
 */
@InternalFilmstripApi
public fun orientedSamples(
  orientation: PictureOrientation,
  rgba: ShortArray,
  stored: Size,
): ShortArray {
  require(rgba.size == stored.width * stored.height * CHANNELS) {
    "${rgba.size} samples do not fill ${stored.width}x${stored.height}"
  }
  if (orientation == PictureOrientation.Normal) return rgba.copyOf()
  val shown = orientation.orientedSize(stored)
  val oriented = ShortArray(rgba.size)
  var at = 0
  for (y in 0 until shown.height) {
    for (x in 0 until shown.width) {
      val source = orientation.sourceIndex(x, y, shown, stored) * CHANNELS
      rgba.copyInto(oriented, at, source, source + CHANNELS)
      at += CHANNELS
    }
  }
  return oriented
}

private fun PictureOrientation.sourceIndex(
  x: Int,
  y: Int,
  shown: Size,
  stored: Size,
): Int = sourceY(x, y, shown.width, shown.height) * stored.width + sourceX(x, y, shown.width, shown.height)

private const val CHANNELS = 4

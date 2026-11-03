package dev.jordond.filmstrip.picture

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size

/**
 * A rectangle of whole texels in a stored picture, ahead of any turn or mirror it is shown with.
 *
 * A backend cuts the decoded picture to it with [croppedSamples], and then lays what is left out with
 * [orientedSamples], so the crop always reads the stored picture.
 *
 * @property left The first column kept, counted from the stored picture's left edge.
 * @property top The first row kept, counted from the stored picture's top edge.
 * @property size How many columns and rows are kept.
 */
@Poko
@InternalFilmstripApi
public class PictureCrop(
  public val left: Int,
  public val top: Int,
  public val size: Size,
) {
  /**
   * Whether every texel this keeps lies inside a picture stored at [stored], so [croppedSamples] can cut it.
   */
  public fun liesWithin(stored: Size): Boolean =
    left >= 0 && top >= 0 && size.width > 0 && size.height > 0 &&
      left + size.width <= stored.width && top + size.height <= stored.height

  /**
   * Whether this keeps the whole of a picture stored at [stored], so cutting it changes nothing.
   */
  public fun keepsAll(stored: Size): Boolean = left == 0 && top == 0 && size == stored

  /**
   * What a backend reports when its decoder hands back a picture of [decoded]'s size that this does not lie within, so
   * it cannot be cropped the way libheif crops it. It is one sentence, printed as it is.
   */
  public fun refusalFor(decoded: Size): String =
    "The decoder handed back this picture at ${decoded.width}x${decoded.height}, which holds no " +
      "${size.width}x${size.height} crop at $left,$top for its clean aperture, so it cannot be cropped the way " +
      "libheif crops it."
}

/**
 * The texels of a picture stored at [stored] as [argb] that [crop] keeps, row by row, [PictureCrop.size] in size.
 *
 * Throws [IllegalArgumentException] when [argb] does not fill [stored] or [crop] does not lie within it.
 *
 * @param argb One packed texel per stored position, row by row.
 */
@InternalFilmstripApi
public fun croppedSamples(
  crop: PictureCrop,
  argb: IntArray,
  stored: Size,
): IntArray {
  require(argb.size == stored.width * stored.height) {
    "${argb.size} texels do not fill ${stored.width}x${stored.height}"
  }
  require(crop.liesWithin(stored)) { "$crop does not lie within ${stored.width}x${stored.height}" }
  if (crop.keepsAll(stored)) return argb.copyOf()
  val width = crop.size.width
  val kept = IntArray(width * crop.size.height)
  for (row in 0 until crop.size.height) {
    val from = (crop.top + row) * stored.width + crop.left
    argb.copyInto(kept, row * width, from, from + width)
  }
  return kept
}

/**
 * The samples of a picture stored at [stored] as [rgba] that [crop] keeps, row by row, [PictureCrop.size] in size.
 *
 * The same cut the packed [croppedSamples] makes, moving the four samples of each texel together. Throws
 * [IllegalArgumentException] when [rgba] does not fill [stored] or [crop] does not lie within it.
 *
 * @param rgba Four samples per stored position, row by row, in the order red, green, blue and alpha.
 */
@InternalFilmstripApi
public fun croppedSamples(
  crop: PictureCrop,
  rgba: ShortArray,
  stored: Size,
): ShortArray {
  require(rgba.size == stored.width * stored.height * RGBA_CHANNELS) {
    "${rgba.size} samples do not fill ${stored.width}x${stored.height}"
  }
  require(crop.liesWithin(stored)) { "$crop does not lie within ${stored.width}x${stored.height}" }
  if (crop.keepsAll(stored)) return rgba.copyOf()
  val width = crop.size.width * RGBA_CHANNELS
  val kept = ShortArray(width * crop.size.height)
  for (row in 0 until crop.size.height) {
    val from = ((crop.top + row) * stored.width + crop.left) * RGBA_CHANNELS
    rgba.copyInto(kept, row * width, from, from + width)
  }
  return kept
}

private const val RGBA_CHANNELS = 4

package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size

/**
 * A coded item a HEIF still carries as the thumbnail of its primary item.
 *
 * [HeifStills] writes it as the file's first item with its own `ispe`, an eight-bit three channel `pixi` and its
 * essential [configuration], so a reader that takes the first item it finds reads it in place of the primary item.
 *
 * @property itemType The item's four letter type, `av01` or `hvc1`.
 * @property configuration The whole decoder configuration box, such as an `av1C` or `hvcC`.
 * @property codestream The coded image the item's data holds.
 * @property size The image's width and height.
 */
@InternalFilmstripApi
public class HeifThumbnail(
  public val itemType: String,
  public val configuration: ByteArray,
  public val codestream: ByteArray,
  public val size: Size,
)

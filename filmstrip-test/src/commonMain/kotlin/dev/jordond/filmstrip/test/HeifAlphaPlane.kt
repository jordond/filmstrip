package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size

/**
 * A coded item a HEIF still carries as the alpha plane of its primary item.
 *
 * [HeifStills] writes it with its own `ispe`, a one channel `pixi` of [bitDepth], its essential [configuration] and
 * an `auxC` naming the AVIF alpha type for `av01` and the HEVC one for `hvc1`. Its luma is the alpha.
 *
 * @property itemType The item's four letter type, `av01` or `hvc1`.
 * @property configuration The whole decoder configuration box, such as an `av1C` or `hvcC`.
 * @property codestream The coded image the item's data holds.
 * @property size The image's width and height, which is the primary item's or, in a grid, a tile's.
 * @property premultiplied Whether the file records the primary item's color as multiplied by this alpha.
 * @property bitDepth The bits a sample `pixi` declares.
 */
@InternalFilmstripApi
public class HeifAlphaPlane(
  public val itemType: String,
  public val configuration: ByteArray,
  public val codestream: ByteArray,
  public val size: Size,
  public val premultiplied: Boolean = false,
  public val bitDepth: Int = 8,
)

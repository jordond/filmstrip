package dev.jordond.filmstrip.picture.heif

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.PictureContainer
import dev.jordond.filmstrip.picture.bytes.ByteView
import dev.jordond.filmstrip.picture.bytes.deferring
import dev.jordond.filmstrip.picture.bytes.fourcc
import dev.jordond.filmstrip.picture.container

/**
 * An `iovl` item's layout, the images it is derived from drawn in order over a canvas of [size] painted [fill].
 *
 * @property inputs The images, in the order the overlay's `dimg` reference lists them, so each later one is drawn over
 * the ones before it.
 * @property size The width and height of the canvas.
 * @property fill The color of every texel no input covers.
 */
@InternalFilmstripApi
public class HeifOverlay(
  public val inputs: List<HeifOverlayInput>,
  public val size: Size,
  public val fill: HeifCanvasFill,
) {
  /**
   * Whether some texel of the canvas is covered by no input and so shows [fill].
   *
   * Every edge of every input splits the canvas into cells, and each cell is checked against the inputs on its own.
   */
  public val fillShown: Boolean by lazy { !covered() }

  private fun covered(): Boolean {
    val columns = edges(size.width) { listOf(it.x, it.x + it.size.width) }
    val rows = edges(size.height) { listOf(it.y, it.y + it.size.height) }
    return columns.zipWithNext().all { (left, right) ->
      rows.zipWithNext().all { (top, bottom) ->
        inputs.any { it.x <= left && it.x + it.size.width >= right && it.y <= top && it.y + it.size.height >= bottom }
      }
    }
  }

  // The canvas's own edges at 0 and [side] with every input edge [of] gives that falls between them, in order.
  private fun edges(
    side: Int,
    of: (HeifOverlayInput) -> List<Int>,
  ): List<Int> = (inputs.flatMap(of).filter { it in 1 until side } + 0 + side).distinct().sorted()
}

/**
 * One image an `iovl` item draws, placed with its top left [x] and [y] texels from the canvas's top left.
 *
 * @property item The id of the image's item.
 * @property x How far right of the canvas's left edge the image starts, negative when it starts left of it.
 * @property y How far below the canvas's top edge the image starts, negative when it starts above it.
 * @property size The width and height of the image, as its `ispe` and any `clap` give it.
 */
@InternalFilmstripApi
public class HeifOverlayInput(
  public val item: Long,
  public val x: Int,
  public val y: Int,
  public val size: Size,
)

/**
 * The canvas color an `iovl` item records, sixteen bits a channel.
 *
 * @property red The red channel, an sRGB code from 0 to 65535.
 * @property green The green channel, an sRGB code from 0 to 65535.
 * @property blue The blue channel, an sRGB code from 0 to 65535.
 * @property alpha The opacity, from 0 for clear to 65535 for opaque.
 */
@InternalFilmstripApi
public class HeifCanvasFill(
  public val red: Int,
  public val green: Int,
  public val blue: Int,
  public val alpha: Int,
) {
  /**
   * Whether the fill is fully opaque, so a texel showing it hides whatever the picture is drawn over.
   *
   * It is opaque only at an [alpha] of 65535.
   */
  public val opaque: Boolean
    get() = alpha == WIDE_MAX

  private companion object {
    const val WIDE_MAX = 65535
  }
}

/**
 * The layout of the `iovl` item [bytes] carry as their primary item, or null when they are not an AVIF or HEIC or
 * their primary item is not an `iovl`.
 *
 * The inputs come from the primary item's `dimg` reference, each sized by its own properties, and the canvas color,
 * size and offsets from the overlay's own data. A file that is truncated or malformed, whose overlay data is not where
 * `iloc` places it, is not version 0, holds fewer offsets than it has inputs or has an input with no size carries none.
 */
@InternalFilmstripApi
public fun heifPrimaryOverlay(bytes: ByteArray): HeifOverlay? {
  val picture = ByteView(bytes, bytes.size)
  if (picture.container() != PictureContainer.Heif) return null
  return deferring { picture.primaryOverlay() }
}

private fun ByteView.primaryOverlay(): HeifOverlay? {
  val parts = metaParts() ?: return null
  val heif = within(parts.meta.end)
  val primary = parts.primary ?: return null
  val iref = parts.iref ?: return null
  val types = parts.iinf?.let { heif.within(it.end).itemTypes(it) }.orEmpty()
  if (types[primary] != IOVL) return null
  val items = heif.within(iref.end).tilesOf(primary, iref) ?: return null
  val (start, stop) = firstExtent(parts.locations ?: return null, parts.idat, primary) ?: return null
  val data = within(stop)
  if (data.u8(start) != 0) return null
  val wide = data.u8(start + 1) and 1 == 1
  val field = if (wide) WIDE_FIELD else NARROW_FIELD
  val fill = HeifCanvasFill(data.u16(start + 2), data.u16(start + 4), data.u16(start + 6), data.u16(start + 8))
  val sides = start + FILL_END
  val width = data.unsigned(sides, wide)
  val height = data.unsigned(sides + field, wide)
  if (width !in 1..Int.MAX_VALUE || height !in 1..Int.MAX_VALUE) return null
  val inputs =
    items.mapIndexed { index, item ->
      val at = sides + 2 * field + 2 * field * index
      val size = heif.heifListing(parts.ipmas, item, parts.properties)?.let { heif.heifExtentOf(it) } ?: return null
      HeifOverlayInput(item, data.signed(at, wide), data.signed(at + field, wide), size)
    }
  return HeifOverlay(inputs, Size(width.toInt(), height.toInt()), fill)
}

private fun ByteView.unsigned(
  at: Int,
  wide: Boolean,
): Long = if (wide) u32(at) else u16(at).toLong()

private fun ByteView.signed(
  at: Int,
  wide: Boolean,
): Int = if (wide) u32(at).toInt() else u16(at).toShort().toInt()

private val IOVL = fourcc("iovl")
private const val FILL_END = 10
private const val NARROW_FIELD = 2
private const val WIDE_FIELD = 4

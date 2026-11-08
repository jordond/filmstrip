package dev.jordond.filmstrip.picture.heif

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.PICTURE_PEAK_CEILING
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
   * The part of each input that lands on the canvas, in the order of [inputs], leaving out an input that lands wholly
   * off it.
   *
   * Edges are counted in [Long], so they never wrap whatever offsets the inputs carry.
   */
  public val shown: List<HeifOverlayCut> by lazy {
    inputs.mapNotNull { input ->
      val left = maxOf(input.x.toLong(), 0L)
      val right = minOf(input.right, size.width.toLong())
      val top = maxOf(input.y.toLong(), 0L)
      val bottom = minOf(input.bottom, size.height.toLong())
      if (left < right && top < bottom) {
        HeifOverlayCut(input, left.toInt(), top.toInt(), Size((right - left).toInt(), (bottom - top).toInt()))
      } else {
        null
      }
    }
  }

  /**
   * The bytes assembling this overlay holds at its peak, or null when that count does not fit in a [Long].
   *
   * Every input is counted at its whole size wherever it lands, and the canvas at [size], each at
   * [ASSEMBLY_TEXEL_BYTES] a texel, held together.
   */
  public val peakBytes: Long?
    get() = assemblyPeakBytes(size, inputs.map { it.size })

  /**
   * Whether some texel of the canvas is covered by no input and so shows [fill].
   *
   * The area the [shown] parts cover together is measured by a sweep across the canvas from left to right. The fill
   * shows when that area is less than the canvas's own. The sweep takes time in proportion to the inputs times the
   * logarithm of their count, so an overlay of any size is checked quickly.
   */
  public val fillShown: Boolean by lazy { coveredArea() < size.width.toLong() * size.height }

  private fun coveredArea(): Long {
    val cut = shown.map { longArrayOf(it.x.toLong(), it.right, it.y.toLong(), it.bottom) }
    if (cut.isEmpty()) return 0L
    val rows =
      cut
        .flatMap { listOf(it[TOP], it[BOTTOM]) }
        .distinct()
        .sorted()
    val edges = cut.flatMap { listOf(Edge(it[LEFT], 1, it), Edge(it[RIGHT], -1, it)) }.sortedBy { it.x }
    val cover = RowCover(rows.toLongArray())
    var area = 0L
    var last = 0L
    for (edge in edges) {
      area += cover.covered() * (edge.x - last)
      last = edge.x
      cover.add(rows.binarySearch(edge.input[TOP]), rows.binarySearch(edge.input[BOTTOM]), edge.change)
    }
    return area
  }

  // Where a cut input starts or stops covering the canvas as the sweep moves right, as a change in how many cover it.
  private class Edge(
    val x: Long,
    val change: Int,
    val input: LongArray,
  )

  // How much of the canvas's height the inputs under the sweep cover, over the bands between [rows], kept as a
  // segment tree so adding or taking away an input costs the logarithm of the band count.
  private class RowCover(
    private val rows: LongArray,
  ) {
    private val counts = IntArray(TREE_NODES_PER_BAND * rows.size)
    private val lengths = LongArray(TREE_NODES_PER_BAND * rows.size)

    fun covered(): Long = lengths[1]

    fun add(
      from: Int,
      to: Int,
      change: Int,
    ) = add(1, 0, rows.size - 1, from, to, change)

    private fun add(
      node: Int,
      low: Int,
      high: Int,
      from: Int,
      to: Int,
      change: Int,
    ) {
      if (to <= low || high <= from) return
      if (from <= low && high <= to) {
        counts[node] += change
      } else {
        val middle = (low + high) / 2
        add(2 * node, low, middle, from, to, change)
        add(2 * node + 1, middle, high, from, to, change)
      }
      lengths[node] =
        when {
          counts[node] > 0 -> rows[high] - rows[low]
          high - low == 1 -> 0L
          else -> lengths[2 * node] + lengths[2 * node + 1]
        }
    }
  }

  private companion object {
    const val LEFT = 0
    const val RIGHT = 1
    const val TOP = 2
    const val BOTTOM = 3
    const val TREE_NODES_PER_BAND = 4
  }
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
) {
  internal val right: Long
    get() = x.toLong() + size.width

  internal val bottom: Long
    get() = y.toLong() + size.height
}

/**
 * The part of one input of an `iovl` item that lands on its canvas, as [HeifOverlay.shown] gives it.
 *
 * [left] and [top] place the part within the input, so cropping the input there to [size] and drawing the crop at [x]
 * and [y] draws the input as the canvas shows it.
 *
 * @property input The input the part is cut from.
 * @property x How far right of the canvas's left edge the part starts.
 * @property y How far below the canvas's top edge the part starts.
 * @property size The width and height of the part.
 */
@InternalFilmstripApi
public class HeifOverlayCut(
  public val input: HeifOverlayInput,
  public val x: Int,
  public val y: Int,
  public val size: Size,
) {
  /**
   * How far right of the input's own left edge the part starts.
   *
   * It is 0 unless the input starts left of the canvas.
   */
  public val left: Int
    get() = (x.toLong() - input.x).toInt()

  /**
   * How far below the input's own top edge the part starts.
   *
   * It is 0 unless the input starts above the canvas.
   */
  public val top: Int
    get() = (y.toLong() - input.y).toInt()

  internal val right: Long
    get() = x.toLong() + size.width

  internal val bottom: Long
    get() = y.toLong() + size.height
}

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
 * What reading an `iovl` primary item comes to, as [heifPrimaryOverlay] gives it.
 *
 * A [Read] result holds a layout every backend can assemble under [PICTURE_PEAK_CEILING]. A [Refused] one holds the
 * sentence a backend reports instead, and nothing is assembled.
 */
@InternalFilmstripApi
public sealed interface HeifOverlayRead {
  /**
   * The overlay's layout, within every bound the read checks.
   *
   * @property overlay The layout.
   */
  @InternalFilmstripApi
  public class Read(
    public val overlay: HeifOverlay,
  ) : HeifOverlayRead

  /**
   * An overlay that is well formed but past a bound, such as [OVERLAY_TOO_LARGE_REASON].
   *
   * @property reason Why, in a sentence a person reads.
   */
  @InternalFilmstripApi
  public class Refused(
    public val reason: String,
  ) : HeifOverlayRead
}

/**
 * The layout of the `iovl` item [bytes] carry as their primary item, or null when they are not an AVIF or HEIC or
 * their primary item is not an `iovl`.
 *
 * The inputs come from the primary item's `dimg` reference, each sized by its own properties, and the canvas color,
 * size and offsets from the overlay's own data. A file that is truncated or malformed, whose overlay data is not where
 * `iloc` places it, is not version 0, holds fewer offsets than it has inputs, has no input or has an input with no
 * size carries none.
 *
 * A well formed overlay is refused, checked in this order, when an input is itself a `grid` or `iovl`, when an input's
 * right or bottom edge falls past [Int.MAX_VALUE], or when its [HeifOverlay.peakBytes] pass [PICTURE_PEAK_CEILING].
 * The ceiling is the only bound on how many inputs it draws.
 */
@InternalFilmstripApi
public fun heifPrimaryOverlay(bytes: ByteArray): HeifOverlayRead? {
  val picture = ByteView(bytes, bytes.size)
  if (picture.container() != PictureContainer.Heif) return null
  return deferring { picture.primaryOverlay() }
}

private fun ByteView.primaryOverlay(): HeifOverlayRead? {
  val parts = metaParts() ?: return null
  val heif = within(parts.meta.end)
  val primary = parts.primary ?: return null
  val iref = parts.iref ?: return null
  val types = parts.iinf?.let { heif.within(it.end).itemTypes(it) }.orEmpty()
  if (types[primary] != IOVL) return null
  return overlay(primary, iref, parts, types)
}

// The layout of iovl item [item], its inputs from the dimg reference in [iref] and its canvas and offsets from its
// data, refused past the bounds heifPrimaryOverlay names, or null when it is malformed the ways heifPrimaryOverlay
// names.
internal fun ByteView.overlay(
  item: Long,
  iref: Box,
  parts: MetaParts,
  types: Map<Long, Int>,
): HeifOverlayRead? {
  val heif = within(parts.meta.end)
  val items = heif.within(iref.end).tilesOf(item, iref) ?: return null
  if (items.isEmpty()) return null
  if (items.any { types[it] == GRID || types[it] == IOVL }) return HeifOverlayRead.Refused(OVERLAY_NESTED_INPUT_REASON)
  val (start, stop) = firstExtent(parts.locations ?: return null, parts.idat, item) ?: return null
  val data = within(stop)
  if (data.u8(start) != 0) return null
  val wide = data.u8(start + 1) and 1 == 1
  val field = if (wide) WIDE_FIELD else NARROW_FIELD
  val fill = HeifCanvasFill(data.u16(start + 2), data.u16(start + 4), data.u16(start + 6), data.u16(start + 8))
  val sides = start + FILL_END
  val width = data.unsigned(sides, wide)
  val height = data.unsigned(sides + field, wide)
  if (width !in 1..Int.MAX_VALUE || height !in 1..Int.MAX_VALUE) return null
  val listings = heif.heifListings(parts.ipmas, items.toSet(), parts.properties)
  val inputs =
    items.mapIndexed { index, input ->
      val at = sides + 2 * field + 2 * field * index
      val size = listings[input]?.let { heif.heifExtentOf(it) } ?: return null
      HeifOverlayInput(input, data.signed(at, wide), data.signed(at + field, wide), size)
    }
  if (inputs.any { it.right > Int.MAX_VALUE || it.bottom > Int.MAX_VALUE }) {
    return HeifOverlayRead.Refused(OVERLAY_INPUT_OUT_OF_RANGE_REASON)
  }
  val overlay = HeifOverlay(inputs, Size(width.toInt(), height.toInt()), fill)
  val peak = overlay.peakBytes
  if (peak == null || peak > PICTURE_PEAK_CEILING) return HeifOverlayRead.Refused(OVERLAY_TOO_LARGE_REASON)
  return HeifOverlayRead.Read(overlay)
}

private fun ByteView.unsigned(
  at: Int,
  wide: Boolean,
): Long = if (wide) u32(at) else u16(at).toLong()

private fun ByteView.signed(
  at: Int,
  wide: Boolean,
): Int = if (wide) u32(at).toInt() else u16(at).toShort().toInt()

/**
 * The reason an overlay one of whose inputs is itself a `grid` or `iovl` is refused.
 *
 * Every input is checked by its item type before any of its data is read.
 */
@InternalFilmstripApi
public const val OVERLAY_NESTED_INPUT_REASON: String =
  "The overlay image is an image overlay that draws another grid or image overlay, which filmstrip does not put " +
    "together, so this overlay picture cannot be drawn."

/**
 * The reason an overlay with an input whose right or bottom edge falls past [Int.MAX_VALUE] is refused.
 *
 * The edge is an input's offset plus its size, counted in [Long] so it never wraps.
 */
@InternalFilmstripApi
public const val OVERLAY_INPUT_OUT_OF_RANGE_REASON: String =
  "The overlay image is an image overlay that places an image past the farthest offset a picture can hold, so this " +
    "overlay picture cannot be drawn."

/**
 * The reason an overlay whose [HeifOverlay.peakBytes] pass [PICTURE_PEAK_CEILING] is refused.
 *
 * It names the ceiling in bytes, the same one every other overlay picture is held under.
 */
@InternalFilmstripApi
public const val OVERLAY_TOO_LARGE_REASON: String =
  "The overlay image is an image overlay whose canvas and images come to more than $PICTURE_PEAK_CEILING bytes to " +
    "put together, the most one picture may hold, so this overlay picture cannot be drawn."

internal val IOVL = fourcc("iovl")
private const val FILL_END = 10
private const val NARROW_FIELD = 2
private const val WIDE_FIELD = 4

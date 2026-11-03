package dev.jordond.filmstrip.ffmpeg.internal.picture

import dev.jordond.filmstrip.picture.heif.HeifCanvasFill
import dev.jordond.filmstrip.picture.heif.HeifGrid
import dev.jordond.filmstrip.picture.heif.HeifOverlay
import dev.jordond.filmstrip.picture.heif.heifPrimaryGrid
import dev.jordond.filmstrip.picture.heif.heifPrimaryOverlay
import dev.jordond.filmstrip.picture.heifPrimaryIsTiled
import kotlin.math.roundToInt

/**
 * The ffmpeg arguments that take an AVIF or HEIC's primary image through a filter graph, or why it cannot be taken.
 */
internal sealed interface PrimaryGraph {
  /**
   * The graph arguments, which go after the input and before the output.
   *
   * @property arguments The arguments, a `-vf` or `-filter_complex` and its graph, with anything the graph needs.
   */
  class Graph(
    val arguments: List<String>,
  ) : PrimaryGraph

  /**
   * A primary image this backend cannot put together.
   *
   * @property reason Why, in a sentence a person reads.
   */
  class Refused(
    val reason: String,
  ) : PrimaryGraph
}

/**
 * How the primary image of a picture whose encoded [bytes] are given, or null when this process cannot read them,
 * goes through [filter].
 *
 * A `grid` goes through [assembledGrid] and an `iovl` through [assembledOverlay], each a complex graph that maps every
 * input by its item id. An `iovl`'s inputs are turned into RGB before they are drawn when [rgb] is set, which is how a
 * picture that comes back as RGB is read. One derived from its inputs any other way is refused, since ffmpeg would bind the graph to one
 * input alone. Every other picture goes through a simple graph, whose input is the stream ffmpeg picks for its output,
 * and for a HEIF file that is the primary item rather than a thumbnail written ahead of it.
 */
internal fun primaryGraph(
  bytes: ByteArray?,
  filter: String,
  rgb: Boolean,
): PrimaryGraph {
  if (bytes == null) return PrimaryGraph.Graph(listOf("-vf", filter))
  heifPrimaryGrid(bytes)?.let { return PrimaryGraph.Graph(listOf("-filter_complex", assembledGrid(it, filter))) }
  heifPrimaryOverlay(bytes)?.let {
    val graph = assembledOverlay(it, filter, rgb)
    return PrimaryGraph.Graph(listOf("-filter_complex_threads", "1", "-filter_complex", graph))
  }
  if (heifPrimaryIsTiled(bytes)) return PrimaryGraph.Refused(UNASSEMBLED_REASON)
  return PrimaryGraph.Graph(listOf("-vf", filter))
}

/**
 * The filter graph that puts [grid]'s tiles together and runs the assembled picture through [filter].
 *
 * Each tile is mapped by its id, which is the id ffmpeg gives the stream it reads the tile as, and stacked in rows by
 * [HeifGrid.columns]. The whole is cropped to [HeifGrid.size] from its top left before [filter] runs. No other stream
 * the file lists, such as a thumbnail, is an input.
 */
internal fun assembledGrid(
  grid: HeifGrid,
  filter: String,
): String {
  val inputs = grid.tiles.joinToString("") { "[0:#$it]" }
  val layout =
    grid.tiles.indices.joinToString("|") { index ->
      "${offset(index % grid.columns, "w0")}_${offset(index / grid.columns, "h0")}"
    }
  val stacked = if (grid.tiles.size == 1) "" else "xstack=inputs=${grid.tiles.size}:layout=$layout,"
  return "$inputs${stacked}crop=${grid.size.width}:${grid.size.height}:0:0,$filter"
}

/**
 * The filter graph that runs each of [overlay]'s inputs through [filter] and draws them over its canvas.
 *
 * Each input is mapped by its id and goes through [filter] on its own, so no input's subsampled color reaches the
 * canvas beside it. With [rgb] set it is held to eight or sixteen-bit RGB after that, so the canvas is painted in RGB
 * too. The inputs are placed at their offsets by `xstack`, which copies them in the order the overlay lists them, so a
 * later one lands over an earlier one where they meet. The graph only keeps that order when ffmpeg
 * runs it on one thread, so the arguments [primaryGraph] hands over hold it to one. Everything is moved right and down
 * by the farthest an input starts left of or above the canvas, then `pad` grows the frame to the whole canvas and the
 * canvas is cropped back out. Both paint what no input covers [HeifOverlay.fill], through the [canvasColor] ffmpeg
 * draws with.
 */
internal fun assembledOverlay(
  overlay: HeifOverlay,
  filter: String,
  rgb: Boolean,
): String {
  val left = minOf(0, overlay.inputs.minOf { it.x })
  val top = minOf(0, overlay.inputs.minOf { it.y })
  val width = overlay.size.width - left
  val height = overlay.size.height - top
  val color = canvasColor(overlay.fill)
  val pin = if (rgb) ",format=pix_fmts=rgb24|rgb48le" else ""
  val inputs = overlay.inputs.indices.joinToString("") { "[$INPUT$it]" }
  val read = overlay.inputs.withIndex().joinToString("") { (at, input) -> "[0:#${input.item}]$filter$pin[$INPUT$at];" }
  val placed =
    if (overlay.inputs.size == 1) {
      val input = overlay.inputs.single()
      val x = input.x - left
      val y = input.y - top
      "pad=w='max(iw+$x,$width)':h='max(ih+$y,$height)':x=$x:y=$y:color=$color"
    } else {
      val layout = overlay.inputs.joinToString("|") { "${it.x - left}_${it.y - top}" }
      "xstack=inputs=${overlay.inputs.size}:layout=$layout:fill=$color," +
        "pad=w='max(iw,$width)':h='max(ih,$height)':x=0:y=0:color=$color"
    }
  return "$read$inputs$placed,crop=${overlay.size.width}:${overlay.size.height}:${-left}:${-top}"
}

/**
 * The color [fill] is painted in, as ffmpeg spells an opaque one.
 *
 * ffmpeg's limit, not the overlay's: `xstack` and `pad` take eight bits a channel, so each sixteen-bit channel is
 * rounded to the nearest eight-bit code, and they paint it into a frame with no alpha.
 */
internal fun canvasColor(fill: HeifCanvasFill): String {
  val code = { wide: Int -> (wide * BYTE_MAX / WIDE_MAX).roundToInt() }
  return "0x%02X%02X%02X".format(code(fill.red), code(fill.green), code(fill.blue))
}

/**
 * Why an `iovl` picture is not drawn, or null when it can be.
 *
 * Only a canvas color some texel shows matters. ffmpeg paints it into a frame with no alpha, so a fill that is not
 * opaque is refused. A picture whose [planes] route turns its samples into color in core is refused too, since its
 * canvas is painted into those YCbCr samples through a matrix ffmpeg picks, not the picture's.
 */
internal fun overlayFillReason(
  overlay: HeifOverlay,
  planes: Boolean,
): String? =
  when {
    !overlay.fillShown -> null
    !overlay.fill.opaque -> TRANSLUCENT_FILL_REASON
    planes -> PLANES_FILL_REASON
    else -> null
  }

// [count] tiles of [side] along one axis, as an xstack layout expression.
private fun offset(
  count: Int,
  side: String,
): String = if (count == 0) "0" else List(count) { side }.joinToString("+")

internal const val UNASSEMBLED_REASON =
  "The overlay image's primary item is derived from other images in a way filmstrip does not put together on ffmpeg, " +
    "so this overlay picture cannot be drawn."

internal const val TRANSLUCENT_FILL_REASON =
  "The overlay image is an image overlay whose canvas color is not opaque, and ffmpeg paints the canvas into a frame " +
    "with no alpha, so this overlay picture cannot be drawn."

internal const val PLANES_FILL_REASON =
  "The overlay image is an image overlay whose canvas shows, and ffmpeg paints the canvas through a YCbCr matrix the " +
    "picture's own matrix is not, so this overlay picture cannot be drawn."

private const val INPUT = "input"
private const val BYTE_MAX = 255.0
private const val WIDE_MAX = 65535

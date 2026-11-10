package dev.jordond.filmstrip.effects.filters.vhs

import dev.jordond.filmstrip.geometry.NormalizedRect
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where the frame a [Vhs] reads has clear columns, which decides how its tape is read.
 */
internal sealed interface Coverage {
  /**
   * A frame with no clear column, read by a displace and a blur that need no alpha.
   */
  data object Opaque : Coverage

  /**
   * A frame that can be clear anywhere along a row, read exactly across all of it.
   */
  data object Anywhere : Coverage

  /**
   * A frame clear beside its picture, read exactly in the [cores] around the picture's edges and through
   * premultiplied alpha elsewhere. The cores are ordered, even and do not touch.
   */
  class Edges(
    val cores: List<Span>,
  ) : Coverage {
    /**
     * Every column of a frame [width] wide, cut into the cores and the stretches of tape between them in order.
     */
    fun pieces(width: Int): List<Piece> {
      val pieces = mutableListOf<Piece>()
      var at = 0
      cores.forEachIndexed { index, core ->
        if (core.from > at) pieces += Piece(Span(at, core.from), null)
        pieces += Piece(core, index)
        at = core.to
      }
      if (at < width) pieces += Piece(Span(at, width), null)
      return pieces
    }
  }

  /**
   * The columns from [from] up to [to] of a frame.
   */
  class Span(
    val from: Int,
    val to: Int,
  ) {
    val width: Int get() = to - from
  }

  /**
   * A [span] of columns, read exactly as the core at [core], or through the plain tape when null.
   */
  class Piece(
    val span: Span,
    val core: Int?,
  )
}

/**
 * How [opaque], where a frame [width] wide is opaque, says it is read. A column is clear beside the picture only when
 * a whole pixel of it is, the way a canvas counts a bar.
 *
 * Each edge of the picture takes a core [reach] either side, which is every column whose read can land across it.
 */
internal fun coverageOf(
  opaque: NormalizedRect?,
  width: Int,
  reach: Int,
): Coverage {
  if (opaque == null) return Coverage.Anywhere
  val left = (opaque.left * width).roundToInt().coerceIn(0, width)
  val right = (opaque.right * width).roundToInt().coerceIn(0, width)
  val edges = listOfNotNull(left.takeIf { it >= 1 && it < width }, right.takeIf { width - it >= 1 && it > 0 })
  if (edges.isEmpty()) return Coverage.Opaque
  val spans = edges.map { Coverage.Span(max(0, evenDown(it - reach)), min(width, evenUp(it + reach, width))) }
  val merged = mutableListOf<Coverage.Span>()
  spans.sortedBy { it.from }.forEach { span ->
    val last = merged.lastOrNull()
    if (last != null && span.from <= last.to) {
      merged[merged.lastIndex] = Coverage.Span(last.from, max(last.to, span.to))
    } else {
      merged += span
    }
  }
  val all = merged.singleOrNull()?.let { it.from == 0 && it.to == width } ?: false
  return if (all) Coverage.Anywhere else Coverage.Edges(merged)
}

/**
 * How many luma pixels a read can land from its own column, with room for the two columns of a bilinear read and the
 * four luma samples a chroma alpha is the mean of.
 */
internal fun edgeReach(
  params: VhsParams,
  width: Int,
): Int = ceil(params.widestReach * width).toInt() + EDGE_ROOM

/**
 * The columns a `geq` has to be handed to draw [core], which is every column it can read, on the chroma grid.
 */
internal fun edgeWindow(
  core: Coverage.Span,
  width: Int,
  reach: Int,
): Coverage.Span = Coverage.Span(max(0, evenDown(core.from - reach)), min(width, evenUp(core.to + reach, width)))

private fun evenDown(column: Int): Int = column - column % 2

private fun evenUp(
  column: Int,
  width: Int,
): Int = if (column >= width) width else column + column % 2

private const val EDGE_ROOM = 4

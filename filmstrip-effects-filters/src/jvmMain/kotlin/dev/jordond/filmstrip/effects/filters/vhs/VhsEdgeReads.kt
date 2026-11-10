package dev.jordond.filmstrip.effects.filters.vhs

import dev.jordond.filmstrip.effect.FilterBranch
import dev.jordond.filmstrip.effect.FilterNode
import kotlin.math.ceil

/**
 * Writes [VhsSignal.pixelAt]'s read of a frame as one `geq`, tap by tap, for the pixels beside an edge of the picture.
 *
 * A read there divides by alpha at every tap of the blur, which no pass over the whole row can follow, so each tap is
 * the premultiplied mix of the two columns either side, taken straight again, held at the frame's edge and faded to
 * black past it. The luma blur and its wide partner give the halo, and the chroma planes blur on their own tap table
 * with the alpha at chroma's size as the mean of the luma alpha it covers.
 *
 * Only a pixel whose taps can land across an edge at its row's shift runs them. A `geq` takes the branch of an `if` it
 * needs and skips the other, so every other pixel costs a compare. A second `geq` draws a mask of those pixels, and
 * `maskedmerge` lays the exact read over the plain tape through it.
 */
internal class VhsEdgeReads(
  private val nodes: VhsExpressions,
  private val params: VhsParams,
  private val width: Int,
) {
  private val lumaReach = tapReach(params, width, chroma = false)
  private val chromaReach = tapReach(params, width, chroma = true) / 2.0 + EDGE_CHROMA_ROOM

  /**
   * The branches that read each core of [coverage] exactly, lay it over its stretch of [base] and join every stretch
   * across the frame into [into]. The frame is read from [frame]0 and up, one pad for each core's read and one for its
   * mask, which the caller fans out to.
   */
  fun branches(
    coverage: Coverage.Edges,
    frame: String,
    base: String,
    into: String,
    height: Int,
  ): List<FilterBranch> {
    val pieces = coverage.pieces(width)
    val cut = { span: Coverage.Span ->
      FilterNode("crop", "w" to "${span.width}", "h" to "$height", "x" to "${span.from}", "y" to "0")
    }
    val lays =
      pieces.flatMapIndexed { at, piece ->
        val index = piece.core
        if (index == null) {
          listOf(FilterBranch(listOf("$base$at"), listOf(cut(piece.span)), listOf("piece$at")))
        } else {
          val edges = coverage.edgesIn(index)
          val window = edgeWindow(piece.span, edges, width, params)
          listOf(
            FilterBranch(listOf("$base$at"), listOf(cut(piece.span)), listOf("base$index")),
            FilterBranch(
              listOf("$frame$index"),
              listOf(
                cut(window),
                read(window.from, edges),
                cut(
                  Coverage.Span(
                    piece.span.from - window.from,
                    piece.span.to - window.from,
                  ),
                ),
              ),
              listOf("exact$index"),
            ),
            FilterBranch(
              listOf("$frame${index}Mask"),
              listOf(cut(piece.span), mask(piece.span.from, edges)),
              listOf("mask$index"),
            ),
            FilterBranch(
              listOf("base$index", "exact$index", "mask$index"),
              listOf(FilterNode("maskedmerge", "planes" to "$MERGED_PLANES")),
              listOf("piece$at"),
            ),
          )
        }
      }
    val fanOut = FilterBranch(listOf(base), emptyList(), pieces.indices.map { "$base$it" })
    val joined =
      FilterBranch(
        pieces.indices.map { "piece$it" },
        listOf(FilterNode("hstack", "inputs" to "${pieces.size}")),
        listOf(into),
      )
    return listOf(fanOut) + lays + joined
  }

  /**
   * The pads [branches] reads the frame from, which the caller writes the frame to.
   */
  fun reads(
    coverage: Coverage.Edges,
    frame: String,
  ): List<String> = coverage.cores.indices.flatMap { listOf("$frame$it", "$frame${it}Mask") }

  /**
   * The `geq` that reads every pixel of the frame exactly, for a frame that can be clear anywhere.
   */
  fun everywhere(): FilterNode = read(0, null)

  /**
   * The `geq` for a window that starts [origin] luma pixels into the frame, drawing the pixels beside [edges], or all
   * of them when it is null. [origin] is even, so the window's chroma starts on a sample.
   */
  private fun read(
    origin: Int,
    edges: List<Int>?,
  ): FilterNode {
    val luma = Plane(1.0, origin, LUMA_BLACK, { "lum($it,Y)" }, { "alpha($it,Y)" })
    val chroma = { name: String ->
      Plane(CHROMA_SCALE, origin / 2, CHROMA_ZERO, { "$name($it,Y)" }, ::meanAlpha)
    }
    return FilterNode(
      "geq",
      "lum" to "${nodes.shiftOpening(lumaShift())};if(${near(origin, edges, 1.0)},${lumaTaps(luma)},0)",
      "cb" to
        "${nodes.shiftOpening(
          chromaShift(),
        )};if(${near(origin / 2, edges, CHROMA_SCALE)},${chromaTaps(chroma("cb"))},0)",
      "cr" to
        "${nodes.shiftOpening(
          chromaShift(),
        )};if(${near(origin / 2, edges, CHROMA_SCALE)},${chromaTaps(chroma("cr"))},0)",
      "a" to "0",
    )
  }

  /**
   * The `geq` that draws 255 in each pixel [read] draws and 0 in the rest, for the core that starts [origin] luma pixels
   * into the frame.
   */
  private fun mask(
    origin: Int,
    edges: List<Int>,
  ): FilterNode {
    val luma = "${nodes.shiftOpening(lumaShift())};255*gt(${near(origin, edges, 1.0)},0)"
    val chroma = "${nodes.shiftOpening(chromaShift())};255*gt(${near(origin / 2, edges, CHROMA_SCALE)},0)"
    return FilterNode("geq", "lum" to luma, "cb" to chroma, "cr" to chroma, "a" to "255")
  }

  // A pixel counts the edges its read starts within a blur's reach of. The read starts at the pixel's column less the
  // row's shift, which register 7 holds in this plane's samples. The sum is a count of those edges.
  private fun near(
    origin: Int,
    edges: List<Int>?,
    scale: Double,
  ): String {
    if (edges == null) return "1"
    val reach = if (scale == 1.0) lumaReach.toDouble() else chromaReach
    return edges.joinToString("+") { "lte(abs(X+$origin-ld(7)-${it * scale}),$reach)" }
  }

  private fun lumaShift() = "st(7,ld(8)*$width)"

  private fun chromaShift() = "st(7,(ld(8)+${params.chromaShift})*$width*$CHROMA_SCALE)"

  // The luma blur in register 6 and the wide blur in 9, each as a sum over the table, then the ring they make.
  private fun lumaTaps(plane: Plane): String {
    val near = taps(plane, params.lumaSigma, LUMA_TAPS, LUMA_TAP_STEP, 6)
    val far = taps(plane, params.wideSigma, LUMA_TAPS, LUMA_TAP_STEP, 9)
    val sum = VhsSignal.tapSum(LUMA_TAPS, LUMA_TAP_STEP)
    return "$near;$far;st(6,ld(6)/$sum);st(9,ld(9)/$sum);max(0,0.5+ld(6)+${params.haloGain}*(ld(6)-ld(9)))"
  }

  private fun chromaTaps(plane: Plane): String =
    "${taps(plane, params.chromaSigma, CHROMA_TAPS, CHROMA_TAP_STEP, 6)};" +
      "0.5+ld(6)/${VhsSignal.tapSum(CHROMA_TAPS, CHROMA_TAP_STEP)}"

  private fun taps(
    plane: Plane,
    sigma: Double,
    count: Int,
    step: Double,
    sum: Int,
  ): String {
    val reads = mutableListOf("st($sum,0)", tap(plane, 0.0, sum, 1.0))
    for (index in 1..count) {
      val offset = index * step * sigma * width * plane.scale
      val weight = VhsSignal.tapWeight(index, step)
      reads += tap(plane, offset, sum, weight)
      reads += tap(plane, -offset, sum, weight)
    }
    return reads.joinToString(";")
  }

  // One tap added into register [sum] at [weight]. Registers 1 to 5 hold its position, its fade, its two columns'
  // weights and their sum, and 8 its value. Each `;` step nests a level of the parser, which allows a hundred, so a tap
  // is one term, its steps added together and scaled to nothing.
  private fun tap(
    plane: Plane,
    offset: Double,
    sum: Int,
    weight: Double,
  ): String {
    val picture = width * plane.scale
    val last = ceil(picture) - 1
    val first = "clip(ld(3),0,$last)-${plane.origin}"
    val second = "clip(ld(3)+1,0,$last)-${plane.origin}"
    val black = plane.black
    return listOf(
      "st(1,X+${plane.origin}-ld(7)+($offset))",
      "st(2,clip((ld(1)+0.5)/${plane.scale}+0.5,0,1)*clip($width-(ld(1)+0.5)/${plane.scale}+0.5,0,1))",
      "st(1,clip(ld(1),-0.5,${picture - 0.5}))",
      "st(3,floor(ld(1)))",
      "st(1,ld(1)-ld(3))",
      "st(4,(1-ld(1))*${plane.alphaAt(first)})",
      "st(5,ld(1)*${plane.alphaAt(second)})",
      "st(1,ld(4)+ld(5))",
      "st(8,$black+(if(gt(ld(1),0),(${plane.valueAt(
        first,
      )}*ld(4)+${plane.valueAt(second)}*ld(5))/ld(1),$black)-$black)*ld(2))",
      "st($sum,ld($sum)+$weight*ld(8))",
    ).joinToString("+", "(", ")*0")
  }

  // Chroma's alpha at a sample is the mean of the four luma alphas it covers, as the area scale makes it.
  private fun meanAlpha(column: String): String =
    "(alpha(2*($column),2*Y)+alpha(2*($column)+1,2*Y)+alpha(2*($column),2*Y+1)+alpha(2*($column)+1,2*Y+1))/4"

  // A plane's samples per luma pixel, where its window starts in its own samples, its black code and its two readers,
  // each taking a column of the window.
  private class Plane(
    val scale: Double,
    val origin: Int,
    val black: Int,
    val valueAt: (String) -> String,
    val alphaAt: (String) -> String,
  )

  private companion object {
    const val CHROMA_SCALE = 0.5
    const val EDGE_CHROMA_ROOM = 4
    const val MERGED_PLANES = 7
  }
}

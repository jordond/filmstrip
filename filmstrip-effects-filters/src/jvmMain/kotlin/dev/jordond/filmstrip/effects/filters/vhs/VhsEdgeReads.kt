package dev.jordond.filmstrip.effects.filters.vhs

import dev.jordond.filmstrip.effect.FilterNode
import kotlin.math.ceil

/**
 * Writes [VhsSignal.pixelAt]'s read of a frame as one `geq`, tap by tap, for the columns beside an edge of the picture.
 *
 * A read there divides by alpha at every tap of the blur, which no pass over the whole row can follow, so each tap is
 * the premultiplied mix of the two columns either side, taken straight again, held at the frame's edge and faded to
 * black past it. The luma blur and its wide partner give the halo, and the chroma planes blur on their own tap table
 * with the alpha at chroma's size as the mean of the luma alpha it covers.
 *
 * The `geq` draws a window cut from the frame at [width] luma pixels wide, and only the columns of a core inside that
 * window run the taps. A tap reads a column of the window, which has to hold every column the core can reach.
 */
internal class VhsEdgeReads(
  private val nodes: VhsExpressions,
  private val params: VhsParams,
  private val width: Int,
) {
  /**
   * The `geq` for a window that starts [origin] luma pixels into the frame, drawing the columns from [from] up to [to]
   * of the frame. [origin] is even, so the window's chroma starts on a sample.
   */
  fun read(
    origin: Int,
    from: Int,
    to: Int,
  ): FilterNode {
    val luma = Plane(1.0, origin, LUMA_BLACK, { "lum($it,Y)" }, { "alpha($it,Y)" })
    val chroma = { name: String ->
      Plane(CHROMA_SCALE, origin / 2, CHROMA_ZERO, { "$name($it,Y)" }, ::meanAlpha)
    }
    val shift = "st(7,ld(8)*$width)"
    val chromaShift = "st(7,(ld(8)+${params.chromaShift})*$width*$CHROMA_SCALE)"
    val alpha = "clip(X+$origin-ld(8)*$width,0,${width - 1})-$origin"
    val lumaGate = "between(X+$origin,$from,${to - 1})"
    val chromaGate = "gte(2*(X+${origin / 2})+1,$from)*lt(2*(X+${origin / 2}),$to)"
    return FilterNode(
      "geq",
      "lum" to "${nodes.shiftOpening(shift)};if($lumaGate,${lumaTaps(luma)},0)",
      "cb" to "${nodes.shiftOpening(chromaShift)};if($chromaGate,${chromaTaps(chroma("cb"))},0)",
      "cr" to "${nodes.shiftOpening(chromaShift)};if($chromaGate,${chromaTaps(chroma("cr"))},0)",
      "a" to "${nodes.shiftOpening()};0.5+alpha($alpha,Y)",
    )
  }

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
      "st(8,$black+(if(gt(ld(1),0),(${plane.valueAt(first)}*ld(4)+${plane.valueAt(second)}*ld(5))/ld(1),$black)-$black)*ld(2))",
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
  }
}

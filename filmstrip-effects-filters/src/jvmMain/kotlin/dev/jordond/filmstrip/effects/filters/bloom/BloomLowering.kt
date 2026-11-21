package dev.jordond.filmstrip.effects.filters.bloom

import dev.jordond.filmstrip.effect.FilterBranch
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effects.RgbRoundTrip
import dev.jordond.filmstrip.effects.filters.internal.GlowField
import dev.jordond.filmstrip.effects.filters.internal.GlowGraph
import dev.jordond.filmstrip.effects.filters.internal.KB
import dev.jordond.filmstrip.effects.filters.internal.KR
import dev.jordond.filmstrip.effects.filters.internal.RGB_FORMAT
import dev.jordond.filmstrip.effects.filters.internal.WIDE_FORMAT
import dev.jordond.filmstrip.geometry.Size

/**
 * The filter graph that draws a [Bloom] on ffmpeg, every figure read from [BloomSignal].
 *
 * The fragment opens by converting whatever it is handed to 8 bit limited range BT.709 4:2:0, keeping any alpha, and
 * on to `gbrap`, since the bloom works on red, green and blue. [GlowGraph.light] blurs [BloomSignal.response] of the
 * frame by [GlowField.blurPlan]'s kernel, and [GlowGraph.screened] screens that light at the bloom's gain over the
 * frame on sixteen bit planes, which is [BloomSignal.bloomed]. The frame rounds once to a code before it goes back to
 * `yuva420p` with its alpha as it read, opaque if it had none.
 *
 * Going through `gbrap` and back rounds twice, which is ffmpeg's own limit, and a test pins how close it lands. The
 * bloom reads no clock, so it has no live input and a windowed preview draws the same frame.
 */
internal fun bloomFragment(
  bloom: Bloom,
  frame: Size,
): FilterFragment {
  val params = BloomParams.of(bloom)
  val plan = GlowField.blurPlan(params.sigma, frame.width)
  val toCodes =
    listOf(
      FilterNode("format", "pix_fmts" to WIDE_FORMAT),
      RgbRoundTrip.toCodeStarts(),
      FilterNode("format", "pix_fmts" to RGB_FORMAT),
    ) + RgbRoundTrip.toCodes()
  val opening =
    listOf(
      FilterBranch(listOf(FilterBranch.INPUT), RgbRoundTrip.opening() + RgbRoundTrip.toRgb(), listOf("rgb")),
      FilterBranch(listOf("rgb"), emptyList(), listOf("narrow", "far")),
      FilterBranch(listOf("narrow"), listOf(FilterNode("format", "pix_fmts" to WIDE_FORMAT)), listOf("near")),
    )
  return FilterFragment(
    branches =
      opening +
        GlowGraph.light(plan, frame, BloomExpressions.response(params), "far", "glow") +
        GlowGraph.screened("near", "glow", listOf(GlowGraph.maskOf(params.gain)), toCodes, "bloomed"),
  )
}

/**
 * Writes [BloomSignal]'s functions as ffmpeg expressions.
 */
internal object BloomExpressions {
  /**
   * The `geq` that draws [BloomSignal.response] in each plane, from a frame already boxed to the blur's size.
   */
  fun response(params: BloomParams): FilterNode {
    val luma = "($KR*r(X,Y)+${1.0 - KR - KB}*g(X,Y)+$KB*b(X,Y))/$FULL_SCALE"
    val t = "clip(($luma-${params.threshold})/${params.kneeEnd - params.threshold},0,1)"
    val kept = { plane: String -> "st(0,$t);0.5+$plane(X,Y)*ld(0)*ld(0)*(3-2*ld(0))" }
    return FilterNode("geq", "r" to kept("r"), "g" to kept("g"), "b" to kept("b"))
  }

  private const val FULL_SCALE = 255
}

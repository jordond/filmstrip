package dev.jordond.filmstrip.effects.filters.tape

import dev.jordond.filmstrip.effect.FilterArgument
import dev.jordond.filmstrip.effect.FilterNode

/**
 * The nodes that draw a look while its level moves, which only the frames between the two ends of its ramp use.
 *
 * Every figure a `gblur` or a `lut2` holds is fixed when the graph is built, so the frames at either end of the ramp
 * run the nodes of the constant look at that end, each switched on by the frame's tick, and the frames between run the
 * nodes here instead, which read the level off the frame's own time. Each node of the three is a pass through on the
 * frames it is not switched on for, so every frame is drawn by exactly one of them.
 */
internal class TapePictureRampNodes(
  private val figures: TapePictureFigures,
  private val nodes: TapePictureExpressions,
  private val width: Int,
) {
  private val tick = tickExpression("t")

  /**
   * [node], switched on for the frames at the level the ramp starts at.
   */
  fun before(node: FilterNode): FilterNode = node.switchedOn(figures.before(tick))

  /**
   * [node], switched on for the frames between the two ends of the ramp.
   */
  fun during(node: FilterNode): FilterNode = node.switchedOn(figures.during(tick))

  /**
   * [node], switched on for the frames at the level the ramp ends at.
   */
  fun after(node: FilterNode): FilterNode = node.switchedOn(figures.after(tick))

  /**
   * [node], switched on for every frame the ramp does not run on.
   */
  fun outside(node: FilterNode): FilterNode = node.switchedOn("not(${figures.during(tick)})")

  /**
   * The `geq` that blurs a displaced tape wider than the picture by [margin] either side, and rings its luma, at the
   * level of the frame.
   *
   * Each blur is the signal's own taps over the tape, read off the plane at the offsets the sigma gives, so it is
   * [TapePictureSignal.pixelAt]'s blur and halo with no recursive approximation in between. Register 9 holds the level
   * of the row, 8 the wide sigma, and 6 and 7 the luma blur and the wide blur.
   */
  fun blur(): FilterNode {
    val level = figures.levelAt("ld(2)")
    val unit = figures.unit
    val open = { more: List<String> ->
      "if(X,0,st(2,${tickExpression("T")});st(9,$level)${more.joinToString("") { ";$it" }})"
    }
    val lumaSigma = "(${unit.lumaSigma}*ld(9))"
    val wide = "sqrt($lumaSigma*$lumaSigma+${HALO_SIGMA * HALO_SIGMA})"
    val luma =
      "${open(listOf("st(8,$wide)"))};st(6,${taps(1.0, lumaSigma, LUMA_TAPS, LUMA_TAP_STEP)});" +
        "st(7,${taps(1.0, "ld(8)", LUMA_TAPS, LUMA_TAP_STEP)});" +
        "max(0,0.5+ld(6)+${figures.linear("ld(9)") { haloGain }}*(ld(6)-ld(7)))"
    val chroma = { plane: String ->
      val sigma = "(${unit.chromaSigma}*ld(9))"
      "${open(emptyList())};0.5+${taps(CHROMA_SCALE, sigma, CHROMA_TAPS, CHROMA_TAP_STEP, plane)}"
    }
    return FilterNode(
      "geq",
      "lum" to luma,
      "cb" to chroma("cb"),
      "cr" to chroma("cr"),
      "a" to "alpha(X,Y)",
    )
  }

  /**
   * The `blend` that adds the noise and lifts and desaturates the frame at the level of the frame, the grade of
   * [TapePictureExpressions.grade] as an expression of the frame's time, rounding the sum once the same way.
   *
   * @param stopsWithFrame Whether the frame ending ends this `blend`, for a map that runs on past it.
   */
  fun grade(stopsWithFrame: Boolean = false): FilterNode {
    // The level holds for the whole frame, so each row's first sample works it out into register 0 and the rest of the
    // row reads it. A slice never splits a row, and every slice of a frame writes the same level.
    val open = "if(X,0,st(0,${figures.levelAt(tickExpression("T"))}))"
    val level = "ld(0)"
    val lift = figures.linear(level) { lift }
    val desaturation = figures.linear(level) { desaturation }
    val noise = "(B-$MID_SCALE)/${nodes.lumaGrainScale}*$level"
    val luma = "$open;clip(0.5+$LUMA_BLACK+$LUMA_CODES*$lift+(A+$noise-$LUMA_BLACK)*(1-$lift),0,$FULL_SCALE)"
    val chromaNoise = "(B-$MID_SCALE)/${nodes.chromaGrainScale}*$level"
    val chroma = "$open;clip(0.5+$CHROMA_ZERO+(A-$CHROMA_ZERO+$chromaNoise)*(1-$desaturation),0,$FULL_SCALE)"
    val planes = listOf("c0_expr" to luma, "c1_expr" to chroma, "c2_expr" to chroma, "c3_expr" to "A")
    val options = if (stopsWithFrame) listOf("shortest" to "1") + planes else planes
    return FilterNode("blend", *options.toTypedArray())
  }

  /**
   * [node] switched off from the first tick the level is at its end, when that end is nothing, so the frames after the
   * settle pass through the nodes that draw, which are `geq`, `gblur`, `lut2` and `blend`. A node switched off hands on
   * the first of its inputs, which is the picture.
   */
  fun idle(node: FilterNode): FilterNode {
    if (node.name !in DRAWING) return node
    val running = "lt($tick,${checkNotNull(figures.ramp).endTick})"
    val held = node.arguments.firstOrNull { it.key == "enable" }
    val condition = if (held == null) running else "(${held.value})*$running"
    return FilterNode(node.name, node.arguments.filter { it.key != "enable" } + FilterArgument("enable", condition))
  }

  // The blur of the plane over a table of taps spaced as a share of [sigma], summed as the signal sums them, with the
  // sum over the weights taken off.
  private fun taps(
    scale: Double,
    sigma: String,
    count: Int,
    step: Double,
    plane: String = "lum",
  ): String {
    val reads = mutableListOf("$plane(X,Y)")
    for (index in 1..count) {
      val offset = "${index * step * width * scale}*$sigma"
      val weight = TapePictureSignal.tapWeight(index, step)
      reads += "$weight*($plane(X+$offset,Y)+$plane(X-$offset,Y))"
    }
    return "(${reads.joinToString("+")})/${TapePictureSignal.tapSum(count, step)}"
  }

  private fun FilterNode.switchedOn(condition: String): FilterNode =
    FilterNode(name, arguments + FilterArgument("enable", condition))

  private companion object {
    const val CHROMA_SCALE = 0.5
    val DRAWING = setOf("geq", "gblur", "lut2", "blend")
  }
}

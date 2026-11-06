package dev.jordond.filmstrip.effects.filters.film

import dev.jordond.filmstrip.effect.FilterBranch
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effects.filters.internal.HASH_RANGE
import dev.jordond.filmstrip.effects.filters.internal.HashExpression
import dev.jordond.filmstrip.effects.filters.internal.KB
import dev.jordond.filmstrip.effects.filters.internal.KR
import dev.jordond.filmstrip.effects.filters.internal.RgbRoundTrip
import dev.jordond.filmstrip.geometry.Size

/**
 * The filter graph that draws a [Film] on ffmpeg, every figure read from [FilmSignal].
 *
 * The fragment opens by converting whatever it is handed to 8 bit limited range BT.709 4:2:0, keeping any alpha,
 * because its expressions are written in those codes. Halation runs in `gbrap`, since it adds to red, green and blue,
 * with the matrix named both ways. A branch takes the frame's alpha into its light, boxes it to
 * [FilmSignal.halationBlurPlan]'s size, draws the response with `geq`, blurs it with `gblur`, and widens it back to be
 * added through `lut2`. Grain is a `geq` map of [FilmSignal.grainAt], weighted and added to luma alone by another
 * `lut2`, which leaves chroma and alpha as they came.
 *
 * Going through `gbrap` and back rounds twice, and `gblur` is a recursive approximation of a Gaussian that holds its
 * edge pixel past the frame, where the signal's blur clamps to it as well. Both are ffmpeg's own limits, and a test
 * pins how close they land. A frame that went through `gbrap` comes out in `yuva420p` with its alpha as it read, opaque
 * if it had none.
 */
internal fun filmFragment(
  film: Film,
  frame: Size,
): FilterFragment {
  val params = FilmParams.of(film)
  return FilterFragment(
    readsCompositionClock = true,
    branches =
      buildList {
        add(FilterBranch(listOf(FilterBranch.INPUT), RgbRoundTrip.opening(), listOf("frame")))
        val graded = if (params.halationGain > 0.0) addHalation(params, frame, "frame") else "frame"
        if (params.grainStrength > 0.0) addGrain(params, frame, graded)
        check(size > 1) { "A film with both strengths at 0 is inert and never lowers." }
      },
  )
}

private fun MutableList<FilterBranch>.addHalation(
  params: FilmParams,
  frame: Size,
  reads: String,
): String {
  val plan = FilmSignal.halationBlurPlan(frame.width)
  val light =
    listOf(
      FilterNode("premultiply", "inplace" to "1"),
      FilterNode("scale", "w" to "${plan.width}", "h" to "${plan.heightOf(frame.height)}", "flags" to "area"),
      FilmExpressions.response(),
      // gblur's sigma is in the pixels of the plane it runs on, and every plane here is one size, the plan's.
      FilterNode("gblur", "sigma" to "${plan.sigmaPx}", "steps" to "$GBLUR_STEPS", "planes" to "$RGB_PLANES"),
      FilterNode("scale", "w" to "${frame.width}", "h" to "${frame.height}", "flags" to "bilinear"),
    )
  val add = "clip(0.5+x+${params.halationGain}*y,0,$FULL_SCALE)"
  val toCodes = listOf(FilterNode("lut2", "c0" to add, "c1" to add, "c2" to add)) + RgbRoundTrip.toCodes()
  add(FilterBranch(listOf(reads), RgbRoundTrip.toRgb(), listOf("rgb")))
  add(FilterBranch(listOf("rgb"), emptyList(), listOf("near", "far")))
  add(FilterBranch(listOf("far"), light, listOf("glow")))
  add(FilterBranch(listOf("near", "glow"), toCodes, listOf("glowed")))
  return "glowed"
}

private fun MutableList<FilterBranch>.addGrain(
  params: FilmParams,
  frame: Size,
  reads: String,
): String {
  add(FilterBranch(listOf(reads), emptyList(), listOf("base", "grainIn")))
  add(FilterBranch(listOf("grainIn"), grainMap(frame), listOf("grain")))
  add(FilterBranch(listOf("base", "grain"), listOf(FilmExpressions.landGrain(params)), listOf("grained")))
  return "grained"
}

// A frame whose cells are a whole number of pixels draws its map on the cells and widens it by neighbour, at the same
// figure. A map reads no input, so its raster is a crop, which copies nothing. Any other frame draws each pixel, since
// a neighbour scale reads a pixel at its centre where the signal's floor reads its corner.
private fun grainMap(frame: Size): List<FilterNode> {
  val span = FilmSignal.grainCellSpan(frame.width, frame.height)
  val edge = span / GRAIN_CELL_SCALE
  val whole = span % GRAIN_CELL_SCALE == 0 && edge > 1 && frame.width % edge == 0 && frame.height % edge == 0
  if (!whole) return listOf(FilmExpressions.grainMap(span))
  return listOf(
    FilterNode("crop", "w" to "${frame.width / edge}", "h" to "${frame.height / edge}", "x" to "0", "y" to "0"),
    FilmExpressions.grainMap(GRAIN_CELL_SCALE),
    FilterNode("scale", "w" to "${frame.width}", "h" to "${frame.height}", "flags" to "neighbor"),
  )
}

/**
 * Writes [FilmSignal]'s functions as ffmpeg expressions.
 */
internal object FilmExpressions {
  private val hash = HashExpression(GRAIN_SEED)
  private val clockTick = "floor(T*$GRAIN_TICK_RATE+$GRAIN_TICK_SLACK)"

  /**
   * The `geq` that draws [FilmSignal.grainAt] as an offset from mid scale in luma, with the frame's span in [span].
   *
   * Registers 2 and 3 hold each draw's state for the frame and 4 the row's first cell, worked out on the row's first
   * pixel, and the rest of the row only runs the end of each hash.
   */
  fun grainMap(
    span: Int,
    tick: String = clockTick,
  ): FilterNode =
    FilterNode(
      "geq",
      "lum" to grainPlane(span, tick),
      "cb" to "$MID_SCALE",
      "cr" to "$MID_SCALE",
    )

  /**
   * The expression [grainMap] draws luma with.
   */
  fun grainPlane(
    span: Int,
    tick: String,
  ): String {
    val row =
      "if(X,0,st(2,${hash.state(PASS_GRAIN_FIRST, tick)});st(3,${hash.state(PASS_GRAIN_SECOND, tick)});" +
        "st(4,floor(Y*$GRAIN_CELL_SCALE/$span)*$GRAIN_CELLS_ACROSS))"
    val cell = "st(5,ld(4)+floor(X*$GRAIN_CELL_SCALE/$span))"
    val first = "st(6,(${hash.hashFrom("ld(2)", "ld(5)")})/$HASH_RANGE)"
    val second = "st(6,ld(6)+(${hash.hashFrom("ld(3)", "ld(5)")})/$HASH_RANGE)"
    return "$row;$cell;$first;$second;$MID_SCALE.5+$GRAIN_REACH*(ld(6)-1)"
  }

  /**
   * The `lut2` that adds a [grainMap]'s code to a luma code, weighted by [FilmSignal.grainWeight] of that code.
   *
   * Its table is worked out once a graph, so a pixel costs one read. A code past the luma range is kept, since a decode
   * clamps red, green and blue there the way [FilmSignal.grained] does.
   */
  fun landGrain(params: FilmParams): FilterNode {
    val luma = "((x-$LUMA_BLACK)/$LUMA_CODES)"
    val weight = "max($GRAIN_WEIGHT_FLOOR,$GRAIN_WEIGHT_PEAK*$luma*(1-$luma))"
    val delta = "$LUMA_CODES*${params.grainStrength}*$weight*(y-$MID_SCALE)/$GRAIN_REACH"
    return FilterNode("lut2", "c0" to "clip(0.5+x+$delta,0,$FULL_SCALE)", "c1" to "x", "c2" to "x")
  }

  /**
   * The `geq` that draws [FilmSignal.halationResponse] in each plane, from a frame already boxed to the blur's size.
   */
  fun response(): FilterNode {
    val luma = "($KR*r(X,Y)+${1.0 - KR - KB}*g(X,Y)+$KB*b(X,Y))/$FULL_SCALE"
    val t = "clip(($luma-$HALATION_LOW)/${HALATION_HIGH - HALATION_LOW},0,1)"
    val tinted = { tint: Double -> "st(0,$t);0.5+$FULL_SCALE*$tint*ld(0)*ld(0)*(3-2*ld(0))" }
    return FilterNode(
      "geq",
      "r" to tinted(HALATION_TINT_RED),
      "g" to tinted(HALATION_TINT_GREEN),
      "b" to tinted(HALATION_TINT_BLUE),
    )
  }
}

// The most gblur takes, and the closest it lands to a Gaussian. Its recursive filter is ffmpeg's limit past this.
private const val GBLUR_STEPS = 6
private const val RGB_PLANES = 7
private const val FULL_SCALE = 255
private const val LUMA_BLACK = 16
private const val LUMA_CODES = 219
private const val MID_SCALE = 128

// The grain map's codes span this much either side of mid scale.
private const val GRAIN_REACH = 127.0

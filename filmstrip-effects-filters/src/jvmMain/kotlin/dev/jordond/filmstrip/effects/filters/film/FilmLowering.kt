package dev.jordond.filmstrip.effects.filters.film

import dev.jordond.filmstrip.effect.FilterBranch
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.LiveBranches
import dev.jordond.filmstrip.effect.LiveInput
import dev.jordond.filmstrip.effects.RgbRoundTrip
import dev.jordond.filmstrip.effects.filters.internal.HASH_RANGE
import dev.jordond.filmstrip.effects.filters.internal.HashExpression
import dev.jordond.filmstrip.effects.filters.internal.KB
import dev.jordond.filmstrip.effects.filters.internal.KR
import dev.jordond.filmstrip.effects.spatial.SpatialBlurGraph
import dev.jordond.filmstrip.geometry.Size

/**
 * The filter graph that draws a [Film] on ffmpeg, every figure read from [FilmSignal].
 *
 * The fragment opens by converting whatever it is handed to 8 bit limited range BT.709 4:2:0, keeping any alpha,
 * because its expressions are written in those codes. Halation runs in `gbrap`, since it adds to red, green and blue,
 * with the matrix named both ways. A branch takes the frame's alpha into its light, boxes it to
 * [FilmSignal.halationBlurPlan]'s size, draws the response with `geq`, and blurs it and widens it back through
 * [SpatialBlurGraph] to be added through `lut2`. Grain is a map of [FilmSignal.grainAt], weighted and added to luma
 * alone by another `lut2`, which leaves chroma and alpha as they came.
 *
 * The map comes two ways with the same samples: as [FilterFragment.branches] a `geq` draws it, and as
 * [FilterFragment.live] [GrainPlanes] does and hands it over as a live input.
 *
 * The weave and the flicker are a `geq` on the same `gbrap` frame, the weave reading each pixel at
 * [FilmSignal.weaveAt]'s offset between its four nearest pixels with their colour premultiplied by alpha, ahead of the
 * halation, and the flicker after it. Dust and scratches, then the gate, run after the grain in [filmGate].
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
  val graded = mutableListOf(FilterBranch(listOf(FilterBranch.INPUT), RgbRoundTrip.opening(), listOf("frame")))
  val gate = filmGate(params, frame)
  val lit = graded.addLight(params, frame, "frame", fullChroma = gate != null)
  val grains = params.grainStrength > 0.0
  check(graded.size > 1 || grains || gate != null) { "A film with every control at 0 is inert and never lowers." }
  val drawn = if (grains) graded + drawnGrain(params, frame, lit) else graded
  val grained = if (grains) GRAINED else lit
  val liveGrain = if (grains) liveGrain(params, frame, graded, lit) else null
  val live =
    when {
      gate?.live != null -> {
        LiveBranches(
          inputs = liveGrain?.inputs.orEmpty() + gate.live.inputs,
          branches = (liveGrain?.branches ?: graded) + gate.live.branches(grained),
        )
      }
      liveGrain != null -> {
        LiveBranches(liveGrain.inputs, liveGrain.branches + gate?.drawn(grained).orEmpty())
      }
      else -> {
        null
      }
    }
  return FilterFragment(
    readsCompositionClock = true,
    branches = drawn + gate?.drawn(grained).orEmpty(),
    sidecars = gate?.sidecars.orEmpty(),
    live = live,
  )
}

// The weave, the halation and the flicker in that order, all on gbrap, and the pad the frame's codes come back on,
// with full chroma when the gate turns them back to RGB.
private fun MutableList<FilterBranch>.addLight(
  params: FilmParams,
  frame: Size,
  reads: String,
  fullChroma: Boolean,
): String {
  val weaves = params.weave > 0.0
  val glows = params.halationGain > 0.0
  val flickers = params.flicker > 0.0
  if (!weaves && !glows && !flickers) return reads
  val woven = if (weaves || (flickers && !glows)) listOf(FilmExpressions.light(params, weaves, !glows)) else emptyList()
  if (!glows) {
    add(FilterBranch(listOf(reads), RgbRoundTrip.toRgb() + woven + RgbRoundTrip.toCodes(fullChroma), listOf(LIT)))
    return LIT
  }
  val plan = FilmSignal.halationBlurPlan(frame.width)
  // The frame reaching a Film has square pixels, so one plan blurs both ways.
  val light =
    SpatialBlurGraph.boxedDown(plan, plan, frame, RGB_FORMAT) +
      FilmExpressions.response() +
      SpatialBlurGraph.blurredUp(plan, plan, frame, RGB_FORMAT, RGB_PLANES)
  val add = "clip(0.5+x+${params.halationGain}*y,0,$FULL_SCALE)"
  val flicker = if (flickers) listOf(FilmExpressions.light(params, weaves = false, flickers = true)) else emptyList()
  val toCodes =
    listOf(FilterNode("lut2", "c0" to add, "c1" to add, "c2" to add)) + flicker + RgbRoundTrip.toCodes(fullChroma)
  add(FilterBranch(listOf(reads), RgbRoundTrip.toRgb() + woven, listOf("rgb")))
  add(FilterBranch(listOf("rgb"), emptyList(), listOf("near", "far")))
  // The glow is weighed by its alpha through a lut2 reading alpha on every plane.
  add(FilterBranch(listOf("far"), emptyList(), listOf("weighedlight", "weighedcover")))
  add(FilterBranch(listOf("weighedcover"), listOf(ALPHA_ON_EVERY_PLANE), listOf("weighedalpha")))
  add(FilterBranch(listOf("weighedlight", "weighedalpha"), listOf(PREMULTIPLIED), listOf("weighed")))
  add(FilterBranch(listOf("weighed"), light, listOf("glow")))
  add(FilterBranch(listOf("near", "glow"), toCodes, listOf(GLOWED)))
  return GLOWED
}

private fun drawnGrain(
  params: FilmParams,
  frame: Size,
  reads: String,
): List<FilterBranch> =
  listOf(
    FilterBranch(listOf(reads), emptyList(), listOf("base", "grainIn")),
    FilterBranch(listOf("grainIn"), grainMap(frame, params.seed), listOf("grain")),
    FilterBranch(listOf("base", "grain"), listOf(FilmExpressions.landGrain(params)), listOf(GRAINED)),
  )

// A live plane runs on past the frames it lands on, so only here does the lut2 stop with the shorter of its inputs.
private fun liveGrain(
  params: FilmParams,
  frame: Size,
  graded: List<FilterBranch>,
  reads: String,
): LiveBranches {
  val raster = grainRaster(frame)
  val widened = FilterBranch(listOf(GRAIN_PLANE), raster.widen(frame), listOf("grain"))
  val land = FilmExpressions.landGrain(params, stopsWithFrame = true)
  return LiveBranches(
    inputs =
      listOf(
        LiveInput(
          pad = GRAIN_PLANE,
          width = raster.width,
          height = raster.height,
          ticksPerSecond = GRAIN_TICK_RATE,
          key = "film.grain.${params.seed}",
          planeAt = GrainPlanes(raster.width, raster.height, params.seed)::at,
          tickOpening = FilmSignal::grainTickOpening,
        ),
      ),
    branches = graded + widened + FilterBranch(listOf(reads, "grain"), listOf(land), listOf(GRAINED)),
  )
}

// A frame whose cells are a whole number of pixels draws its map on the cells and widens it by neighbour, at the same
// figure. A map reads no input, so its raster is a crop, which copies nothing. Any other frame draws each pixel, since
// a neighbour scale reads a pixel at its centre where the signal's floor reads its corner.
internal fun grainMap(
  frame: Size,
  seed: Int = GRAIN_SEED,
): List<FilterNode> {
  val raster = grainRaster(frame)
  if (raster == frame) {
    return listOf(FilmExpressions.grainMap(FilmSignal.grainCellSpan(frame.width, frame.height), seed = seed))
  }
  return listOf(
    FilterNode("crop", "w" to "${raster.width}", "h" to "${raster.height}", "x" to "0", "y" to "0"),
    FilmExpressions.grainMap(GRAIN_CELL_SCALE, seed = seed),
  ) + raster.widen(frame)
}

// The size a frame's map is made at, its cells where they are a whole number of pixels and the frame otherwise.
internal fun grainRaster(frame: Size): Size {
  val span = FilmSignal.grainCellSpan(frame.width, frame.height)
  val edge = span / GRAIN_CELL_SCALE
  val whole = span % GRAIN_CELL_SCALE == 0 && edge > 1 && frame.width % edge == 0 && frame.height % edge == 0
  return if (whole) Size(frame.width / edge, frame.height / edge) else frame
}

internal fun Size.widen(frame: Size): List<FilterNode> =
  if (this == frame) {
    listOf(FilterNode("null"))
  } else {
    listOf(FilterNode("scale", "w" to "${frame.width}", "h" to "${frame.height}", "flags" to "neighbor"))
  }

/**
 * Draws a raster [width] by [height] of [FilmSignal.grainAt] under [seed] per tick, at the codes
 * [FilmExpressions.grainPlane] writes.
 */
internal class GrainPlanes(
  private val width: Int,
  private val height: Int,
  private val seed: Int = GRAIN_SEED,
) {
  private val columns = IntArray(width) { FilmSignal.grainCell(it, 0, width, height) }
  private val rows = IntArray(height) { FilmSignal.grainCell(0, it, width, height) }

  fun at(tick: Int): ByteArray {
    val plane = ByteArray(width * height)
    var index = 0
    for (row in rows) {
      for (column in columns) {
        plane[index++] = (GRAIN_MID_CODE + GRAIN_REACH * FilmSignal.grainAt(row + column, tick, seed)).toInt().toByte()
      }
    }
    return plane
  }
}

/**
 * Writes [FilmSignal]'s functions as ffmpeg expressions.
 */
internal object FilmExpressions {
  // FilmSignal.grainTickAt of the frame's time.
  val clockTick =
    "floor((floor(T*$GRAIN_CLOCK_BASE+$GRAIN_CLOCK_ROUNDING)+$GRAIN_CLOCK_ROUNDING)/$GRAIN_CLOCK_BASE*" +
      "$GRAIN_TICK_RATE+$GRAIN_TICK_MARGIN)"

  /**
   * The `geq` on `gbrap` that reads each pixel at [FilmSignal.weaveAt]'s offset when [weaves], and multiplies it by
   * [FilmSignal.flickerAt]'s gain, clamped to full scale, when [flickers], at the tick [tick] names.
   *
   * The offset and the gain are worked out on a row's first pixel into registers 3, 4 and 5, and the row's place down
   * into 7 and 9. A woven read takes the four pixels around its place, each held at the frame's edge by the `geq`'s own
   * read, and mixes their colour premultiplied by alpha, so a clear pixel lends its neighbour no colour. Its alpha is
   * the mix of theirs. Every plane rounds to the nearest code.
   */
  fun light(
    params: FilmParams,
    weaves: Boolean,
    flickers: Boolean,
    tick: String = clockTick,
  ): FilterNode {
    val hash = HashExpression(params.seed)
    val unit = { pass: Int, knot: String -> "(${hash.hash(pass, knot, "0")})/$HASH_RANGE*2-1" }
    val reach = { pass: Int, reach: Double, size: String, register: Int ->
      "st(6,${unit(pass, "ld(9)")});st(7,${unit(pass, "ld(9)+1")});" +
        "st($register,${params.weave}*$reach*(ld(6)+(ld(7)-ld(6))*ld(8))*$size)"
    }
    val weave =
      "st(9,floor(ld(2)/$WEAVE_KNOT_TICKS));st(8,(ld(2)-ld(9)*$WEAVE_KNOT_TICKS)/$WEAVE_KNOT_TICKS);" +
        "st(8,ld(8)*ld(8)*(3-2*ld(8)));${reach(PASS_WEAVE_X, WEAVE_REACH_X, "W", 3)};" +
        reach(PASS_WEAVE_Y, WEAVE_REACH_Y, "H", 4)
    val gain = "st(5,1+$FLICKER_DEPTH*${params.flicker}*(${unit(PASS_FLICKER, "ld(2)")}))"
    val down = "st(7,Y+ld(4));st(9,floor(ld(7)));st(7,ld(7)-ld(9))"
    val row = "if(X,0,st(2,$tick);${listOfNotNull(
      weave.takeIf { weaves },
      gain.takeIf { flickers },
      down.takeIf { weaves },
    ).joinToString(";")})"
    val at = "st(6,X+ld(3));st(8,floor(ld(6)));st(6,ld(6)-ld(8))"
    val corners =
      listOf(Triple("(1-ld(6))*(1-ld(7))", 0, 0), Triple("ld(6)*(1-ld(7))", 1, 0))
        .plus(listOf(Triple("(1-ld(6))*ld(7)", 0, 1), Triple("ld(6)*ld(7)", 1, 1)))
    val tap = { plane: String, dx: Int, dy: Int -> "$plane(ld(8)+$dx,ld(9)+$dy)" }
    val coverage = corners.joinToString("+") { (weight, dx, dy) -> "$weight*${tap("alpha", dx, dy)}" }
    val read = { plane: String ->
      if (weaves) {
        val mixed =
          corners.joinToString(
            "+",
          ) { (weight, dx, dy) -> "$weight*${tap(plane, dx, dy)}*${tap("alpha", dx, dy)}" }
        "$at;st(2,$coverage);if(gt(ld(2),0),($mixed)/ld(2),0)"
      } else {
        "$plane(X,Y)"
      }
    }
    val lit = { plane: String ->
      val value = if (flickers) "min($FULL_SCALE,(${read(plane)})*ld(5))" else read(plane)
      "$row;clip(0.5+($value),0,$FULL_SCALE)"
    }
    val alpha = if (weaves) "$row;$at;clip(0.5+$coverage,0,$FULL_SCALE)" else "alpha(X,Y)"
    return FilterNode("geq", "r" to lit("r"), "g" to lit("g"), "b" to lit("b"), "a" to alpha, "i" to "nearest")
  }

  /**
   * The `geq` that draws [FilmSignal.grainAt] under [seed] as an offset from mid scale in luma, with the frame's span
   * in [span].
   *
   * Registers 2 and 3 hold each draw's state for the frame and 4 the row's first cell, worked out on the row's first
   * pixel, and the rest of the row only runs the end of each hash.
   */
  fun grainMap(
    span: Int,
    tick: String = clockTick,
    seed: Int = GRAIN_SEED,
  ): FilterNode =
    FilterNode(
      "geq",
      "lum" to grainPlane(span, tick, seed),
      "cb" to "$MID_SCALE",
      "cr" to "$MID_SCALE",
    )

  /**
   * The expression [grainMap] draws luma with.
   */
  fun grainPlane(
    span: Int,
    tick: String,
    seed: Int = GRAIN_SEED,
  ): String {
    val hash = HashExpression(seed)
    val row =
      "if(X,0,st(2,${hash.state(PASS_GRAIN_FIRST, tick)});st(3,${hash.state(PASS_GRAIN_SECOND, tick)});" +
        "st(4,floor(Y*$GRAIN_CELL_SCALE/$span)*$GRAIN_CELLS_ACROSS))"
    val cell = "st(5,ld(4)+floor(X*$GRAIN_CELL_SCALE/$span))"
    val first = "st(6,(${hash.hashFrom("ld(2)", "ld(5)")})/$HASH_RANGE)"
    val second = "st(6,ld(6)+(${hash.hashFrom("ld(3)", "ld(5)")})/$HASH_RANGE)"
    return "$row;$cell;$first;$second;$GRAIN_MID_CODE+$GRAIN_REACH*(ld(6)-1)"
  }

  /**
   * The `lut2` that adds a [grainMap]'s code to a luma code, weighted by [FilmSignal.grainWeight] of that code.
   *
   * Its table is worked out once a graph, so a pixel costs one read. A code past the luma range is kept, since a decode
   * clamps red, green and blue there the way [FilmSignal.grained] does.
   *
   * @param stopsWithFrame Whether the frame ending ends this `lut2`, for a map that runs on past it.
   */
  fun landGrain(
    params: FilmParams,
    stopsWithFrame: Boolean = false,
  ): FilterNode {
    val luma = "((x-$LUMA_BLACK)/$LUMA_CODES)"
    val weight = "max($GRAIN_WEIGHT_FLOOR,$GRAIN_WEIGHT_PEAK*$luma*(1-$luma))"
    val delta = "$LUMA_CODES*${params.grainStrength}*$weight*(y-$MID_SCALE)/$GRAIN_REACH"
    val planes = listOf("c0" to "clip(0.5+x+$delta,0,$FULL_SCALE)", "c1" to "x", "c2" to "x")
    return FilterNode("lut2", *(if (stopsWithFrame) listOf("shortest" to "1") + planes else planes).toTypedArray())
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

private const val RGB_FORMAT = "gbrap"

// Each colour code weighed by its alpha code over 256 and rounded, where ffmpeg's premultiply lands on eight bits.
private const val WEIGHED_CODE = "floor((x*y+128)/256)"
private val PREMULTIPLIED =
  FilterNode(
    "lut2",
    "c0" to WEIGHED_CODE,
    "c1" to WEIGHED_CODE,
    "c2" to WEIGHED_CODE,
    "c3" to "x",
  )
private val ALPHA_ON_EVERY_PLANE =
  FilterNode(
    "mergeplanes",
    *(0 until RGBA_PLANES).flatMap { listOf("map${it}s" to "0", "map${it}p" to "${RGBA_PLANES - 1}") }.toTypedArray(),
    "format" to RGB_FORMAT,
  )
private const val RGBA_PLANES = 4
private const val RGB_PLANES = 7
private const val FULL_SCALE = 255
private const val LUMA_BLACK = 16
private const val LUMA_CODES = 219

// Where a grain of nothing lands, half a code above mid scale so a truncation rounds.
private const val GRAIN_MID_CODE = 128.5
private const val GRAIN_PLANE = "grainPlane"
private const val LIT = "lit"
private const val GLOWED = "glowed"
private const val GRAINED = "grained"

// Mid scale, the code a chroma plane holds for no colour and the grain map holds for no grain.
internal const val MID_SCALE = 128

// The grain map's codes span this much either side of mid scale.
internal const val GRAIN_REACH = 127.0

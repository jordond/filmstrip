package dev.jordond.filmstrip.effects.filters.film

import dev.jordond.filmstrip.effect.FilterBranch
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.LiveBranches
import dev.jordond.filmstrip.effect.LiveInput
import dev.jordond.filmstrip.effect.exactCrop
import dev.jordond.filmstrip.effects.RgbRoundTrip
import dev.jordond.filmstrip.effects.filters.internal.GlowGraph
import dev.jordond.filmstrip.effects.filters.internal.HASH_RANGE
import dev.jordond.filmstrip.effects.filters.internal.HashExpression
import dev.jordond.filmstrip.effects.filters.internal.KB
import dev.jordond.filmstrip.effects.filters.internal.KR
import dev.jordond.filmstrip.effects.filters.internal.RGB_FORMAT
import dev.jordond.filmstrip.effects.filters.internal.WIDE_FORMAT
import dev.jordond.filmstrip.effects.filters.internal.WIDE_FULL_SCALE
import dev.jordond.filmstrip.geometry.Size

/**
 * The filter graph that draws a [Film] on ffmpeg, every figure read from [FilmSignal].
 *
 * The fragment opens by converting whatever it is handed to 8 bit limited range BT.709 4:2:0, keeping any alpha,
 * because its expressions are written in those codes. Halation runs in `gbrap`, since it adds to red, green and blue,
 * with the matrix named both ways. [GlowGraph.light] takes the frame's alpha into its light, boxes it to
 * [FilmSignal.halationBlurPlan]'s size, draws the response with `geq`, and blurs it and widens it back to be added
 * through `mix`, which keeps 16 bit codes and the frame's alpha exactly where a `lut2` would round them. Grain is a map
 * of [FilmSignal.grainAt], weighted and added to luma alone by a `lut2`, which leaves chroma and alpha as they came.
 *
 * The leak is screened on after the glow and before the flicker by [FilmLeakGraph], from a map of [FilmLeak.leakAt]
 * drawn at [leakRaster]. A film with a leak runs those steps on sixteen bit planes whether or not it glows.
 *
 * The fade and the breath of [FilmTone] follow the leak. A breath rides the flicker's `geq`, and takes the fade with
 * it. A fade with neither is [FilmToneExpressions.fade], a `lutrgb` of the same terms, which reads no clock.
 *
 * The grain's map comes two ways with the same samples: as [FilterFragment.branches] a `geq` draws it, and as
 * [FilterFragment.live] [GrainPlanes] does and hands it over as a live input.
 *
 * The weave and the flicker are a `geq` on the same `gbrap` frame, the weave reading each pixel at
 * [FilmSignal.weaveAt]'s offset between its four nearest pixels with their colour premultiplied by alpha, ahead of the
 * halation, and the flicker after it. Dust and scratches, then the gate, run after the grain in [filmGate].
 *
 * The glow is blurred by the plan's exact kernel and added, and the flicker applied, on sixteen bit planes,
 * so it rounds once to a code before the frame goes back to `yuva420p`. Going through `gbrap` and back rounds twice,
 * which is ffmpeg's own limit, and a test pins how close it lands. A frame that went through `gbrap` comes out in
 * `yuva420p` with its alpha as it read, opaque if it had none.
 */
internal fun filmFragment(
  film: Film,
  frame: Size,
): FilterFragment {
  val params = FilmParams.of(film)
  val opening = FilterBranch(listOf(FilterBranch.INPUT), RgbRoundTrip.opening(), listOf("frame"))
  val gate = filmGate(params, frame)
  val leaks = params.leak > 0.0
  val graded = mutableListOf(opening)
  val lit =
    graded.addLight(params, frame, "frame", fullChroma = gate != null) {
      FilmLeakGraph.drawnMap(params, frame, it)
    }
  val liveLeak = if (leaks) liveLight(params, frame, opening, fullChroma = gate != null) else null
  val liveGraded = liveLeak?.branches ?: graded
  val leakInputs = liveLeak?.inputs.orEmpty()
  val grains = params.grainStrength > 0.0
  check(graded.size > 1 || grains || gate != null) { "A film with every control at 0 is inert and never lowers." }
  val drawn = if (grains) graded + drawnGrain(params, frame, lit) else graded
  val grained = if (grains) GRAINED else lit
  val liveGrain = if (grains) liveGrain(params, frame, liveGraded, lit) else null
  val live =
    when {
      gate?.live != null -> {
        LiveBranches(
          inputs = leakInputs + liveGrain?.inputs.orEmpty() + gate.live.inputs,
          branches = (liveGrain?.branches ?: liveGraded) + gate.live.branches(grained),
        )
      }
      liveGrain != null -> {
        LiveBranches(leakInputs + liveGrain.inputs, liveGrain.branches + gate?.drawn(grained).orEmpty())
      }
      liveLeak != null -> {
        LiveBranches(leakInputs, liveGraded + gate?.drawn(grained).orEmpty())
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

// The light branches with the leak's map read off a live input, and that input.
private fun liveLight(
  params: FilmParams,
  frame: Size,
  opening: FilterBranch,
  fullChroma: Boolean,
): LiveBranches {
  val inputs = mutableListOf<LiveInput>()
  val branches = mutableListOf(opening)
  branches.addLight(params, frame, "frame", fullChroma) { clock ->
    val (input, map) = FilmLeakGraph.liveMap(params, frame, clock)
    inputs += input
    map
  }
  return LiveBranches(inputs, branches)
}

// The weave, the halation, the leak, the tone and the flicker in that order, all on gbrap, and the pad the frame's
// codes come back on, with full chroma when the gate turns them back to RGB. The glow and the leak run on sixteen bit
// planes, and the tone and the flicker follow them there. leakMap gives the branches that put the leak's map on
// FilmLeakGraph.MAP, timed by the gbrap frame on the pad it is handed.
private fun MutableList<FilterBranch>.addLight(
  params: FilmParams,
  frame: Size,
  reads: String,
  fullChroma: Boolean,
  leakMap: (clock: String) -> List<FilterBranch>,
): String {
  val weaves = params.weave > 0.0
  val glows = params.halationGain > 0.0
  val leaks = params.leak > 0.0
  val clocked = params.flicker > 0.0 || params.breath > 0.0
  val fades = params.fade > 0.0
  if (!weaves && !glows && !leaks && !clocked && !fades) return reads
  val wide = glows || leaks
  val woven =
    when {
      weaves || (clocked && !wide) -> listOf(FilmExpressions.light(params, weaves, !wide))
      fades && !wide -> listOf(FilmToneExpressions.fade(params, wide = false))
      else -> emptyList()
    }
  if (!wide) {
    add(FilterBranch(listOf(reads), RgbRoundTrip.toRgb() + woven + RgbRoundTrip.toCodes(fullChroma), listOf(LIT)))
    return LIT
  }
  val toned =
    when {
      clocked -> listOf(FilmExpressions.light(params, weaves = false, tones = true, wide = true))
      fades -> listOf(FilmToneExpressions.fade(params, wide = true))
      else -> emptyList()
    }
  val toCodes =
    toned +
      FilterNode("format", "pix_fmts" to WIDE_FORMAT) +
      RgbRoundTrip.toCodeStarts() +
      FilterNode("format", "pix_fmts" to RGB_FORMAT) +
      RgbRoundTrip.toCodes(fullChroma)
  val readers = listOfNotNull("narrow", "far".takeIf { glows }, "leakTimer".takeIf { leaks })
  add(FilterBranch(listOf(reads), RgbRoundTrip.toRgb() + woven, listOf(if (readers.size > 1) "rgb" else "narrow")))
  if (readers.size > 1) add(FilterBranch(listOf("rgb"), emptyList(), readers))
  val widened = listOf(FilterNode("format", "pix_fmts" to WIDE_FORMAT))
  val glowed = if (leaks) "glowedWide" else GLOWED
  if (glows) {
    val added =
      listOf(
        FilterNode(
          "mix",
          "weights" to "1 ${params.halationGain}",
          "scale" to "1",
          "planes" to "7",
          "duration" to "first",
        ),
        FilterNode("lutrgb", "r" to CLIPPED_WIDE, "g" to CLIPPED_WIDE, "b" to CLIPPED_WIDE),
        FilterNode("format", "pix_fmts" to WIDE_FORMAT),
      )
    add(FilterBranch(listOf("narrow"), widened, listOf("near")))
    addAll(GlowGraph.light(FilmSignal.halationBlurPlan(frame.width), frame, FilmExpressions.response(), "far", "glow"))
    add(FilterBranch(listOf("near", "glow"), if (leaks) added else added + toCodes, listOf(glowed)))
  } else {
    add(FilterBranch(listOf("narrow"), widened, listOf(glowed)))
  }
  if (leaks) {
    addAll(leakMap("leakTimer"))
    addAll(FilmLeakGraph.screened(frame, glowed, toCodes, GLOWED))
  }
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
    exactCrop(raster.width, raster.height),
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
   * The `geq` on `gbrap` that reads each pixel at [FilmSignal.weaveAt]'s offset when [weaves], and when [tones] takes
   * it through [FilmTone.toned] and [FilmSignal.flickerAt]'s gain, clamped to the code range, at the tick [tick] names.
   *
   * The offset and the gain are worked out on a row's first pixel into registers 3, 4 and 5, and the row's place down
   * into 7 and 9. A breath then folds its contrast, the plane's own gain and the flicker into one scale in 5 and one
   * offset in 4, which the row's place has no more use for. The hash works through 0 and 1, so nothing is held there.
   * A control at 0 leaves its part out.
   *
   * A woven read takes the four pixels around its place, each held at the frame's edge by the `geq`'s own read, and
   * mixes their colour premultiplied by alpha, so a clear pixel lends its neighbour no colour. Its alpha is the mix
   * of theirs. Every plane rounds to the nearest code.
   */
  fun light(
    params: FilmParams,
    weaves: Boolean,
    tones: Boolean,
    tick: String = clockTick,
    wide: Boolean = false,
  ): FilterNode {
    val full = if (wide) WIDE_FULL_SCALE else FULL_SCALE
    val rounding = if (wide) "" else "0.5+"
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
    val flickers = tones && params.flicker > 0.0
    val breathes = tones && params.breath > 0.0
    val fades = tones && params.fade > 0.0
    val gain = "st(5,1+$FLICKER_DEPTH*${params.flicker}*(${unit(PASS_FLICKER, "ld(2)")}))"
    val knot = "floor(ld(2)/$BREATH_KNOT_TICKS)"
    val knotted = { pass: Int ->
      "st(6,${unit(pass, knot)});ld(6)+((${unit(pass, "$knot+1")})-ld(6))*ld(8)"
    }
    val breath = { channel: Int ->
      val (pass, depth) =
        when (channel) {
          GREEN -> PASS_BREATH_TINT to BREATH_TINT
          BLUE -> PASS_BREATH_WARMTH to -BREATH_WARMTH
          else -> PASS_BREATH_WARMTH to BREATH_WARMTH
        }
      val held = if (flickers) "ld(5)" else "1"
      "st(8,(ld(2)-$knot*$BREATH_KNOT_TICKS)/$BREATH_KNOT_TICKS);st(8,ld(8)*ld(8)*(3-2*ld(8)));" +
        "st(4,1+$BREATH_CONTRAST*${params.breath}*(${knotted(PASS_BREATH_CONTRAST)}));" +
        "st(5,(1+$depth*${params.breath}*(${knotted(pass)}))*$held);" +
        "st(8,ld(4));st(4,${full * BREATH_PIVOT}*(1-ld(8))*ld(5));st(5,ld(8)*ld(5))"
    }
    val down = "st(7,Y+ld(4));st(9,floor(ld(7)));st(7,ld(7)-ld(9))"
    val row = { channel: Int ->
      "if(X,0,st(2,$tick);${listOfNotNull(
        weave.takeIf { weaves },
        gain.takeIf { flickers },
        down.takeIf { weaves },
        breath(channel).takeIf { breathes },
      ).joinToString(";")})"
    }
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
    val lit = { plane: String, channel: Int ->
      val faded =
        if (fades) {
          "((${read(
            plane,
          )})*${FilmToneExpressions.fadeScale(params, channel)}+${FilmToneExpressions.fadeLift(params, channel, full)})"
        } else {
          "(${read(plane)})"
        }
      val value =
        when {
          breathes -> "$faded*ld(5)+ld(4)"
          flickers -> "min($full,$faded*ld(5))"
          else -> faded
        }
      "${row(channel)};clip($rounding($value),0,$full)"
    }
    val alpha = if (weaves) "${row(RED)};$at;clip(0.5+$coverage,0,$FULL_SCALE)" else "alpha(X,Y)"
    return FilterNode(
      "geq",
      "r" to lit("r", RED),
      "g" to lit("g", GREEN),
      "b" to lit("b", BLUE),
      "a" to alpha,
      "i" to "nearest",
    )
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

internal const val FULL_SCALE = 255
internal const val RED = 0
internal const val GREEN = 1
internal const val BLUE = 2

private const val CLIPPED_WIDE = "min(val,$WIDE_FULL_SCALE)"
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

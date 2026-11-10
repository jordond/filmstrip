package dev.jordond.filmstrip.effects.filters.film

import dev.jordond.filmstrip.effect.FilterBranch
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.LiveInput
import dev.jordond.filmstrip.effect.Sidecar
import dev.jordond.filmstrip.effects.GainMaps
import dev.jordond.filmstrip.effects.RgbRoundTrip
import dev.jordond.filmstrip.effects.filters.internal.HASH_RANGE
import dev.jordond.filmstrip.effects.filters.internal.HashExpression
import dev.jordond.filmstrip.geometry.Size

/**
 * What a [Film] draws after its grain, dust and scratches then the gate, or null when both are off.
 *
 * The grained frame goes to `gbrap`, whose conversion clamps each of red, green and blue the way
 * [FilmSignal.grained] does. A map of [FilmSignal.dustAt] and [FilmSignal.scratchAt] lands on it through `lut2`, and
 * [GainMaps] draws [FilmSignal.gateMask] once into a sixteen bit map that `blend` multiplies every colour plane's
 * code by, at 256 a code, leaving alpha as it came. The marks map comes two ways with the same samples, a `geq` in
 * [drawn] and [MarkPlanes] in [live]. Either way it is tagged full range before it reaches `gbrap`, so its codes land
 * as written rather than stretched from limited range.
 */
internal fun filmGate(
  params: FilmParams,
  frame: Size,
): FilmGate? {
  if (params.dust <= 0.0 && params.gate <= 0.0) return null
  val map =
    if (params.gate > 0.0) {
      GainMaps.of(GateShape(params.gate, frame), frame) { x, y ->
        FilmSignal.gateMask(x, y, frame.width.toDouble() / frame.height, params.gate)
      }
    } else {
      null
    }
  return FilmGate(params, frame, map)
}

/**
 * The branches [filmGate] lowers to, from the pad of the grained frame's codes to [GATED].
 */
internal class FilmGate(
  private val params: FilmParams,
  private val frame: Size,
  private val map: Sidecar?,
) {
  val sidecars: List<Sidecar> = listOfNotNull(map)

  /**
   * The marks drawn on a live plane, or null when the dust is off.
   */
  val live: LiveGate? = if (params.dust > 0.0) LiveGate() else null

  /**
   * The branches that draw the marks with a `geq` and then the gate, reading [reads].
   */
  fun drawn(reads: String): List<FilterBranch> {
    if (params.dust <= 0.0) return closing(reads, null)
    val split = FilterBranch(listOf(reads), emptyList(), listOf(BASE, MARKS_IN))
    val marks =
      FilterBranch(
        listOf(MARKS_IN),
        listOf(FilterNode("format", "pix_fmts" to GRAY), FilmGateExpressions.marksMap(params, frame)),
        listOf(MARKS),
      )
    return listOf(split, marks) + closing(BASE, MARKS, stopsWithFrame = false)
  }

  /**
   * The marks a [MarkPlanes] draws on the composition's clock, handed over as a live input.
   */
  inner class LiveGate {
    val inputs: List<LiveInput> =
      listOf(
        LiveInput(
          pad = MARK_PLANE,
          width = frame.width,
          height = frame.height,
          ticksPerSecond = GRAIN_TICK_RATE,
          key = "film.marks.${params.seed}.${params.dust}",
          planeAt = MarkPlanes(frame.width, frame.height, params.seed, params.dust)::at,
          tickOpening = FilmSignal::grainTickOpening,
        ),
      )

    /**
     * The branches that land the live marks and then the gate, reading [reads].
     */
    fun branches(reads: String): List<FilterBranch> {
      val luma = listOf(FilterNode("format", "pix_fmts" to PLANE_FORMATS), FilterNode("extractplanes", "planes" to "y"))
      return listOf(FilterBranch(listOf(MARK_PLANE), luma, listOf(MARKS))) +
        closing(reads, MARKS, stopsWithFrame = true)
    }
  }

  private fun closing(
    reads: String,
    marks: String?,
    stopsWithFrame: Boolean = false,
  ): List<FilterBranch> {
    val marked =
      if (marks == null) {
        listOf(FilterBranch(listOf(reads), RgbRoundTrip.toRgb(), listOf(MARKED)))
      } else {
        listOf(
          FilterBranch(listOf(reads), RgbRoundTrip.toRgb(), listOf(RGB)),
          FilterBranch(listOf(marks), listOf(FULL_RANGE, onEveryPlane(RGB_FORMAT)), listOf(MARK_RGB)),
          FilterBranch(listOf(RGB, MARK_RGB), listOf(FilmGateExpressions.landMarks(stopsWithFrame)), listOf(MARKED)),
        )
      }
    if (map == null) return marked + FilterBranch(listOf(MARKED), RgbRoundTrip.toCodes(), listOf(GATED))
    val gain =
      FilterBranch(
        emptyList(),
        GainMaps.read(map) + FilterNode("format", "pix_fmts" to WIDE_GRAY) + onEveryPlane(WIDE_RGBA),
        listOf(GAIN),
      )
    val blend =
      listOf(
        FilterNode("blend", "c0_mode" to "multiply", "c1_mode" to "multiply", "c2_mode" to "multiply"),
        RgbRoundTrip.toCodeStarts(),
        FilterNode("format", "pix_fmts" to RGB_FORMAT),
      ) + RgbRoundTrip.toCodes()
    return marked +
      listOf(
        FilterBranch(
          listOf(MARKED),
          listOf(FilterNode("format", "pix_fmts" to WIDE_RGBA), RgbRoundTrip.toCodeStarts()),
          listOf(WIDE),
        ),
        gain,
        FilterBranch(listOf(WIDE, GAIN), blend, listOf(GATED)),
      )
  }
}

/**
 * Draws a frame [width] by [height] of the marks [FilmGateExpressions.marksMap] writes, per tick under [seed] at a
 * [dust] from 0 to 1.
 *
 * Dust is the same over a grain cell, so it is drawn once a cell on [grainRaster] and widened by neighbour. Only the
 * scratch is worked out for each column of the frame.
 */
internal class MarkPlanes(
  private val width: Int,
  private val height: Int,
  private val seed: Int,
  private val dust: Double,
) {
  private val raster = grainRaster(Size(width, height))
  private val edge = width / raster.width
  private val columns = IntArray(raster.width) { FilmSignal.grainCell(it, 0, raster.width, raster.height) }
  private val rows = IntArray(raster.height) { FilmSignal.grainCell(0, it, raster.width, raster.height) }

  fun at(tick: Int): ByteArray {
    val specks = DoubleArray(raster.width * raster.height)
    var cell = 0
    for (row in rows) {
      for (column in columns) specks[cell++] = FilmSignal.dustAt(seed, tick, row + column, dust)
    }
    val scratched = BooleanArray(width) { FilmSignal.scratchAt(seed, tick, (it + HALF) / width, dust) != 0.0 }
    val plane = ByteArray(width * height)
    var index = 0
    for (y in 0 until height) {
      val above = y / edge * raster.width
      for (x in 0 until width) plane[index++] = markCode(specks[above + x / edge], scratched[x]).toByte()
    }
    return plane
  }
}

/**
 * The code a marks map holds for a speck of [dust] and whether a scratch runs through the pixel: mid scale, one either
 * side for a light or dark speck, and three more under a scratch.
 */
internal fun markCode(
  dust: Double,
  scratched: Boolean,
): Int = MID_SCALE + dust.compareTo(0.0).coerceIn(-1, 1) + if (scratched) MARK_SCRATCH_STEP else 0

/**
 * Writes the marks of [FilmSignal.dustAt] and [FilmSignal.scratchAt] as ffmpeg expressions.
 */
internal object FilmGateExpressions {
  /**
   * The `geq` that draws the codes [markCode] gives each pixel of a [frame] at the tick [tick] names.
   *
   * A row's first pixel works out whether the run's scratch lives at the tick into register 3, its place into 4, its
   * drift so far into 5, and the row's cell into 6. Each pixel then hashes its dust block.
   */
  fun marksMap(
    params: FilmParams,
    frame: Size,
    tick: String = FilmExpressions.clockTick,
  ): FilterNode = FilterNode("geq", "lum" to marksPlane(params, frame, tick))

  /**
   * The expression [marksMap] draws luma with.
   */
  fun marksPlane(
    params: FilmParams,
    frame: Size,
    tick: String,
  ): String {
    val hash = HashExpression(params.seed)
    val unit = { pass: Int, at: String, cell: String -> "(${hash.hash(pass, at, cell)})/$HASH_RANGE" }
    val span = FilmSignal.grainCellSpan(frame.width, frame.height)
    val lives = SCRATCH_LIFE_MAX - SCRATCH_LIFE_MIN + 1
    val row =
      listOf(
        "st(2,$tick)",
        "st(9,floor(ld(2)/$SCRATCH_RUN_TICKS))",
        "st(3,lt(${unit(PASS_SCRATCH, "ld(9)", "0")},${params.dust}))",
        "st(8,$SCRATCH_LIFE_MIN+floor(${unit(PASS_SCRATCH_LIFE, "ld(9)", "0")}*$lives))",
        "st(7,floor(${unit(PASS_SCRATCH_START, "ld(9)", "0")}*($SCRATCH_RUN_TICKS-ld(8)+1)))",
        "st(7,ld(2)-ld(9)*$SCRATCH_RUN_TICKS-ld(7))",
        "st(3,ld(3)*gte(ld(7),0)*lt(ld(7),ld(8)))",
        "st(4,$SCRATCH_EDGE+${1.0 - 2.0 * SCRATCH_EDGE}*${unit(PASS_SCRATCH_PLACE, "ld(9)", "0")})",
        "st(5,$SCRATCH_DRIFT*(${unit(PASS_SCRATCH_DRIFT, "ld(9)", "0")}*2-1)*ld(7))",
        "st(6,floor(Y*$GRAIN_CELL_SCALE/$span))",
      ).joinToString(";")
    val chance = DUST_PER_FRAME * params.dust / DUST_CELLS
    val half = DUST_CELL / 2.0
    val speck =
      listOf(
        "st(9,$DUST_RADIUS_MIN+${DUST_RADIUS_MAX - DUST_RADIUS_MIN}*${unit(PASS_DUST_SIZE, "ld(2)", "ld(8)")})",
        "st(9,lte(pow(mod(ld(7),$DUST_CELL)+0.5-$half,2)+pow(mod(ld(6),$DUST_CELL)+0.5-$half,2),ld(9)*ld(9)))",
        "ld(9)*if(lt(${unit(PASS_DUST_TONE, "ld(2)", "ld(8)")},0.5),1,-1)",
      ).joinToString(";")
    val dust =
      "st(7,floor(X*$GRAIN_CELL_SCALE/$span));" +
        "st(8,floor(ld(6)/$DUST_CELL)*$DUST_CELLS_ACROSS+floor(ld(7)/$DUST_CELL));" +
        "st(9,if(lt(${unit(PASS_DUST, "ld(2)", "ld(8)")},$chance),$speck,0))"
    val scratch = "ld(3)*lte(abs((X+0.5)/W-ld(4)-ld(5)),${SCRATCH_WIDTH / 2.0})"
    return "if(X,0,$row);$dust;$MID_SCALE+ld(9)+$MARK_SCRATCH_STEP*$scratch"
  }

  /**
   * The `lut2` that adds the marks a map's code holds to red, green and blue, clamped to full scale, leaving alpha.
   *
   * @param stopsWithFrame Whether the frame ending ends this `lut2`, for a map that runs on past it.
   */
  fun landMarks(stopsWithFrame: Boolean): FilterNode {
    val scratched = "gte(y-$MID_SCALE,${MARK_SCRATCH_STEP - 1})"
    val mark = "($DUST_LEVEL*(y-$MID_SCALE-$MARK_SCRATCH_STEP*$scratched)+$SCRATCH_LEVEL*$scratched)"
    val add = "clip(0.5+x+$FULL_CODE*$mark,0,$FULL_CODE)"
    val planes = listOf("c0" to add, "c1" to add, "c2" to add, "c3" to "x")
    return FilterNode("lut2", *(if (stopsWithFrame) listOf("shortest" to "1") + planes else planes).toTypedArray())
  }
}

// Every plane of the format takes the gray plane, the first plane of the branch's input.
private fun onEveryPlane(target: String): FilterNode =
  FilterNode(
    "mergeplanes",
    *(0 until RGBA_PLANES).flatMap { listOf("map${it}s" to "0", "map${it}p" to "0") }.toTypedArray(),
    "format" to target,
  )

// The gate's control and the frame it shows on, which say whether two gates draw the same map.
private data class GateShape(
  val gate: Double,
  val frame: Size,
)

// The marks' codes are data, so tagging them full range keeps a conversion to gbrap from stretching them.
private val FULL_RANGE = FilterNode("setparams", "range" to "pc")

private const val HALF = 0.5
private const val MARK_SCRATCH_STEP = 3
private const val FULL_CODE = 255
private const val RGBA_PLANES = 4
private const val GRAY = "gray"
private const val PLANE_FORMATS = "yuv420p|yuva420p"
private const val RGB_FORMAT = "gbrap"
private const val WIDE_RGBA = "gbrap16le"
private const val WIDE_GRAY = "gray16le"
private const val MARK_PLANE = "markPlane"
private const val BASE = "unmarked"
private const val MARKS_IN = "marksIn"
private const val MARKS = "marks"
private const val MARK_RGB = "marksRgb"
private const val RGB = "markedIn"
private const val MARKED = "marked"
private const val WIDE = "wide"
private const val GAIN = "gateGain"

/**
 * The pad a [FilmGate]'s branches end on.
 */
internal const val GATED = "gated"

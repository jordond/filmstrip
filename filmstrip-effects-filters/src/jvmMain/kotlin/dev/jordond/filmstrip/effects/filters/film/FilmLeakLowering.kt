package dev.jordond.filmstrip.effects.filters.film

import dev.jordond.filmstrip.effect.FilterBranch
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.LiveInput
import dev.jordond.filmstrip.effect.exactCrop
import dev.jordond.filmstrip.effects.filters.internal.GlowGraph
import dev.jordond.filmstrip.effects.filters.internal.HASH_RANGE
import dev.jordond.filmstrip.effects.filters.internal.HashExpression
import dev.jordond.filmstrip.effects.filters.internal.WIDE_FORMAT
import dev.jordond.filmstrip.geometry.Size
import kotlin.math.PI
import kotlin.math.min

/**
 * How many pixels of [frame] one sample of its leak map covers a side.
 *
 * It is 8 from a frame 360 pixels on its shorter side and up, and falls with a smaller frame down to 1, so the shorter
 * side always holds at least 45 samples or every pixel of it. The leak's width goes with the frame, so a small frame
 * read at 8 would land further from the light than a large one.
 */
internal fun leakStep(frame: Size): Int =
  (min(frame.width, frame.height) / LEAK_RASTER_SAMPLES).coerceIn(1, LEAK_RASTER_STEP)

/**
 * The size a frame's leak map is made at, the frame over [leakStep] a side and rounded up, with one more sample past
 * each edge.
 *
 * The light is [FilmLeak.leakAt]'s at each sample's centre. Only the raster is smaller, since the leak is far wider
 * than a sample and a `geq` over the whole frame costs more than the rest of the film. The samples past the edges
 * give the frame's outer pixels a sample either side to be read between.
 */
internal fun leakRaster(frame: Size): Size {
  val step = leakStep(frame)
  return Size(
    (frame.width + step - 1) / step + 2 * LEAK_RASTER_MARGIN,
    (frame.height + step - 1) / step + 2 * LEAK_RASTER_MARGIN,
  )
}

/**
 * Where the centre of sample [index] of a [leakRaster] at [step] sits along a frame side of [pixels], as a fraction of
 * it.
 */
internal fun leakSampleAt(
  index: Int,
  pixels: Int,
  step: Int,
): Double = (index - LEAK_RASTER_MARGIN + 0.5) * step / pixels

/**
 * Draws [FilmLeak.leakAt] over [frame]'s [leakRaster] per tick, as the codes [FilmLeakGraph.leakPlane] writes.
 *
 * A plane is three rasters tall, the red light over the green over the blue, so it travels as luma alone.
 */
internal class LeakPlanes(
  frame: Size,
  private val seed: Int,
  private val leak: Double,
) {
  private val raster = leakRaster(frame)
  private val step = leakStep(frame)
  private val across = DoubleArray(raster.width) { leakSampleAt(it, frame.width, step) }
  private val down = DoubleArray(raster.height) { leakSampleAt(it, frame.height, step) }
  private val aspect = frame.width.toDouble() / frame.height

  fun at(tick: Int): ByteArray {
    val band = raster.width * raster.height
    val plane = ByteArray(band * LEAK_BANDS)
    for (y in 0 until raster.height) {
      for (x in 0 until raster.width) {
        val light = FilmLeak.leakAt(across[x], down[y], aspect, tick, seed, leak)
        val at = y * raster.width + x
        for (channel in 0 until LEAK_BANDS) {
          plane[channel * band + at] = (0.5 + LEAK_FULL_SCALE * light[channel]).toInt().toByte()
        }
      }
    }
    return plane
  }
}

/**
 * The branches that screen a [Film]'s light leaks over a sixteen bit `gbrap16le` frame on ffmpeg.
 *
 * The map is eight bit `gbrp` at [leakRaster]. It comes two ways with the same samples: [drawnMap] draws it with a
 * `geq`, and [liveMap] reads [LeakPlanes] off a live input. [screened] widens it to the frame between its samples,
 * drops the samples past its edges, and screens it over the frame through [GlowGraph.screened], which is
 * [FilmLeak.leaked]. Alpha comes through from the frame.
 */
internal object FilmLeakGraph {
  /**
   * The branches that draw the map onto [MAP] with a `geq`, timed by the `gbrap` frame on [clock].
   */
  fun drawnMap(
    params: FilmParams,
    frame: Size,
    clock: String,
  ): List<FilterBranch> {
    val raster = leakRaster(frame)
    val plane = { channel: Int -> leakPlane(params, frame, channel) }
    val draw =
      listOf(
        sizedTo(raster, frame),
        FilterNode("format", "pix_fmts" to MAP_FORMAT),
        FilterNode("geq", "r" to plane(RED), "g" to plane(GREEN), "b" to plane(BLUE)),
      )
    return listOf(FilterBranch(listOf(clock), draw, listOf(MAP)))
  }

  /**
   * The input [LeakPlanes] feeds and the branches that join its bands onto [MAP], on the frames of the `gbrap` frame
   * on [clock].
   *
   * A live plane keeps its own ticks and runs on past the frame, and `maskedmerge` draws a frame for every input's
   * and never stops with the shortest. A `lut2` that hands on only the map lands it on the frame's own frames first.
   */
  fun liveMap(
    params: FilmParams,
    frame: Size,
    clock: String,
  ): Pair<LiveInput, List<FilterBranch>> {
    val raster = leakRaster(frame)
    val input =
      LiveInput(
        pad = PLANE,
        width = raster.width,
        height = raster.height * LEAK_BANDS,
        ticksPerSecond = GRAIN_TICK_RATE,
        key = "film.leak.${params.seed}.${params.leak}.${frame.width}x${frame.height}",
        planeAt = LeakPlanes(frame, params.seed, params.leak)::at,
        tickOpening = FilmSignal::grainTickOpening,
      )
    val bands = listOf("leakRed", "leakGreen", "leakBlue")
    val band = { index: Int ->
      FilterBranch(
        listOf("${bands[index]}In"),
        listOf(exactCrop(raster.width, raster.height, 0, index * raster.height)),
        listOf(bands[index]),
      )
    }
    val joined =
      FilterNode(
        "mergeplanes",
        *(0 until LEAK_BANDS).flatMap { listOf("map${it}s" to "$it", "map${it}p" to "0") }.toTypedArray(),
        "format" to MAP_FORMAT,
      )
    val timed = listOf(sizedTo(raster, frame), FilterNode("format", "pix_fmts" to MAP_FORMAT))
    val held = FilterNode("lut2", "shortest" to "1", "c0" to "y", "c1" to "y", "c2" to "y")
    return input to
      listOf(
        FilterBranch(listOf(PLANE), listOf(FilterNode("extractplanes", "planes" to "y")), listOf("leakBands")),
        FilterBranch(listOf("leakBands"), emptyList(), bands.map { "${it}In" }),
        band(RED),
        band(GREEN),
        band(BLUE),
        // gbrp holds green, then blue, then red.
        FilterBranch(listOf(bands[GREEN], bands[BLUE], bands[RED]), listOf(joined), listOf("leakJoined")),
        FilterBranch(listOf(clock), timed, listOf("leakClock")),
        FilterBranch(listOf("leakClock", "leakJoined"), listOf(held), listOf(MAP)),
      )
  }

  /**
   * The branches that screen the map on [MAP] over the `gbrap16le` frame on [reads] and write it to [writes], with
   * [after] run on the result.
   */
  fun screened(
    frame: Size,
    reads: String,
    after: List<FilterNode>,
    writes: String,
  ): List<FilterBranch> {
    val raster = leakRaster(frame)
    val step = leakStep(frame)
    val mask =
      listOf(
        FilterNode("format", "pix_fmts" to WIDE_FORMAT),
        FilterNode("scale", "w" to "${raster.width * step}", "h" to "${raster.height * step}", "flags" to "bilinear"),
        exactCrop(frame.width, frame.height, LEAK_RASTER_MARGIN * step, LEAK_RASTER_MARGIN * step),
        GlowGraph.maskOf(),
      )
    return GlowGraph.screened(reads, MAP, mask, after, writes)
  }

  /**
   * The expression that draws [FilmLeak.leakAt]'s light in one [channel] of red, green and blue as a code, at the
   * centre of each sample of [frame]'s raster.
   *
   * Each spot's centre, radius and strength in this channel are worked out on a row's first sample into registers 2
   * to 9, four a spot, and the rest of the row reads only how far it sits from each centre.
   */
  fun leakPlane(
    params: FilmParams,
    frame: Size,
    channel: Int,
    tick: String = FilmExpressions.clockTick,
  ): String {
    val hash = HashExpression(params.seed)
    val step = leakStep(frame)
    val aspect = frame.width.toDouble() / frame.height
    val spot = { index: Int ->
      val x = FIRST_REGISTER + index * SPOT_REGISTERS
      val y = x + 1
      val radius = x + 2
      val gain = x + 3
      val clock = "($tick+${index * LEAK_STAGGER_TICKS})"
      val draw = { pass: Int -> "(${hash.hash(pass, "ld($gain)", "$index")})/$HASH_RANGE" }
      val tint =
        LEAK_TINTS.indices.joinToString("+") { "eq(ld(0),$it)*${LEAK_TINTS[it][channel]}" }
      val wave = "sin($PI*($clock-ld($gain)*$LEAK_RUN_TICKS)/$LEAK_RUN_TICKS)"
      listOf(
        // The gain's register holds the run until the gain itself is worked out.
        "st($gain,floor($clock/$LEAK_RUN_TICKS))",
        "st($x,floor(${draw(PASS_LEAK_EDGE)}*$LEAK_EDGES))",
        "st($y,${draw(PASS_LEAK_PLACE)})",
        "st($radius,$LEAK_RADIUS_MIN+${LEAK_RADIUS_MAX - LEAK_RADIUS_MIN}*${draw(PASS_LEAK_RADIUS)})",
        "st(0,ld($x))",
        "st($x,if(eq(ld(0),$LEAK_EDGE_LEFT),0,if(eq(ld(0),$LEAK_EDGE_RIGHT),1,ld($y))))",
        "st($y,if(lte(ld(0),$LEAK_EDGE_RIGHT),ld($y),0))",
        "st(0,floor(${draw(PASS_LEAK_TINT)}*${LEAK_TINTS.size}))",
        "st($gain,$LEAK_LEVEL*${params.leak}*pow($wave,2)*($tint))",
      ).joinToString(";")
    }
    val lit = { index: Int ->
      val x = FIRST_REGISTER + index * SPOT_REGISTERS
      val across = "(${sampleAt("X", frame.width, step)}-ld($x))*$aspect"
      val down = "(${sampleAt("Y", frame.height, step)}-ld(${x + 1}))"
      "st(0,clip(hypot($across,$down)/ld(${x + 2}),0,1));st(0,1-ld(0)*ld(0)*(3-2*ld(0)));ld(${x + 3})*ld(0)*ld(0)"
    }
    val row = "if(X,0,${(0 until LEAK_SPOTS).joinToString(";") { spot(it) }})"
    val light = (0 until LEAK_SPOTS).joinToString(";") { "st(1,${if (it == 0) "" else "ld(1)+"}(${lit(it)}))" }
    return "$row;$light;floor(0.5+$LEAK_FULL_SCALE*min(1,ld(1)))"
  }

  // Takes the frame to the raster's size for its timing alone, with a crop where the raster fits inside it. A small
  // frame's raster has a sample for every pixel and one past each edge, so it is wider than the frame, which is
  // stretched to it.
  private fun sizedTo(
    raster: Size,
    frame: Size,
  ): FilterNode =
    if (raster.width <= frame.width && raster.height <= frame.height) {
      exactCrop(raster.width, raster.height)
    } else {
      FilterNode("scale", "w" to "${raster.width}", "h" to "${raster.height}", "flags" to "neighbor")
    }

  // leakSampleAt as an expression.
  private fun sampleAt(
    index: String,
    pixels: Int,
    step: Int,
  ): String = "($index-$LEAK_RASTER_MARGIN+0.5)*$step/$pixels"

  // The pad the map lands on.
  const val MAP = "leakMap"
  private const val PLANE = "leakPlane"
  private const val MAP_FORMAT = "gbrp"
  private const val RED = 0
  private const val GREEN = 1
  private const val BLUE = 2
  private const val FIRST_REGISTER = 2
  private const val SPOT_REGISTERS = 4
}

// The most pixels a sample covers a side, and the fewest samples the frame's shorter side holds before a sample
// covers fewer.
private const val LEAK_RASTER_STEP = 8
private const val LEAK_RASTER_SAMPLES = 45
private const val LEAK_RASTER_MARGIN = 1
private const val LEAK_BANDS = 3
private const val LEAK_FULL_SCALE = 255

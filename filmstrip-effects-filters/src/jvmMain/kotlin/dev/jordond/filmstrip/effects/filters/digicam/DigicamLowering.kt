package dev.jordond.filmstrip.effects.filters.digicam

import dev.jordond.filmstrip.effect.FilterBranch
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.LiveBranches
import dev.jordond.filmstrip.effect.LiveInput
import dev.jordond.filmstrip.effect.exactCrop
import dev.jordond.filmstrip.effects.RgbRoundTrip
import dev.jordond.filmstrip.effects.filters.film.GRAIN_CLOCK_BASE
import dev.jordond.filmstrip.effects.filters.film.GRAIN_CLOCK_ROUNDING
import dev.jordond.filmstrip.effects.filters.film.GRAIN_TICK_MARGIN
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
 * The filter graph that draws a [Digicam] on ffmpeg, every figure read from [DigicamSignal].
 *
 * The fragment opens by converting whatever it is handed to 8 bit limited range BT.709 4:2:0, keeping any alpha, and
 * on to `gbrap`, since the look works on red, green and blue. [GlowGraph.light] blurs the frame by
 * [DigicamSignal.blurPlan]'s kernel, a `mix` moves the frame away from that blur on sixteen bit planes, which is
 * [DigicamSignal.sharpened], and a `lutrgb` multiplies it by the clip gain and cuts it off at white, which is
 * [DigicamSignal.clipped]. The frame rounds once to a code after that.
 *
 * The noise is a map of [DigicamSignal.chromaAt] holding what each cell moves green, blue and red by at a weight of
 * one. A `lut2` weighs it by the luma of the frame under it and a second adds it to the frame, clamped the way
 * [DigicamSignal.noised] clamps. The map comes two ways with the same samples: as [FilterFragment.branches] a `geq`
 * draws it, and as [FilterFragment.live] [NoisePlanes] does and hands it over as a live input.
 *
 * The map and the luma it is weighed by hold eight bits, which is the most a live plane and a `lut2` take, and a test
 * pins how close the noise lands. A look with no noise reads no clock and has no live form.
 */
internal fun digicamFragment(
  digicam: Digicam,
  frame: Size,
): FilterFragment {
  val params = DigicamParams.of(digicam)
  val noises = params.noiseAmplitude > 0.0
  val toEightBits = listOf(RgbRoundTrip.toCodeStarts(), FilterNode("format", "pix_fmts" to RGB_FORMAT))
  val pushed = pushedBranches(params, frame, if (noises) emptyList() else toEightBits + RgbRoundTrip.toCodes())
  if (!noises) return FilterFragment(branches = pushed)
  val luma = listOf(DigicamExpressions.luma()) + toEightBits
  val weighed =
    pushed +
      FilterBranch(listOf(PUSHED), emptyList(), listOf("pushedOut", "lumaIn")) +
      FilterBranch(listOf("pushedOut"), toEightBits, listOf("base")) +
      FilterBranch(listOf("lumaIn"), luma, listOf(LUMA))
  val raster = noiseRaster(frame)
  val drawn =
    listOf(
      FilterBranch(listOf(LUMA), emptyList(), listOf("lumaOut", "mapIn")),
      FilterBranch(
        listOf("mapIn"),
        listOf(exactCrop(raster.width, raster.height), DigicamExpressions.noiseMap(digicam.seed)) + raster.widen(frame),
        listOf(MAP),
      ),
    )
  val bands = listOf("noiseGreen", "noiseBlue", "noiseRed")
  val joined =
    FilterNode(
      "mergeplanes",
      *bands.indices.flatMap { listOf("map${it}s" to "$it", "map${it}p" to "0") }.toTypedArray(),
      "format" to "gbrp",
    )
  val live =
    listOf(
      FilterBranch(listOf(NOISE_PLANE), listOf(FilterNode("extractplanes", "planes" to "y")), listOf("noiseBands")),
      FilterBranch(listOf("noiseBands"), emptyList(), bands.map { "${it}In" }),
    ) +
      bands.mapIndexed { index, band ->
        FilterBranch(
          listOf("${band}In"),
          listOf(exactCrop(raster.width, raster.height, 0, index * raster.height)),
          listOf(band),
        )
      } +
      FilterBranch(
        bands,
        listOf(joined) + raster.widen(frame),
        listOf(MAP),
      )
  val land = { reads: String, stopsWithFrame: Boolean ->
    listOf(
      FilterBranch(listOf(reads, MAP), listOf(DigicamExpressions.weighMoves(params, stopsWithFrame)), listOf("moves")),
      FilterBranch(
        listOf("base", "moves"),
        listOf(DigicamExpressions.landMoves()) + RgbRoundTrip.toCodes(),
        listOf(NOISED),
      ),
    )
  }
  return FilterFragment(
    readsCompositionClock = true,
    branches = weighed + drawn + land("lumaOut", false),
    live =
      LiveBranches(
        inputs =
          listOf(
            LiveInput(
              pad = NOISE_PLANE,
              width = raster.width,
              height = raster.height * bands.size,
              ticksPerSecond = NOISE_RATE,
              key = "digicam.noise.${digicam.seed}",
              planeAt = NoisePlanes(raster.width, raster.height, digicam.seed)::at,
              tickOpening = DigicamSignal::tickOpening,
            ),
          ),
        branches = weighed + live + land(LUMA, true),
      ),
  )
}

// The frame sharpened against its blur and clipped, on sixteen bit planes at 256 a code, with after run on the result.
private fun pushedBranches(
  params: DigicamParams,
  frame: Size,
  after: List<FilterNode>,
): List<FilterBranch> {
  val clip = "min(0.5+val*${params.clipGain},$WIDE_FULL_SCALE)"
  val clipped =
    listOf(
      FilterNode("lutrgb", "r" to clip, "g" to clip, "b" to clip),
      FilterNode("format", "pix_fmts" to WIDE_FORMAT),
    ) + after
  val widened = listOf(FilterNode("format", "pix_fmts" to WIDE_FORMAT))
  val toRgb = RgbRoundTrip.opening() + RgbRoundTrip.toRgb()
  if (params.haloGain <= 0.0) {
    return listOf(FilterBranch(listOf(FilterBranch.INPUT), toRgb + widened + clipped, listOf(PUSHED)))
  }
  // The frame plus the gain times what it stands clear of its blur by. A mix holds a sum under zero at zero.
  val sharpen =
    FilterNode(
      "mix",
      "weights" to "${1.0 + params.haloGain} ${-params.haloGain}",
      "scale" to "1",
      "planes" to "7",
      "duration" to "first",
    )
  return listOf(
    FilterBranch(listOf(FilterBranch.INPUT), toRgb, listOf("rgb")),
    FilterBranch(listOf("rgb"), emptyList(), listOf("narrow", "far")),
    FilterBranch(listOf("narrow"), widened, listOf("near")),
  ) +
    GlowGraph.light(DigicamSignal.blurPlan(frame.width), frame, FilterNode("null"), "far", "blur") +
    FilterBranch(listOf("near", "blur"), listOf(sharpen) + clipped, listOf(PUSHED))
}

// The size the noise map is made at, one pixel a cell, with a last cell the frame's edge cuts counted whole.
internal fun noiseRaster(frame: Size): Size {
  val side = DigicamSignal.cellPx(frame.height)
  return Size((frame.width + side - 1) / side, (frame.height + side - 1) / side)
}

// A cell is a whole number of pixels wide, so a neighbour scale lays each on its pixels and a crop cuts the last. The
// map leaves as gbrap, the format the luma it is weighed by holds.
private fun Size.widen(frame: Size): List<FilterNode> {
  val side = DigicamSignal.cellPx(frame.height)
  val format = FilterNode("format", "pix_fmts" to RGB_FORMAT)
  if (side == 1) return listOf(format)
  return listOf(
    FilterNode("scale", "w" to "${width * side}", "h" to "${height * side}", "flags" to "neighbor"),
    exactCrop(frame.width, frame.height),
    format,
  )
}

/**
 * Draws the noise map of a raster [width] by [height] cells under [seed] per tick, as three bands one under another
 * in the order `gbrp` holds its planes, at the codes [DigicamExpressions.noiseBand] writes.
 */
internal class NoisePlanes(
  private val width: Int,
  private val height: Int,
  private val seed: Int,
) {
  fun at(tick: Int): ByteArray {
    val band = width * height
    val plane = ByteArray(band * BANDS)
    var index = 0
    for (row in 0 until height) {
      for (column in 0 until width) {
        val chroma = DigicamSignal.chromaAt(seed, tick, row * NOISE_CELLS_ACROSS + column)
        plane[index] = codeOf(greenOf(chroma[0], chroma[1]))
        plane[band + index] = codeOf(chroma[0])
        plane[band * 2 + index] = codeOf(chroma[1])
        index++
      }
    }
    return plane
  }

  private fun codeOf(move: Double): Byte = (NOISE_MID_CODE + NOISE_REACH * move).toInt().toByte()

  private companion object {
    const val BANDS = 3
  }
}

/**
 * Writes [DigicamSignal]'s functions as ffmpeg expressions.
 */
internal object DigicamExpressions {
  // DigicamSignal.tickAt of the frame's time.
  val clockTick =
    "floor((floor(T*$GRAIN_CLOCK_BASE+$GRAIN_CLOCK_ROUNDING)+$GRAIN_CLOCK_ROUNDING)/$GRAIN_CLOCK_BASE*" +
      "$NOISE_RATE+$GRAIN_TICK_MARGIN)"

  /**
   * The `colorchannelmixer` that puts a `gbrap16le` frame's BT.709 luma on each of its colour planes.
   */
  fun luma(): FilterNode {
    val green = 1.0 - KR - KB
    return FilterNode(
      "colorchannelmixer",
      *listOf(
        "r",
        "g",
        "b",
      ).flatMap { listOf("${it}r" to "$KR", "${it}g" to "$green", "${it}b" to "$KB") }.toTypedArray(),
    )
  }

  /**
   * The `geq` that draws the noise map under [seed] on a frame of one pixel a cell, at the tick [tick] names.
   */
  fun noiseMap(
    seed: Int,
    tick: String = clockTick,
  ): FilterNode =
    FilterNode(
      "geq",
      "r" to noiseBand(RED, seed, tick),
      "g" to noiseBand(GREEN, seed, tick),
      "b" to noiseBand(BLUE, seed, tick),
      "a" to "$FULL_SCALE",
    )

  /**
   * The expression [noiseMap] draws a plane with, what [DigicamSignal.noised] moves that plane by at a weight of one
   * over the most it can, as an offset from mid scale.
   *
   * Registers 2 and 3 hold each draw's state for the frame, worked out on the row's first pixel, and the rest of the
   * row only runs the end of each hash.
   *
   * @param band The plane, counted in the order `gbrp` holds them.
   */
  fun noiseBand(
    band: Int,
    seed: Int,
    tick: String,
  ): String {
    val hash = HashExpression(seed)
    val row = "if(X,0,st(2,${hash.state(PASS_NOISE_CB, tick)});st(3,${hash.state(PASS_NOISE_CR, tick)}))"
    val cell = "st(4,Y*$NOISE_CELLS_ACROSS+X)"
    val blue = "st(5,(${hash.hashFrom("ld(2)", "ld(4)")})/$HASH_RANGE*2-1)"
    val red = "st(6,(${hash.hashFrom("ld(3)", "ld(4)")})/$HASH_RANGE*2-1)"
    val move =
      when (band) {
        GREEN -> "$blue;$red;-($KR*$CR_TO_RED*ld(6)+$KB*$CB_TO_BLUE*ld(5))/${(1.0 - KR - KB) * GREEN_REACH}"
        BLUE -> "$blue;ld(5)"
        else -> "$red;ld(6)"
      }
    return "$row;$cell;st(7,$move);$NOISE_MID_CODE+$NOISE_REACH*ld(7)"
  }

  /**
   * The `lut2` that turns a luma code and a [noiseMap] code into what [DigicamSignal.noised] moves a plane by in
   * codes, as an offset from the middle of ten bits, which holds the largest move either way.
   *
   * @param stopsWithFrame Whether the frame ending ends this `lut2`, for a map that runs on past it.
   */
  fun weighMoves(
    params: DigicamParams,
    stopsWithFrame: Boolean,
  ): FilterNode {
    val left = "(1-x/$FULL_SCALE)"
    val move = { reach: Double ->
      "floor($MOVE_MID.5+$FULL_SCALE*${params.noiseAmplitude * reach}*$left*$left*(y-$NOISE_MID)/$NOISE_REACH)"
    }
    val planes =
      listOf(
        "d" to "$MOVE_BITS",
        "c0" to move(GREEN_REACH),
        "c1" to move(CB_TO_BLUE),
        "c2" to move(CR_TO_RED),
        "c3" to "$MOVE_MID",
      )
    return FilterNode("lut2", *(if (stopsWithFrame) listOf("shortest" to "1") + planes else planes).toTypedArray())
  }

  /**
   * The `lut2` that adds a [weighMoves] offset to a `gbrap` frame, each plane held within its codes, and keeps the
   * frame's alpha.
   */
  fun landMoves(): FilterNode {
    val moved = "clip(x+y-$MOVE_MID,0,$FULL_SCALE)"
    return FilterNode("lut2", "d" to "8", "c0" to moved, "c1" to moved, "c2" to moved, "c3" to "x")
  }

  // The planes in the order gbrp holds them.
  const val GREEN = 0
  const val BLUE = 1
  const val RED = 2
}

// What the green of a pixel moves by, over the most it can, when its chroma moves by cb and cr.
internal fun greenOf(
  cb: Double,
  cr: Double,
): Double = -(KR * CR_TO_RED * cr + KB * CB_TO_BLUE * cb) / ((1.0 - KR - KB) * GREEN_REACH)

// The most a chroma offset of one on both axes moves green by.
internal const val GREEN_REACH = (KR * CR_TO_RED + KB * CB_TO_BLUE) / (1.0 - KR - KB)

// The noise map's codes span this much either side of mid scale, and a move of nothing lands half a code above it so a
// truncation rounds.
internal const val NOISE_REACH = 127.0
internal const val NOISE_MID = 128
internal const val NOISE_MID_CODE = 128.5

private const val FULL_SCALE = 255

// A weighed move reaches past 127 codes either way, so it is held in ten bits around their middle.
private const val MOVE_BITS = 10
private const val MOVE_MID = 512

private const val PUSHED = "pushed"
private const val LUMA = "luma"
private const val MAP = "noiseMap"
private const val NOISE_PLANE = "noisePlane"
private const val NOISED = "noised"

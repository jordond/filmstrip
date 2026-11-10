package dev.jordond.filmstrip.effects.filters.nightvision

import dev.jordond.filmstrip.effect.FilterBranch
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.LiveBranches
import dev.jordond.filmstrip.effect.LiveInput
import dev.jordond.filmstrip.effect.Sidecar
import dev.jordond.filmstrip.effects.GainMaps
import dev.jordond.filmstrip.effects.LutTable
import dev.jordond.filmstrip.effects.RgbRoundTrip
import dev.jordond.filmstrip.effects.filters.film.FilmSignal
import dev.jordond.filmstrip.effects.filters.film.GRAIN_REACH
import dev.jordond.filmstrip.effects.filters.film.GRAIN_TICK_RATE
import dev.jordond.filmstrip.effects.filters.film.GrainPlanes
import dev.jordond.filmstrip.effects.filters.film.MID_SCALE
import dev.jordond.filmstrip.effects.filters.film.grainMap
import dev.jordond.filmstrip.effects.filters.film.grainRaster
import dev.jordond.filmstrip.effects.filters.film.widen
import dev.jordond.filmstrip.effects.filters.internal.KB
import dev.jordond.filmstrip.effects.filters.internal.KR
import dev.jordond.filmstrip.geometry.Size

/**
 * The filter graph that draws a [NightVision] on ffmpeg, every figure read from [NightVisionSignal].
 *
 * The frame goes through [RgbRoundTrip] to `gbrap` and on to sixteen bit `gbrap16le`, where it is kept for the mix.
 * `colorchannelmixer` writes the frame's luma into every colour plane, and two `blend`s add film's grain map drawn
 * under the look's seed, its rises and its falls split apart by `lutrgb`. `lut1d` runs the result through
 * [NightVisionSignal.phosphor], read from a [LutTable] sidecar. [GainMaps] holds [NightVisionSignal.vignetteAt] at
 * every pixel, which a `blend` multiplies in, and a last `blend` mixes the look over the kept frame at the intensity,
 * with the frame's own alpha. Every blend runs one of its own modes, so no plane is worked out by an expression.
 *
 * The grain map comes two ways with the same samples: as [FilterFragment.branches] film's `geq` draws it, and as
 * [FilterFragment.live] [GrainPlanes] does and hands it over as a live input.
 *
 * Going through RGB and back rounds twice, and the grain map holds its value to one of 255 codes. Both are ffmpeg's
 * limits, and a test pins how close they land. A frame comes out in `yuva420p` with its alpha as it read, opaque if it
 * had none.
 */
internal fun nightVisionFragment(
  nightVision: NightVision,
  frame: Size,
): FilterFragment {
  val params = NightVisionParams.of(nightVision)
  val phosphor = PHOSPHOR
  val vignette =
    GainMaps.of(NightVisionVignette, frame) { x, y ->
      NightVisionSignal.vignetteAt(x, y, frame.width.toDouble() / frame.height)
    }
  val drawn =
    listOf(
      FilterBranch(listOf(FilterBranch.INPUT), RgbRoundTrip.opening(), listOf(CODES)),
      FilterBranch(listOf(CODES), emptyList(), listOf(FRAME_CODES, GRAIN_CODES)),
      FilterBranch(listOf(GRAIN_CODES), grainMap(frame, params.seed) + grainToWide(), listOf(GRAIN)),
    ) + look(FRAME_CODES, params, phosphor, vignette, stopsWithFrame = false)
  return FilterFragment(
    readsCompositionClock = true,
    branches = drawn,
    sidecars = listOf(phosphor, vignette),
    live = liveGrain(frame, params, look(CODES, params, phosphor, vignette, stopsWithFrame = true)),
  )
}

// The graph from the frame's codes and a GRAIN pad to the frame the effect hands on. The phosphor and the kept frame
// both land at 256 a code, where the mix's blend lands each plane on the start of its code.
private fun look(
  reads: String,
  params: NightVisionParams,
  phosphor: Sidecar,
  vignette: Sidecar,
  stopsWithFrame: Boolean,
): List<FilterBranch> {
  val noise = params.noise * WIDE_FULL / GRAIN_REACH
  val delta = "(floor(val/$WIDE_PER_BYTE)-$MID_SCALE)*$noise"
  val stops = if (stopsWithFrame) listOf("shortest" to "1") else emptyList()
  val mix = 1.0 - params.intensity
  return listOf(
    FilterBranch(listOf(reads), RgbRoundTrip.toRgb() + format(WIDE_RGBA), listOf(WIDE)),
    FilterBranch(listOf(WIDE), emptyList(), listOf(KEPT, LUMA_IN)),
    FilterBranch(listOf(LUMA_IN), listOf(lumaEverywhere(), format(WIDE_RGBA)), listOf(LUMA)),
    FilterBranch(listOf(GRAIN), emptyList(), listOf(RISES_IN, FALLS_IN)),
    // lutrgb drops a fraction, so a fall is taken up to a whole sample to land where the sum would have.
    FilterBranch(listOf(RISES_IN), listOf(onColour("max($delta,0)")), listOf(RISES)),
    FilterBranch(listOf(FALLS_IN), listOf(onColour("ceil(max(-$delta,0))")), listOf(FALLS)),
    FilterBranch(listOf(LUMA, RISES), listOf(blend("addition", stops), format(WIDE_RGBA)), listOf(RISEN)),
    FilterBranch(listOf(RISEN, FALLS), listOf(blend("subtract", stops), format(WIDE_RGBA)), listOf(NOISY)),
    FilterBranch(
      listOf(NOISY),
      listOf(FilterNode("lut1d", "file" to phosphor.placeholder, "interp" to "linear"), format(WIDE_RGBA)),
      listOf(GLOW),
    ),
    FilterBranch(
      emptyList(),
      GainMaps.read(vignette) + format(WIDE_GRAY) + onEveryPlane(),
      listOf(GAIN),
    ),
    FilterBranch(listOf(GLOW, GAIN), listOf(blend("multiply"), format(WIDE_RGBA)), listOf(LIT)),
    FilterBranch(listOf(KEPT), listOf(RgbRoundTrip.toCodeStarts()), listOf(KEPT_CODES)),
    FilterBranch(
      listOf(KEPT_CODES, LIT),
      listOf(
        blend("normal", COLOUR_PLANES.map { "c${it}_opacity" to "$mix" }),
        RgbRoundTrip.toCodeStarts(),
        format(RGB_FORMAT),
      ) + RgbRoundTrip.toCodes(),
      listOf(OUT),
    ),
  )
}

// A blend that runs mode on the colour planes and hands the top frame's alpha on as it is.
private fun blend(
  mode: String,
  options: List<Pair<String, String>> = emptyList(),
): FilterNode = FilterNode("blend", *(options + COLOUR_PLANES.map { "c${it}_mode" to mode }).toTypedArray())

// A lutrgb that writes expression on every colour plane.
private fun onColour(expression: String): FilterNode =
  FilterNode("lutrgb", *listOf("r", "g", "b").map { it to expression }.toTypedArray())

// A live plane runs on past the frames it lands on, so the grain's blend stops with the shorter of its inputs.
private fun liveGrain(
  frame: Size,
  params: NightVisionParams,
  look: List<FilterBranch>,
): LiveBranches {
  val raster = grainRaster(frame)
  return LiveBranches(
    inputs =
      listOf(
        LiveInput(
          pad = GRAIN_PLANE,
          width = raster.width,
          height = raster.height,
          ticksPerSecond = GRAIN_TICK_RATE,
          key = "nightvision.grain.${params.seed}",
          planeAt = GrainPlanes(raster.width, raster.height, params.seed)::at,
          tickOpening = FilmSignal::grainTickOpening,
        ),
      ),
    branches =
      listOf(
        FilterBranch(listOf(FilterBranch.INPUT), RgbRoundTrip.opening(), listOf(CODES)),
        FilterBranch(listOf(GRAIN_PLANE), raster.widen(frame) + grainToWide(), listOf(GRAIN)),
      ) + look,
  )
}

// The grain map's luma plane, copied into every plane of a sixteen bit frame so a blend can read it beside the luma. A
// map widened from its cells leaves the scale free to pick any format, so it is held to the 4:2:0 it was drawn in.
private fun grainToWide(): List<FilterNode> =
  listOf(format(GRAIN_FORMATS), FilterNode("extractplanes", "planes" to "y"), format(WIDE_GRAY), onEveryPlane())

// Every colour plane takes the frame's luma, and alpha is kept. The frame was widened at about 256 a code, so each
// weight takes it on to the 257 a code that lut1d reads at.
private fun lumaEverywhere(): FilterNode {
  val widened = WIDE_PER_CODE.toDouble() / WIDE_PER_BYTE
  val (red, green, blue) = listOf(KR, 1.0 - KR - KB, KB).map { it * widened }
  val rows = listOf("r", "g", "b")
  return FilterNode(
    "colorchannelmixer",
    *rows
      .flatMap { row -> listOf("${row}r" to "$red", "${row}g" to "$green", "${row}b" to "$blue") }
      .toTypedArray(),
  )
}

// NightVisionSignal.phosphor at every step of the code range, as a table lut1d reads, written at 256 a code. The lift
// and the ramp are fixed, so the one table serves every intensity and seed.
private val PHOSPHOR: Sidecar by lazy {
  val params = NightVisionParams.at(intensity = 1.0)
  val steps = List(PHOSPHOR_STEPS) { NightVisionSignal.phosphor(it.toDouble() / (PHOSPHOR_STEPS - 1), params) }
  val atCodeStarts = WIDE_PER_BYTE.toDouble() / WIDE_PER_CODE
  LutTable(List(RGB) { channel -> DoubleArray(PHOSPHOR_STEPS) { steps[it][channel] * atCodeStarts } }).sidecar
}

// The look's vignette, which never changes, as the shape GainMaps keeps its map under.
private data object NightVisionVignette

// Every plane of a sixteen bit RGBA frame takes the one gray plane it reads.
private fun onEveryPlane(): FilterNode =
  FilterNode(
    "mergeplanes",
    *(0 until RGBA_PLANES).flatMap { listOf("map${it}s" to "0", "map${it}p" to "0") }.toTypedArray(),
    "format" to WIDE_RGBA,
  )

private fun format(pixelFormat: String): FilterNode = FilterNode("format", "pix_fmts" to pixelFormat)

private const val CODES = "codes"
private const val FRAME_CODES = "frameCodes"
private const val GRAIN_CODES = "grainCodes"
private const val GRAIN = "grain"
private const val GRAIN_PLANE = "grainPlane"
private const val WIDE = "wide"
private const val KEPT = "kept"
private const val LUMA_IN = "lumaIn"
private const val LUMA = "luma"
private const val NOISY = "noisy"
private const val RISES_IN = "risesIn"
private const val FALLS_IN = "fallsIn"
private const val RISES = "rises"
private const val FALLS = "falls"
private const val RISEN = "risen"
private const val GLOW = "glow"
private const val GAIN = "gain"
private const val LIT = "lit"
private const val KEPT_CODES = "keptCodes"
private const val OUT = "out"

// Steps in the phosphor table, which lut1d reads between on a straight line.
private const val PHOSPHOR_STEPS = 4096

private const val GRAIN_FORMATS = "yuv420p|yuva420p"
private const val WIDE_RGBA = "gbrap16le"
private const val WIDE_GRAY = "gray16le"
private const val RGB_FORMAT = "gbrap"
private val COLOUR_PLANES = 0 until 3
private const val RGBA_PLANES = 4
private const val RGB = 3
private const val WIDE_FULL = GainMaps.FULL
private const val WIDE_PER_BYTE = 256
private const val WIDE_PER_CODE = WIDE_FULL / 255

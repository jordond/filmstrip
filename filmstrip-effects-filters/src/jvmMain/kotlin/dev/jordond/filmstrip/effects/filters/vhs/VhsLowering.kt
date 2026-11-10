package dev.jordond.filmstrip.effects.filters.vhs

import dev.jordond.filmstrip.effect.FilterBranch
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.LiveBranches
import dev.jordond.filmstrip.effect.LiveInput
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * The filter graph that draws a [Vhs] on ffmpeg, every figure read from [VhsSignal].
 *
 * The fragment opens by converting whatever it is handed to 8 bit limited range 4:2:0 with an alpha plane, because its
 * expressions and tables are written in those codes and its reads weigh by alpha. An opaque frame gets an opaque alpha
 * plane. A clip's chain runs on the source's own format until then.
 *
 * Colour is premultiplied by alpha through `lut2`, so a clear column beside the picture adds none of its colour to a
 * read, then a full frame `geq` displaces all three planes, chroma shift included, so the answer is subpixel. A second
 * `lut2` divides the alpha back off, chroma by its own displaced alpha. `gblur` then softens each plane along the row,
 * and a halo is a second `gblur` taken back off through `lut2`. All of that runs on a tape
 * wider than the frame by a margin either side and is cropped back after, since `gblur` holds its edge pixel past what
 * it is handed where the signal keeps reading the tape, which is black past the picture. Noise, dropouts, the
 * tracking band's noise and its snow are drawn by `geq` on a narrow raster, widened by neighbour and merged with
 * `lut2`, so their cost does not follow the frame's width. Snow lands as two `lut2`s, one taking its weight off luma
 * and one adding the weighted snow back, and only when the band has any. On live planes the snow and its weight come
 * in as one frame, the weight in the columns past the raster, so a snowy clip opens three inputs.
 *
 * `gblur` is a recursive approximation of a Gaussian, which is ffmpeg's own limit, and a test pins how close it lands.
 */
internal fun vhsFragment(
  vhs: Vhs,
  frame: Size,
  opaque: NormalizedRect? = NormalizedRect.Full,
): FilterFragment {
  val params = VhsParams.of(vhs)
  val width = frame.width
  val height = frame.height
  val nodes = VhsExpressions(vhs.seed, params)
  val coverage = coverageOf(opaque, width, edgeReach(params, width))
  val normalised =
    listOf(
      FilterNode("scale", "out_range" to "tv"),
      FilterNode("format", "pix_fmts" to if (coverage is Coverage.Opaque) OPAQUE_TAPE_FORMATS else TAPE_FORMAT),
    )
  val margin = tapeMargin(params, width)
  // A sigma is taken per plane in that plane's own pixels. gblur reads sigmaV=0 as no blur down the column.
  val softened =
    listOf(
      gblur(params.lumaSigma * width, LUMA_PLANE),
      gblur(params.chromaSigma * width / 2.0, CHROMA_PLANES),
    )
  // lut2 does not clamp, and writes a result of -1 or lower as the top code, so black would wrap to white.
  val halo =
    FilterNode("lut2", "c0" to "max(0,0.5+x+${params.haloGain}*(x-y))")
  // Only the width drops. A neighbour scale reads a row at its centre, and virtualRow reads it at its top, so a map
  // scaled down the column would land up to a third of its rows on the wrong virtual row. A map reads no input, so its
  // raster is a crop, which copies nothing. A frame narrower than the raster draws its maps at its own width, since a
  // wider map scaled down reads each sample off its pixel's centre.
  val rasterWidth = min(width, RASTER_WIDTH)
  val raster = FilterNode("crop", "w" to "$rasterWidth", "h" to "$height", "x" to "0", "y" to "0")
  val upscale = FilterNode("scale", "w" to "$width", "h" to "$height", "flags" to "neighbor")
  val unpad = FilterNode("crop", "w" to "$width", "h" to "$height", "x" to "$margin", "y" to "0")
  // The snow's weight holds along a row, so its map is two samples wide, the narrowest 4:2:0 frame.
  val maskRaster = FilterNode("crop", "w" to "$MASK_WIDTH", "h" to "$height", "x" to "0", "y" to "0")
  // An odd raster puts the weight on an odd column, which crop rounds down to the chroma grid unless it is exact.
  val weightColumns =
    FilterNode("crop", "w" to "$MASK_WIDTH", "h" to "$height", "x" to "$rasterWidth", "y" to "0", "exact" to "1")
  val planes =
    VhsPlanes(vhs.seed, params, rasterWidth, height, nodes.lumaGrainScale, nodes.chromaGrainScale)
  val edges = VhsEdgeReads(nodes, params, width)
  val padding = FilterNode("pad", "w" to "${width + 2 * margin}", "h" to "ih", "x" to "$margin", "y" to "0", "color" to "black")
  val blurred = { into: String ->
    listOf(
      FilterBranch(listOf("sharp"), emptyList(), listOf("near", "far")),
      FilterBranch(listOf("far"), listOf(gblur(HALO_SIGMA * width, LUMA_PLANE)), listOf("wide")),
      FilterBranch(listOf("near", "wide"), listOf(halo, unpad), listOf(into)),
    )
  }
  val cut = { x: Int, columns: Int ->
    FilterNode("crop", "w" to "$columns", "h" to "$height", "x" to "$x", "y" to "0")
  }
  // Everything from the frame in [from] to the tape after its blur and halo, written to `ringed`. A frame with no clear
  // column runs the plain displace and the blurs. One with clear columns beside its picture takes the exact read in the
  // columns around each edge of the picture and premultiplies for the rest, and one that may be clear anywhere takes the
  // exact read across the whole frame.
  val ringed = { from: String ->
    when (coverage) {
      is Coverage.Opaque -> {
        val tape = listOf(padding, nodes.displace(width, margin)) + softened
        listOf(FilterBranch(listOf(from), tape, listOf("sharp"))) + blurred("ringed")
      }
      is Coverage.Anywhere -> {
        listOf(FilterBranch(listOf(from), listOf(edges.read(0, 0, width)), listOf("ringed")))
      }
      is Coverage.Edges -> {
        val reach = edgeReach(params, width)
        val pieces = coverage.pieces(width)
        val plain = pieces.indices.filter { pieces[it].core == null }
        val reads =
          pieces.mapNotNull { piece ->
            val index = piece.core ?: return@mapNotNull null
            val core = piece.span
            val window = edgeWindow(core, width, reach)
            val steps = listOf(cut(window.from, window.width), edges.read(window.from, core.from, core.to), cut(core.from - window.from, core.width))
            FilterBranch(listOf("edgeIn$index"), steps, listOf("piece${pieces.indexOf(piece)}"))
          }
        val cuts =
          plain.map { FilterBranch(listOf(if (plain.size == 1) "ringedBase" else "plain$it"), listOf(cut(pieces[it].span.from, pieces[it].span.width)), listOf("piece$it")) }
        val fanOut = if (plain.size > 1) listOf(FilterBranch(listOf("ringedBase"), emptyList(), plain.map { "plain$it" })) else emptyList()
        val joined = FilterBranch(pieces.indices.map { "piece$it" }, listOf(FilterNode("hstack", "inputs" to "${pieces.size}")), listOf("ringed"))
        val taps = listOf("tapeIn") + coverage.cores.indices.map { "edgeIn$it" }
        listOf(FilterBranch(listOf(from), emptyList(), taps)) +
          tapeBranches(nodes, width, margin, "tapeIn", softened) + blurred("ringedBase") + fanOut + cuts + reads + joined
      }
    }
  }
  val liveInput = { pad: String, planeAt: (Int) -> ByteArray, planeWidth: Int ->
    LiveInput(
      pad = pad,
      width = planeWidth,
      height = height,
      ticksPerSecond = TICK_RATE,
      key = "vhs.$pad:$vhs",
      planeAt = planeAt,
      tickOpening = VhsSignal::tickOpening,
      carriesChroma = true,
    )
  }
  val snows = params.trackingSnow > 0.0
  val landed = if (snows) "landed" else "out"
  val snowBranches = { stopsWithFrame: Boolean ->
    if (snows) {
      listOf(
        FilterBranch(listOf("landed", "mask"), listOf(nodes.takeSnowWeight(stopsWithFrame)), listOf("unsnowed")),
        FilterBranch(listOf("unsnowed", "snow"), listOf(nodes.addSnow(stopsWithFrame)), listOf("out")),
      )
    } else {
      emptyList()
    }
  }
  val drawnSnow =
    if (snows) {
      listOf(
        FilterBranch(listOf("maskIn"), listOf(maskRaster, nodes.snowMask(), upscale), listOf("mask")),
        FilterBranch(listOf("snowIn"), listOf(raster, nodes.snowMap(), upscale), listOf("snow")),
      )
    } else {
      emptyList()
    }
  val liveSnow =
    if (snows) {
      listOf(
        FilterBranch(listOf(SNOW_PLANE), emptyList(), listOf("snowSide", "maskSide")),
        FilterBranch(listOf("snowSide"), listOf(raster, upscale), listOf("snow")),
        FilterBranch(listOf("maskSide"), listOf(weightColumns, upscale), listOf("mask")),
      )
    } else {
      emptyList()
    }
  val snowInputs =
    if (snows) {
      listOf(
        liveInput(SNOW_PLANE, { tick -> planes.snowAt(tick, MASK_WIDTH) }, rasterWidth + MASK_WIDTH),
      )
    } else {
      emptyList()
    }
  return FilterFragment(
    readsCompositionClock = true,
    branches =
      listOf(
        FilterBranch(listOf(FilterBranch.INPUT), normalised, listOf("frame")),
        FilterBranch(
          listOf("frame"),
          emptyList(),
          listOf("tape", "grainIn", "dropIn") + if (snows) listOf("maskIn", "snowIn") else emptyList(),
        ),
      ) + ringed("tape") +
        listOf(
          FilterBranch(listOf("grainIn"), listOf(raster, nodes.grainMap(), upscale), listOf("grain")),
          FilterBranch(listOf("ringed", "grain"), listOf(nodes.grade()), listOf("graded")),
          FilterBranch(listOf("dropIn"), listOf(raster, nodes.dropMap(), upscale), listOf("drop")),
          FilterBranch(listOf("graded", "drop"), listOf(nodes.landDropout()), listOf(landed)),
        ) + drawnSnow + snowBranches(false),
    // A live plane runs on past the frames it lands on, so its lut2s stop with the shorter of their inputs.
    live =
      LiveBranches(
        inputs =
          listOf(
            liveInput(GRAIN_PLANE, planes::grainAt, rasterWidth),
            liveInput(DROP_PLANE, planes::dropAt, rasterWidth),
          ) + snowInputs,
        branches =
          listOf(FilterBranch(listOf(FilterBranch.INPUT), normalised, listOf("frame"))) + ringed("frame") +
            listOf(
              FilterBranch(listOf(GRAIN_PLANE), listOf(upscale), listOf("grain")),
              FilterBranch(listOf("ringed", "grain"), listOf(nodes.grade(stopsWithFrame = true)), listOf("graded")),
              FilterBranch(listOf(DROP_PLANE), listOf(upscale), listOf("drop")),
              FilterBranch(listOf("graded", "drop"), listOf(nodes.landDropout(stopsWithFrame = true)), listOf(landed)),
            ) + liveSnow + snowBranches(true),
        needsBt709Frames = true,
      ),
  )
}

// The tape's colour is premultiplied by its alpha, displaced, divided again and softened, writing the pad `sharp`.
// Alpha at chroma's size is the mean of the luma alpha it covers, and is displaced on its own since chroma sits a
// shift from luma.
private fun tapeBranches(
  nodes: VhsExpressions,
  width: Int,
  margin: Int,
  from: String,
  softened: List<FilterNode>,
): List<FilterBranch> {
  val pad =
    FilterNode(
      "pad",
      "w" to "${width + 2 * margin}",
      "h" to "ih",
      "x" to "$margin",
      "y" to "0",
      "color" to "black",
    )
  val alpha = FilterNode("extractplanes", "planes" to "a")
  val half = FilterNode("scale", "w" to "iw/2", "h" to "ih/2", "flags" to "area")
  val alphaFrame = FilterNode("mergeplanes", "mapping" to ALPHA_FRAME_MAPPING, "format" to "yuva420p")
  return listOf(
    FilterBranch(listOf(from), listOf(pad), listOf("padded")),
    FilterBranch(listOf("padded"), emptyList(), listOf("colour", "coverage")),
    FilterBranch(listOf("coverage"), listOf(alpha), listOf("alpha")),
    FilterBranch(listOf("alpha"), emptyList(), listOf("alphaLuma", "alphaHalf", "alphaCarry")),
    FilterBranch(listOf("alphaHalf"), listOf(half), listOf("alphaChroma")),
    FilterBranch(listOf("alphaChroma"), emptyList(), listOf("alphaCb", "alphaCr", "alphaShift")),
    FilterBranch(listOf("alphaLuma", "alphaCb", "alphaCr", "alphaCarry"), listOf(alphaFrame), listOf("weights")),
    FilterBranch(
      listOf("colour", "weights"),
      listOf(nodes.premultiply(), nodes.displace(width, margin)),
      listOf("moved"),
    ),
    FilterBranch(listOf("alphaShift"), listOf(nodes.displaceChromaAlpha(width, margin)), listOf("chromaMoved")),
    FilterBranch(listOf("moved"), emptyList(), listOf("movedColour", "movedCoverage")),
    FilterBranch(listOf("movedCoverage"), listOf(alpha), listOf("movedAlpha")),
    FilterBranch(listOf("movedAlpha"), emptyList(), listOf("movedLuma", "movedCarry")),
    FilterBranch(listOf("chromaMoved"), emptyList(), listOf("movedCb", "movedCr")),
    FilterBranch(listOf("movedLuma", "movedCb", "movedCr", "movedCarry"), listOf(alphaFrame), listOf("movedWeights")),
    FilterBranch(listOf("movedColour", "movedWeights"), listOf(nodes.divide()) + softened, listOf("sharp")),
  )
}

// Enough tape for four sigmas of the widest blur, on chroma's grid. A chroma sigma in its own plane covers twice as
// many luma pixels, which is the fraction of the width it already is.
private fun tapeMargin(
  params: VhsParams,
  width: Int,
): Int {
  val pixels = ceil(MARGIN_SIGMAS * max(params.wideSigma, params.chromaSigma) * width).toInt()
  return max(pixels + pixels % 2, 2)
}

private fun gblur(
  sigma: Double,
  planes: Int,
): FilterNode =
  FilterNode(
    "gblur",
    "sigma" to "${max(sigma, MIN_SIGMA)}",
    "sigmaV" to "0",
    "steps" to "$GBLUR_STEPS",
    "planes" to "$planes",
  )

// The most gblur takes, and the closest it lands to a Gaussian. Its recursive filter is ffmpeg's limit past this.
private const val GBLUR_STEPS = 6
private const val MARGIN_SIGMAS = 4.0
private const val TAPE_FORMAT = "yuva420p"
private const val OPAQUE_TAPE_FORMATS = "yuv420p|yuva420p"

// Four gray inputs laid onto the planes of a yuva420p frame in order, each plane from the first plane of its input.
private const val ALPHA_FRAME_MAPPING = "0x00102030"
private const val LUMA_PLANE = 1
private const val CHROMA_PLANES = 6

// gblur's own floor. Below about 0.005 pixels its recursive filter loses its scale and darkens the frame, to a fifth of
// the picture at 0.0005. A smaller blur runs here instead, which changes no pixel.
private const val MIN_SIGMA = 0.01
internal const val LUMA_BLACK = 16
internal const val LUMA_WHITE = 235
internal const val LUMA_CODES = 219
internal const val CHROMA_ZERO = 128
internal const val CHROMA_CODES = 224
internal const val MID_SCALE = 128
internal const val GRAIN_REACH = 120.0
internal const val MIN_GRAIN_CODES = 0.01
internal const val FULL_SCALE = 255
private const val RASTER_WIDTH = 640
private const val MASK_WIDTH = 2

private const val GRAIN_PLANE = "grainPlane"
private const val DROP_PLANE = "dropPlane"
private const val SNOW_PLANE = "snowPlane"

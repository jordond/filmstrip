package dev.jordond.filmstrip.effects.spatial

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.color.BlurPlan
import dev.jordond.filmstrip.color.vignetteGain
import dev.jordond.filmstrip.effect.FilterBranch
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effects.GainMaps
import dev.jordond.filmstrip.effects.RgbRoundTrip
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import kotlin.math.roundToInt

/**
 * The fragment a [GaussianBlur] lowers to on ffmpeg, for a [frame] stored at [stored] on a [transfer] grade, or SDR
 * when it is null.
 *
 * The blur runs at [BlurPlan.of]'s size on planar RGB, so every plane is blurred by the same sigma, which 4:2:0 chroma
 * would not be. The frame is boxed down by `scale` with `area`, blurred by `gblur` and widened back with `bilinear`.
 * A clip with non-square pixels is blurred as it is stored, with a plan for each axis so the sigma is the one [frame]
 * shows.
 *
 * The frame is blurred on float `gbrapf32le` with its colour premultiplied by its alpha, and divided by the blurred
 * alpha after, so a pixel the frame left empty adds no colour to the picture beside it. Each pixel then takes back the
 * alpha it came with, so an empty pixel stays empty for the fill and the picture's edge keeps its own colour. An SDR
 * frame comes back as `yuva420p`. A kept grade is blurred the same way on planes holding [SpatialSignal]'s scaled SDR
 * signal, and comes back as `yuva420p10le`.
 *
 * `gblur` is a recursive approximation of a Gaussian that holds its edge pixel past the frame, and the way through RGB
 * rounds both ways. Both are ffmpeg's limits, and a test pins how close they land.
 */
internal fun blurFragment(
  blur: GaussianBlur,
  frame: Size,
  stored: Size,
  transfer: HdrTransfer?,
): FilterFragment {
  val sigma = blur.sigma.toDouble()
  val across = BlurPlan.of(sigma, stored.width)
  // The stored rows held at the shape the frame shows, so a row's sigma is the one the frame's rows take.
  val down = BlurPlan.of(sigma, (frame.width.toDouble() * stored.height / frame.height).roundToInt())
  val signal = transfer?.let(::SpatialSignal)
  // premultiply reads a frame tagged limited range as holding video range codes, so a kept grade's planes are tagged
  // full while it and unpremultiply run. setrange only relabels, so no sample moves.
  val opening = signal?.opening?.plus(rangeOf("full")) ?: (RgbRoundTrip.opening() + RgbRoundTrip.toRgb())
  val closing = signal?.let { listOf(rangeOf("limited")) + it.closing } ?: RgbRoundTrip.toCodes()
  // The sharp frame is split off straight, so both frames mergeplanes reads come back straight.
  val divided =
    listOf(FilterNode("premultiply", "inplace" to "1")) +
      SpatialBlurGraph.boxedDown(across, down, stored, FLOAT_RGBA) +
      SpatialBlurGraph.blurredUp(across, down, stored, FLOAT_RGBA, ALL_PLANES) +
      FilterNode("unpremultiply", "inplace" to "1")
  return FilterFragment(
    branches =
      listOf(
        FilterBranch(listOf(FilterBranch.INPUT), opening + format(FLOAT_RGBA), listOf(FRAME)),
        FilterBranch(listOf(FRAME), emptyList(), listOf(SHARP, SOFT)),
        FilterBranch(listOf(SOFT), divided, listOf(BLURRED)),
        FilterBranch(listOf(BLURRED, SHARP), listOf(WITH_SHARP_ALPHA) + closing, listOf(OUT)),
      ),
    sidecars = signal?.sidecars.orEmpty(),
  )
}

/**
 * The filters every effect that blurs a frame on ffmpeg runs, so they all blur by [BlurPlan.of]'s kernel.
 *
 * An effect boxes its frame down with [boxedDown], may draw on the small frame, and blurs it and widens it back with
 * [blurredUp]. Each step is pinned to the format it reads, so the graph never picks another between them. `gblur`
 * takes its sigma in the pixels of the plane it runs on, so the frame is held on planes of one size, like planar RGB.
 */
@InternalFilmstripApi
public object SpatialBlurGraph {
  /**
   * Boxes a frame stored at [stored] down by `scale` with `area` to [across]' width and [down]'s height, in
   * [pixelFormat].
   *
   * [down] is the plan for the stored rows held at the shape the frame shows, and the same plan as [across] for a frame
   * with square pixels.
   */
  public fun boxedDown(
    across: BlurPlan,
    down: BlurPlan,
    stored: Size,
    pixelFormat: String,
  ): List<FilterNode> =
    listOf(
      FilterNode("scale", "w" to "${across.width}", "h" to "${down.heightOf(stored.height)}", "flags" to "area"),
      format(pixelFormat),
    )

  /**
   * Blurs a [boxedDown] frame by `gblur` at [across]' sigma along a row and [down]'s up a column, on the [planes]
   * bit mask, and widens it back to [stored] with `bilinear`, in [pixelFormat].
   */
  public fun blurredUp(
    across: BlurPlan,
    down: BlurPlan,
    stored: Size,
    pixelFormat: String,
    planes: Int,
  ): List<FilterNode> =
    listOf(
      FilterNode(
        "gblur",
        "sigma" to "${across.sigmaPx}",
        "sigmaV" to "${down.sigmaPx}",
        "steps" to "$GBLUR_STEPS",
        "planes" to "$planes",
      ),
      format(pixelFormat),
      FilterNode("scale", "w" to "${stored.width}", "h" to "${stored.height}", "flags" to "bilinear"),
      format(pixelFormat),
    )

  /**
   * The fragment that blurs an opaque frame of [size] on a kept [transfer] grade by [plan], then multiplies each colour
   * by [gain], and hands it back as `yuva420p10le`.
   *
   * The blur and the gain run on float planes holding [SpatialSignal]'s scaled SDR signal, so they are the blur and the
   * gain of the SDR signal, and the alpha plane comes through as it came.
   */
  public fun onKeptGrade(
    plan: BlurPlan,
    size: Size,
    transfer: HdrTransfer,
    gain: Float,
  ): FilterFragment {
    val signal = SpatialSignal(transfer)
    val chain =
      buildList {
        addAll(signal.opening)
        add(format(FLOAT_RGBA))
        addAll(boxedDown(plan, plan, size, FLOAT_RGBA))
        addAll(blurredUp(plan, plan, size, FLOAT_RGBA, COLOR_PLANES))
        if (gain != 1f) {
          val weight = gain.toString()
          add(FilterNode("colorchannelmixer", "rr" to weight, "gg" to weight, "bb" to weight))
        }
        addAll(signal.closing)
      }
    return FilterFragment(chain = chain, sidecars = signal.sidecars)
  }
}

/**
 * The fragment a [Vignette] lowers to on ffmpeg, for a [frame] stored at [stored] on a [transfer] grade, or SDR when
 * it is null.
 *
 * [GainMaps] draws [vignetteGain] once at [stored]'s size into a sixteen bit sidecar, each pixel taking the gain at
 * the place it shows in [frame], and `blend` multiplies every colour plane by it, leaving alpha as it came. An SDR
 * frame is multiplied on sixteen bit `gbrap16le` and comes back through `gbrap` as `yuva420p`. A kept grade is
 * multiplied on float planes holding [SpatialSignal]'s scaled SDR signal.
 */
internal fun vignetteFragment(
  vignette: Vignette,
  frame: Size,
  stored: Size,
  transfer: HdrTransfer?,
): FilterFragment {
  val shape = VignetteShape(vignette.strength, vignette.radius, vignette.softness, frame)
  val map =
    GainMaps.of(shape, stored) { x, y ->
      vignetteGain(
        x,
        y,
        frame.width.toDouble() / frame.height,
        vignette.strength.toDouble(),
        vignette.radius.toDouble(),
        vignette.softness.toDouble(),
      )
    }
  val read = GainMaps.read(map)
  if (transfer == null) {
    return FilterFragment(
      branches =
        listOf(
          FilterBranch(
            listOf(FilterBranch.INPUT),
            RgbRoundTrip.opening() + RgbRoundTrip.toRgb() + format(WIDE_RGBA),
            listOf(FRAME),
          ),
          FilterBranch(
            emptyList(),
            read + format(WIDE_GRAY) + onEveryPlane(WIDE_RGBA, ALPHA_PLANES),
            listOf(GAIN),
          ),
          FilterBranch(
            listOf(FRAME, GAIN),
            listOf(FilterNode("blend", "all_expr" to GAINED, "c3_expr" to RgbRoundTrip.keptOfWidened("A"))) +
              RgbRoundTrip.toEightBits() +
              RgbRoundTrip.toCodes(),
            listOf(OUT),
          ),
        ),
      sidecars = listOf(map),
    )
  }
  val signal = SpatialSignal(transfer)
  return FilterFragment(
    branches =
      listOf(
        FilterBranch(listOf(FilterBranch.INPUT), signal.opening, listOf(FRAME)),
        FilterBranch(
          emptyList(),
          read + format(FLOAT_GRAY) + onEveryPlane(FLOAT_RGBA, ALPHA_PLANES),
          listOf(GAIN),
        ),
        FilterBranch(
          listOf(FRAME, GAIN),
          listOf(FilterNode("blend", "all_expr" to "A*B", "c3_expr" to "A")) + signal.closing,
          listOf(OUT),
        ),
      ),
    sidecars = signal.sidecars + map,
  )
}

// Every plane of the format takes the gray plane, the first planes of the gain branch's format.
private fun onEveryPlane(
  target: String,
  planes: Int,
): FilterNode =
  FilterNode(
    "mergeplanes",
    *(0 until planes).flatMap { listOf("map${it}s" to "0", "map${it}p" to "0") }.toTypedArray(),
    "format" to target,
  )

// The vignette's figures and the frame it shows on, which say whether two vignettes draw the same map.
private data class VignetteShape(
  val strength: Float,
  val radius: Float,
  val softness: Float,
  val frame: Size,
)

private fun rangeOf(range: String): FilterNode = FilterNode("setrange", "range" to range)

private fun format(pixelFormat: String): FilterNode = FilterNode("format", "pix_fmts" to pixelFormat)

// The most gblur takes, and the closest it lands to a Gaussian. Its recursive filter is ffmpeg's limit past this.
private const val GBLUR_STEPS = 6
private const val ALL_PLANES = 15
private const val ALPHA_PLANES = 4

// The red, green and blue planes of gbrapf32le, with its alpha left out.
private const val COLOR_PLANES = 7

private const val FRAME = "frame"
private const val SHARP = "sharp"
private const val SOFT = "soft"
private const val BLURRED = "blurred"

// The blurred colour planes of the first read, under the alpha plane of the second. gbrap holds green, blue, red and
// alpha in that order.
private val WITH_SHARP_ALPHA =
  FilterNode(
    "mergeplanes",
    "map0s" to "0",
    "map0p" to "0",
    "map1s" to "0",
    "map1p" to "1",
    "map2s" to "0",
    "map2p" to "2",
    "map3s" to "1",
    "map3p" to "3",
    "format" to FLOAT_RGBA,
  )
private const val GAIN = "gain"
private const val OUT = "out"

private const val WIDE_RGBA = "gbrap16le"
private const val WIDE_GRAY = "gray16le"
private const val FLOAT_RGBA = "gbrapf32le"
private const val FLOAT_GRAY = "grayf32le"
private const val FULL_CODE = 255

// Each colour plane's code read back from its widened sample, times a gain map sample, landing at 65535 full scale.
private val GAINED = "${RgbRoundTrip.codeOfWidened("A")}*B/$FULL_CODE"

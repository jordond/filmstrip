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
 * would not be. The frame is boxed down by `scale` with `area`, blurred by `convolution` and widened back with
 * `bilinear`.
 * A clip with non-square pixels is blurred as it is stored, with a plan for each axis so the sigma is the one [frame]
 * shows.
 *
 * The frame is blurred on float `gbrapf32le` with its colour premultiplied by its alpha, and divided by the blurred
 * alpha after, so a pixel the frame left empty adds no colour to the picture beside it. Each pixel then takes back the
 * alpha it came with, so an empty pixel stays empty for the fill and the picture's edge keeps its own colour. An SDR
 * frame comes back as `yuva420p`. A kept grade is blurred the same way on planes holding [SpatialSignal]'s scaled SDR
 * signal, and comes back as `yuva420p10le`.
 *
 * The blur is [BlurPlan]'s kernel with the frame's edge pixel held past it. The way through RGB rounds both ways, which
 * is ffmpeg's limit, and a test pins how close it lands.
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
 * [blurredUp]. Each step is pinned to the format it reads, so the graph never picks another between them. `convolution`
 * takes its taps in the pixels of the plane it runs on, so the frame is held on planes of one size, like planar RGB.
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
   * Blurs a [boxedDown] frame by [across]' weights along a row and [down]'s up a column, on the [planes] bit mask, and
   * widens it back to [stored] with `bilinear`, in [pixelFormat].
   *
   * `convolution` runs each pass along a row with the plan's own taps on a sixteen bit copy of the frame, held out past
   * its edge by `fillborders` so every tap beyond the frame reads the edge pixel, as the plan's weights do. The pass up
   * a column runs between two `transpose`. A frame in `gbrap` is
   * widened at 256 to a code and comes back on its nearest code, and any other is carried through `gbrap16le`.
   */
  public fun blurredUp(
    across: BlurPlan,
    down: BlurPlan,
    stored: Size,
    pixelFormat: String,
    planes: Int,
  ): List<FilterNode> {
    val wide = wideOf(pixelFormat)
    return buildList {
      add(format(wide))
      add(
        FilterNode(
          "pad",
          "w" to "iw+${2 * across.taps}",
          "h" to "ih+${2 * down.taps}",
          "x" to "${across.taps}",
          "y" to "${down.taps}",
        ),
      )
      add(
        FilterNode(
          "fillborders",
          "left" to "${across.taps}",
          "right" to "${across.taps}",
          "top" to "${down.taps}",
          "bottom" to "${down.taps}",
          "mode" to "smear",
        ),
      )
      add(convolution(across, planes))
      add(TRANSPOSE)
      add(convolution(down, planes))
      add(TRANSPOSE)
      add(
        FilterNode(
          "crop",
          "w" to "iw-${2 * across.taps}",
          "h" to "ih-${2 * down.taps}",
          "x" to "${across.taps}",
          "y" to "${down.taps}",
        ),
      )
      add(FilterNode("scale", "w" to "${stored.width}", "h" to "${stored.height}", "flags" to "bilinear"))
      add(format(wide))
      if (pixelFormat == EIGHT_BIT_RGBA) add(RgbRoundTrip.toCodeStarts())
      add(format(pixelFormat))
    }
  }

  /**
   * The taps of [plan]'s kernel from the far one on one side through the centre to the far one on the other, as the
   * whole numbers `convolution` takes.
   *
   * Each weight is rounded at 16384 to the unit, which is as fine as a sixteen bit sum stays inside the filter's 32 bit
   * accumulator, and `convolution` divides by the sum of the numbers it is given.
   */
  public fun kernelMatrix(plan: BlurPlan): String {
    val reach = (plan.taps downTo 1) + (0..plan.taps)
    return reach.joinToString(" ") { (plan.weights[it] * KERNEL_SCALE).roundToInt().toString() }
  }

  /**
   * The fragment that blurs an opaque frame of [size] on a kept [transfer] grade by [plan], then multiplies each colour
   * by [gain], and hands it back as `yuva420p10le`.
   *
   * The blur and the gain run on float planes holding [SpatialSignal]'s scaled SDR signal, so they are the blur and the
   * gain of the SDR signal, and the alpha plane comes through as it came. With [weightedByAlpha] the signal is
   * premultiplied before the blur and divided by the blurred alpha after it, so a pixel weighs by its alpha, and the
   * alpha plane is blurred with it.
   */
  public fun onKeptGrade(
    plan: BlurPlan,
    size: Size,
    transfer: HdrTransfer,
    gain: Float,
    weightedByAlpha: Boolean = false,
  ): FilterFragment {
    val signal = SpatialSignal(transfer)
    val chain =
      buildList {
        addAll(signal.opening)
        if (weightedByAlpha) add(rangeOf("full"))
        add(format(FLOAT_RGBA))
        if (weightedByAlpha) add(FilterNode("premultiply", "inplace" to "1"))
        addAll(boxedDown(plan, plan, size, FLOAT_RGBA))
        addAll(blurredUp(plan, plan, size, FLOAT_RGBA, if (weightedByAlpha) ALL_PLANES else COLOR_PLANES))
        if (weightedByAlpha) addAll(listOf(FilterNode("unpremultiply", "inplace" to "1"), rangeOf("limited")))
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

// The sixteen bit format a blur runs in, which a sixteen bit frame already is.
private fun wideOf(pixelFormat: String): String = if (pixelFormat.endsWith("16le")) pixelFormat else WIDE_RGBA_BLUR

// One pass of the plan's weights on every plane of the bit mask and none other.
private fun convolution(
  plan: BlurPlan,
  planes: Int,
): FilterNode {
  val matrix = SpatialBlurGraph.kernelMatrix(plan)
  val arguments =
    (0 until PLANE_COUNT).filter { planes and (1 shl it) != 0 }.flatMap {
      listOf(
        "${it}m" to matrix,
        "${it}mode" to "row",
      )
    }
  return FilterNode("convolution", *arguments.toTypedArray())
}

private fun format(pixelFormat: String): FilterNode = FilterNode("format", "pix_fmts" to pixelFormat)

// A column pass is a row pass between two of these, since convolution's own column mode answers 0 on sixteen bit
// frames, which is ffmpeg's limit. dir 0 is the plain transpose, so two put every pixel back where it was.
private val TRANSPOSE = FilterNode("transpose", "dir" to "0")
private const val KERNEL_SCALE = 16384
private const val EIGHT_BIT_RGBA = "gbrap"
private const val WIDE_RGBA_BLUR = "gbrap16le"
private const val PLANE_COUNT = 4
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

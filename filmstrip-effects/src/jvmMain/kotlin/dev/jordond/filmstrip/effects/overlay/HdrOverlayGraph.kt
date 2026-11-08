package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.color.BT2020_CHROMA_SHIFT
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.AuxInput
import dev.jordond.filmstrip.effect.FilterArgument
import dev.jordond.filmstrip.effect.FilterCrop
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.PlaneLanes
import dev.jordond.filmstrip.effect.Sidecar
import dev.jordond.filmstrip.effects.FLOAT_RGB
import dev.jordond.filmstrip.effects.GRADE_TO_FLOAT
import dev.jordond.filmstrip.effects.HdrGradeTables
import dev.jordond.filmstrip.effects.LINEAR
import dev.jordond.filmstrip.effects.PIXEL
import dev.jordond.filmstrip.effects.displayLightFromHlgScene
import dev.jordond.filmstrip.effects.geq
import dev.jordond.filmstrip.effects.lut1d
import dev.jordond.filmstrip.effects.shiftedSignalToGrade
import dev.jordond.filmstrip.effects.signalFromDisplayLight
import dev.jordond.filmstrip.effects.signalFromGrade
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource

// On an HDR grade the overlay is blended in linear display light, which overlay cannot do, so the
// merge is maskedmerge on planar float RGB and runs on the part of the frame the overlay reaches.
// The picture already carried onto the grade's signal is read twice, once for its color and
// once for its alpha, at sixteen bits so no ten-bit code is lost on the way. Each branch is laid
// onto the region by its own cut, which expressions of the branch's own time move where the overlay
// is animated.
//
// A branch nothing changes from frame to frame is read once rather than looped, and maskedmerge
// holds its one frame under every frame of the region. That keeps the picture's decode to light
// off the frame loop for a still overlay or one that only fades, and the alpha's too when no
// window gates it and no fade drives it.
//
// A branch whose size moves is brought to its sixteen-bit RGBA ahead of its scale, since a converter
// after it is configured at the first frame's size and pins every frame to it. A fade runs on the
// mask alone, ahead of a size that moves and after one that holds, since geq reads the frame size
// it was configured at.
internal fun PlacedPicture.gradedOverlay(
  attributes: Attributes,
  drawnRange: TimeRange,
  graded: GraphPicture.Graded,
  frames: List<SampledFrame>?,
  still: PixelRect,
): FilterFragment {
  val rate = attributes.gridRate
  val animated = frames?.takeIf { rate != null }
  val layout = hdrOverlayLayout(animated?.map { it.drawn } ?: listOf(still), attributes.inputSize)
  val window =
    HdrWindow(
      x = animated?.runsOf { layout.windowX(it.drawn.x).toString() } ?: heldAt(layout.windowX(still.x)),
      y = animated?.runsOf { layout.windowY(it.drawn.y).toString() } ?: heldAt(layout.windowY(still.y)),
      width = animated?.runsOf { it.drawn.width.toString() } ?: heldAt(still.width),
      height = animated?.runsOf { it.drawn.height.toString() } ?: heldAt(still.height),
    )
  val opacity =
    if (animated == null || rate == null) {
      stillOpacityNodes(opacity)
    } else {
      opacityNodes(animated, rate, HDR_PLANES)
    }
  val fading = opacity.any { it.name == "geq" }
  val gate = listOfNotNull(windowGate(drawnRange, attributes.span, rate))

  // The picture's branch carries its color alone, and the mask's carries the opacity and the gate as well.
  fun branch(
    mask: Boolean,
    moving: Boolean,
  ): AuxInput {
    val alpha = if (mask) opacity else emptyList()
    val fadesFirst = fading && window.sized
    return AuxInput(
      ImageSource.ofBytes(graded.raster.png),
      buildList {
        if (window.sized) add(FilterNode("format", "pix_fmts" to HDR_OVERLAY_RGBA))
        if (fadesFirst) addAll(alpha)
        add(scaleNode(window.width, window.height, rate))
        if (!window.sized) add(FilterNode("format", "pix_fmts" to HDR_OVERLAY_RGBA))
        if (!fadesFirst) addAll(alpha)
        if (mask) addAll(gate)
        addAll(layout.windowNodes(window, rate))
      },
      if (moving) attributes.auxTimeline() else null,
    )
  }

  val picture = branch(mask = false, moving = window.moves)
  val mask = branch(mask = true, moving = window.moves || fading || gate.isNotEmpty())

  return hdrOverlayFragment(graded.transfer, layout, picture, mask)
}

/**
 * Where an overlay's picture is cut from its canvas on an HDR grade, and the size it is drawn at, frame by frame.
 *
 * @property x Where the window's left edge sits on the canvas.
 * @property y Where the window's top edge sits on the canvas.
 * @property width The picture's drawn width.
 * @property height The picture's drawn height.
 */
internal class HdrWindow(
  val x: FrameRuns,
  val y: FrameRuns,
  val width: FrameRuns,
  val height: FrameRuns,
) {
  /**
   * Whether the picture's size moves.
   */
  val sized: Boolean get() = !width.held || !height.held

  /**
   * Whether the picture's size or the window it is cut through moves.
   */
  val moves: Boolean get() = sized || !x.held || !y.held
}

/**
 * The nodes that pad an overlay's picture onto [HdrOverlayLayout.canvas] and cut out the [window] it is drawn through.
 *
 * A window that moves is cut by expressions of the branch's own time at [rate], and a picture whose size moves re-reads
 * the padding on every frame.
 */
internal fun HdrOverlayLayout.windowNodes(
  window: HdrWindow,
  rate: Int?,
): List<FilterNode> =
  buildList {
    val padding =
      buildList {
        add(FilterArgument("w", canvas.width.toString()))
        add(FilterArgument("h", canvas.height.toString()))
        add(FilterArgument("x", left.toString()))
        add(FilterArgument("y", top.toString()))
        add(FilterArgument("color", TRANSPARENT))
        if (window.sized) add(FilterArgument("eval", "frame"))
      }
    add(FilterNode("pad", padding))
    val whole = canvas.width == region.width && canvas.height == region.height
    val atOrigin = listOf(window.x, window.y).all { it.held && it.runs.single().value == "0" }
    if (!whole || !atOrigin) {
      val clock = rate?.let(::branchFrameClock).orEmpty()
      add(
        FilterNode(
          "crop",
          "w" to region.width.toString(),
          "h" to region.height.toString(),
          "x" to window.x.on(clock),
          "y" to window.y.on(clock),
        ),
      )
    }
  }

/**
 * An overlay's fragment on an HDR grade, blended in linear display light on [layout]'s region.
 *
 * The region is cut out of the frame, decoded from [transfer]'s signal to display light on planar float RGB, and
 * merged with [picture] by [mask], both decoded the same way. The merge is `a·overlay + (1 - a)·background` per
 * channel, the rule every backend lands an overlay texel on. The result is encoded back onto the signal and into the
 * grade's own YUV, which carries no alpha, so the region goes back onto the frame opaque and replaces what it cut.
 *
 * The grade's Y'CbCr goes to R'G'B' on the way in through [GRADE_TO_FLOAT] and [signalFromGrade], and back on the way
 * out through [shiftedSignalToGrade], so the matrix runs on the tables and mixer rows of [HdrGradeTables] and no
 * `format` step applies one. Each signal is decoded to light on [HdrGradeTables.lightCurve], with HLG's luminance gain
 * after it in geq, and the blend's light is encoded back in geq.
 *
 * [picture] and [mask] arrive as the same picture on the grade's signal, cut to the region's size. The color is read
 * from the first and the alpha, with any opacity already folded in, from the second. Both go to float one plane at a
 * time too, so every sixteen-bit sample is read as it is, and the picture is decoded to light the way the region is.
 */
internal fun hdrOverlayFragment(
  transfer: HdrTransfer,
  layout: HdrOverlayLayout,
  picture: AuxInput,
  mask: AuxInput,
): FilterFragment {
  val region = layout.region
  val tables = mutableListOf<Sidecar>()
  val light = tables.displayLightFromSignal(transfer)
  val encode = geq(signalFromDisplayLight(transfer, PIXEL).mapIndexed { channel, signal -> shifted(signal, channel) })

  return FilterFragment(
    crop =
      FilterCrop(
        width = region.width.toString(),
        height = region.height.toString(),
        x = region.x.toString(),
        y = region.y.toString(),
      ),
    chain = GRADE_TO_FLOAT + tables.signalFromGrade() + light,
    // The lanes pin their own input to planar RGB, which carries no alpha, and colorchannelmixer takes whatever format
    // the filter after it asks for. So each branch is pinned to its sixteen-bit RGBA right before its lanes, and the
    // mask's colorchannelmixer runs where the alpha it reads still is.
    auxInputs =
      listOf(
        AuxInput(picture.image, picture.chain + RGB_TO_FLOAT + light, picture.timeline),
        AuxInput(mask.image, mask.chain + ALPHA_TO_RGB + RGB_TO_FLOAT, mask.timeline),
      ),
    merge = FilterNode("maskedmerge"),
    post = listOf(encode) + tables.shiftedSignalToGrade(),
    sidecars = tables.distinct(),
  )
}

// The nodes that decode each channel of a signal on planar float to linear display light, as a fraction of the
// transfer's peak, adding the tables they read to this list. HLG's scene light reaches display light through a gain
// read off all three channels, which lut1d's per-channel tables cannot apply, so geq applies it.
private fun MutableList<Sidecar>.displayLightFromSignal(transfer: HdrTransfer): List<FilterNode> =
  buildList {
    add(lut1d(HdrGradeTables.LIGHT_WARP, LINEAR))
    add(lut1d(HdrGradeTables.lightCurve(transfer), LINEAR))
    if (transfer == HdrTransfer.Hlg) add(geq(displayLightFromHlgScene(PIXEL)))
  }

// A signal expression moved by its channel's share of the chroma offset, which the encode rows read it at.
private fun shifted(
  signal: String,
  channel: Int,
): String = "($signal)+(${BT2020_CHROMA_SHIFT[channel]})"

// An overlay branch's sixteen-bit RGBA carried onto planar float RGB one plane at a time, alpha left behind.
private val RGB_TO_FLOAT =
  listOf(FilterNode("format", "pix_fmts" to HDR_OVERLAY_RGBA), PlaneLanes.node(listOf("r", "g", "b"), FLOAT_RGB))

// Every color channel of the mask branch replaced by its alpha, which is what maskedmerge reads plane by plane.
private val ALPHA_TO_RGB =
  FilterNode("colorchannelmixer", "rr" to "0", "ra" to "1", "gg" to "0", "ga" to "1", "bb" to "0", "ba" to "1")

private const val TRANSPARENT = "black@0"

// Sixteen-bit RGBA, the depth an overlay carried onto a grade's signal is read at, and the planar form geq reads it in.
private const val HDR_OVERLAY_RGBA = "rgba64le"
private val HDR_PLANES = OpacityPlanes("gbrap16le", HDR_OVERLAY_RGBA)

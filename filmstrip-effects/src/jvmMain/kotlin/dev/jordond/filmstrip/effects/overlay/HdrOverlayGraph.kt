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
// onto the region by its own cut, which the commands move where the overlay is animated.
//
// A branch nothing changes from frame to frame is read once rather than looped, and maskedmerge
// holds its one frame under every frame of the region. That keeps the picture's decode to light
// off the frame loop for a still overlay or one that only fades, and the alpha's too when no
// window gates it and no fade drives it.
internal fun PlacedPicture.gradedOverlay(
  attributes: Attributes,
  drawnRange: TimeRange,
  graded: GraphPicture.Graded,
  animated: Driven.Commands?,
  still: PixelRect,
): FilterFragment {
  val layout = animated?.layout ?: hdrOverlayLayout(listOf(still), attributes.inputSize)
  val at = animated?.first ?: still

  fun branch(
    instance: String,
    sidecar: Sidecar?,
    size: List<String>,
    alpha: List<FilterNode>,
    moving: Boolean,
  ): AuxInput =
    AuxInput(
      ImageSource.ofBytes(graded.raster.png),
      buildList {
        sidecar?.let { add(FilterNode("sendcmd", "f" to it.placeholder)) }
        add(FilterNode("scale$instance", "w" to size[0], "h" to size[1]))
        add(FilterNode("format", "pix_fmts" to HDR_OVERLAY_RGBA))
        addAll(alpha)
        addAll(layout.windowNodes(at, instance, eachFrame = sidecar != null))
      },
      if (moving) attributes.auxTimeline() else null,
    )

  val stillSize = listOf(still.width.toString(), still.height.toString())
  val masked = animated?.let { it.instance + MASK_INSTANCE }.orEmpty()
  val driven = animated?.takeUnless { it.pictureHeld }
  val picture =
    when {
      driven != null -> branch(driven.instance, driven.sidecar, stillSize, emptyList(), moving = true)
      animated != null -> branch("", null, listOf(at.width.toString(), at.height.toString()), emptyList(), false)
      else -> branch("", null, stillSize, emptyList(), moving = false)
    }
  val mask =
    branch(
      masked,
      animated?.mask,
      stillSize,
      alphaNodes(drawnRange, attributes.span, attributes.gridRate, masked, animated != null),
      moving = animated != null || effect.visibleDuring != null,
    )

  return hdrOverlayFragment(graded.transfer, layout, picture, mask, listOfNotNull(driven?.sidecar, animated?.mask))
}

/**
 * The nodes that pad an overlay's picture onto [HdrOverlayLayout.canvas] and cut out the window drawn at [at].
 *
 * The cut is named `crop` plus [instance], so commands can move it. [eachFrame] re-reads the padding on every frame,
 * which a picture whose size is driven by commands needs.
 */
internal fun HdrOverlayLayout.windowNodes(
  at: PixelRect,
  instance: String,
  eachFrame: Boolean,
): List<FilterNode> =
  buildList {
    val padding =
      buildList {
        add(FilterArgument("w", canvas.width.toString()))
        add(FilterArgument("h", canvas.height.toString()))
        add(FilterArgument("x", left.toString()))
        add(FilterArgument("y", top.toString()))
        add(FilterArgument("color", TRANSPARENT))
        if (eachFrame) add(FilterArgument("eval", "frame"))
      }
    add(FilterNode("pad", padding))
    val windowX = windowX(at.x)
    val windowY = windowY(at.y)
    val whole = canvas.width == region.width && canvas.height == region.height
    if (instance.isNotEmpty() || !whole || windowX != 0 || windowY != 0) {
      add(
        FilterNode(
          "crop$instance",
          "w" to region.width.toString(),
          "h" to region.height.toString(),
          "x" to windowX.toString(),
          "y" to windowY.toString(),
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
  sidecars: List<Sidecar>,
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
    sidecars = sidecars + tables.distinct(),
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

// Sixteen-bit RGBA, the depth an overlay carried onto a grade's signal is read at.
private const val HDR_OVERLAY_RGBA = "rgba64le"

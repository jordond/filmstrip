package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.AuxInput
import dev.jordond.filmstrip.effect.FilterArgument
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.Sidecar
import dev.jordond.filmstrip.effects.displayLightFromSignal
import dev.jordond.filmstrip.effects.signalFromDisplayLight
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource

// Where a still overlay lands, in the numbers the SDR merge's corner expressions come to. The height is the one
// scale answers for a width and -1, which rounds the picture's aspect to the nearest pixel.
internal fun ImageOverlay.stillRect(
  drawnOn: Size,
  picture: Size,
  width: Int,
  margin: Int,
): PixelRect {
  val height = ((width.toLong() * picture.height + picture.width / 2) / picture.width).toInt()
  return PixelRect(
    x = if (corner.isTrailing) drawnOn.width - width - margin else margin,
    y = if (corner.isBottom) drawnOn.height - height - margin else margin,
    width = width,
    height = height,
  )
}

// On an HDR grade the overlay is blended in linear display light, which overlay cannot do, so the
// merge is maskedmerge on planar float RGB and runs on the part of the frame the overlay reaches.
// The picture already carried onto the grade's signal is read twice, once for its colour and
// once for its alpha, at sixteen bits so no ten-bit code is lost on the way. Each branch is laid
// onto the region by its own cut, which the commands move where the overlay is animated.
//
// A branch nothing changes from frame to frame is read once rather than looped, and maskedmerge
// holds its one frame under every frame of the region. That keeps the picture's decode to light
// off the frame loop for a still overlay or one that only fades, and the alpha's too when no
// window gates it and no fade drives it.
internal fun ImageOverlay.gradedOverlay(
  attributes: Attributes,
  run: TimeRange,
  transfer: HdrTransfer,
  graded: HdrOverlayRaster,
  animated: Driven.Commands?,
  still: PixelRect,
  overlayWidth: Int,
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
      ImageSource.ofBytes(graded.png),
      buildList {
        sidecar?.let { add(FilterNode("sendcmd", "f" to it.placeholder)) }
        add(FilterNode("scale$instance", "w" to size[0], "h" to size[1]))
        add(FilterNode("format", "pix_fmts" to HDR_OVERLAY_RGBA))
        addAll(alpha)
        addAll(layout.windowNodes(at, instance, eachFrame = sidecar != null))
      },
      if (moving) attributes.auxTimeline() else null,
    )

  val stillSize = listOf(overlayWidth.toString(), "-1")
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
      alphaNodes(run, attributes.span, attributes.gridRate, masked, animated != null),
      moving = animated != null || visibleDuring != null,
    )

  return hdrOverlayFragment(transfer, layout, picture, mask, listOfNotNull(driven?.sidecar, animated?.mask))
}

/**
 * A rectangle on a frame, in whole pixels.
 *
 * @property x The left edge.
 * @property y The top edge.
 * @property width The width.
 * @property height The height.
 */
internal class PixelRect(
  val x: Int,
  val y: Int,
  val width: Int,
  val height: Int,
)

/**
 * Where an overlay on an HDR grade is blended, and how its picture is laid out to line up with that part of the frame.
 *
 * The blend runs on [region] alone, cut out of the frame, and both of its inputs have to be that size. The picture is
 * padded onto a transparent canvas of [canvas] with its corner at [left] and [top], and the window of the canvas the
 * region covers is cut out wherever the picture is drawn, so the layer and the region line up pixel for pixel.
 *
 * @property region The part of the frame every drawn position of the overlay falls inside.
 * @property canvas The size of the transparent canvas the picture is padded onto.
 * @property left Where the picture's left edge sits on the canvas.
 * @property top Where the picture's top edge sits on the canvas.
 */
internal class HdrOverlayLayout(
  val region: PixelRect,
  val canvas: Size,
  val left: Int,
  val top: Int,
  private val largest: Size,
) {
  // Where the window is cut from the canvas for a picture drawn at a position on the frame. A picture drawn wholly
  // outside the region is held just clear of its edge, where the window reads nothing but the transparent canvas.
  fun windowX(drawnX: Int): Int = left - (drawnX - region.x).coerceIn(-largest.width, region.width)

  fun windowY(drawnY: Int): Int = top - (drawnY - region.y).coerceIn(-largest.height, region.height)
}

/**
 * Lays out an overlay drawn at every rectangle in [drawn] on a frame of [frame]'s size.
 *
 * The region is the smallest one holding every rectangle's part of the frame, widened to even pixels so a 4:2:0 frame
 * is cut on a chroma sample. The canvas is as small as it can be while every window stays on it.
 */
internal fun hdrOverlayLayout(
  drawn: List<PixelRect>,
  frame: Size,
): HdrOverlayLayout {
  fun span(
    starts: List<Int>,
    ends: List<Int>,
    extent: Int,
  ): Pair<Int, Int> {
    val start = starts.min().coerceIn(0, extent - 1) and EVEN
    val end = (ends.max().coerceIn(0, extent) + 1 and EVEN).coerceAtMost(extent)
    return start to (end - start).coerceAtLeast(1)
  }

  val (x, width) = span(drawn.map { it.x }, drawn.map { it.x + it.width }, frame.width)
  val (y, height) = span(drawn.map { it.y }, drawn.map { it.y + it.height }, frame.height)
  val largest = Size(drawn.maxOf { it.width }, drawn.maxOf { it.height })
  val offsetsX = drawn.map { (it.x - x).coerceIn(-largest.width, width) }
  val offsetsY = drawn.map { (it.y - y).coerceIn(-largest.height, height) }
  val left = offsetsX.max().coerceAtLeast(0)
  val top = offsetsY.max().coerceAtLeast(0)

  return HdrOverlayLayout(
    region = PixelRect(x, y, width, height),
    canvas =
      Size(
        maxOf(left + largest.width, left - offsetsX.min() + width),
        maxOf(top + largest.height, top - offsetsY.min() + height),
      ),
    left = left,
    top = top,
    largest = largest,
  )
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
 * [picture] and [mask] arrive as the same picture on the grade's signal, cut to the region's size. The colour is read
 * from the first and the alpha, with any opacity already folded in, from the second.
 */
internal fun hdrOverlayFragment(
  transfer: HdrTransfer,
  layout: HdrOverlayLayout,
  picture: AuxInput,
  mask: AuxInput,
  sidecars: List<Sidecar>,
): FilterFragment {
  val region = layout.region
  val light = geq(displayLightFromSignal(transfer, PIXEL))

  return FilterFragment(
    crop =
      FilterNode(
        "crop",
        "w" to region.width.toString(),
        "h" to region.height.toString(),
        "x" to region.x.toString(),
        "y" to region.y.toString(),
        "exact" to "1",
      ),
    chain =
      listOf(
        FilterNode("scale", "in_color_matrix" to GRADE_MATRIX, "in_range" to GRADE_RANGE),
        FilterNode("format", "pix_fmts" to FLOAT_RGB),
        light,
      ),
    auxInputs =
      listOf(
        AuxInput(
          picture.image,
          picture.chain + FilterNode("format", "pix_fmts" to FLOAT_RGB) + light,
          picture.timeline,
        ),
        AuxInput(mask.image, mask.chain + ALPHA_TO_RGB + FilterNode("format", "pix_fmts" to FLOAT_RGB), mask.timeline),
      ),
    merge = FilterNode("maskedmerge"),
    post =
      listOf(
        geq(signalFromDisplayLight(transfer, PIXEL)),
        FilterNode("scale", "out_color_matrix" to GRADE_MATRIX, "out_range" to GRADE_RANGE),
        FilterNode("format", "pix_fmts" to GRADE_YUV),
      ),
    sidecars = sidecars,
  )
}

private fun geq(rgb: List<String>): FilterNode = FilterNode("geq", "r" to rgb[0], "g" to rgb[1], "b" to rgb[2])

// The three planes of the pixel geq is writing, as it reads them.
private val PIXEL = listOf("r(X,Y)", "g(X,Y)", "b(X,Y)")

// Every colour channel of the mask branch replaced by its alpha, which is what maskedmerge reads plane by plane.
private val ALPHA_TO_RGB =
  FilterNode("colorchannelmixer", "rr" to "0", "ra" to "1", "gg" to "0", "ga" to "1", "bb" to "0", "ba" to "1")

// Planar float RGB, which both geq and maskedmerge run on, so linear light is held without banding.
private const val FLOAT_RGB = "gbrpf32le"

// The grade's own YUV: BT.2020 non-constant luminance, limited range, ten-bit 4:2:0.
private const val GRADE_YUV = "yuv420p10le"
private const val GRADE_MATRIX = "bt2020"
private const val GRADE_RANGE = "tv"

private const val TRANSPARENT = "black@0"

// Clears the lowest bit, which rounds a non-negative pixel position down to an even one.
private const val EVEN = 1.inv()

// Sixteen-bit RGBA, the depth an overlay carried onto a grade's signal is read at.
private const val HDR_OVERLAY_RGBA = "rgba64le"

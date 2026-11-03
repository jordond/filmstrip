package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.AuxInput
import dev.jordond.filmstrip.effect.Degradation
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.FilterArgument
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.PictureOrientation
import kotlin.math.roundToInt

/**
 * Lowers [spec] onto a filter graph fragment that draws it over the frames it merges onto.
 *
 * On an SDR frame the picture is scaled and merged with `overlay`, whose corner arithmetic is written as an expression
 * of the main frame's W and H and the overlay's w and h. The margin is not. It is a fraction of the shorter side of
 * the frame the overlay is drawn on, which is the input, the clip's own for a clip overlay and the output for a
 * composition one. A picture that describes its color is drawn from the sRGB codes its description gives it,
 * through [sdrOverlayImage], an AVIF or HEIC from its decode, and any other untagged one from its own bytes. On an HDR
 * grade the picture is decoded and blended in linear display light instead. [decoder] decodes every picture this needs
 * decoded and sizes one for an animation, so the texels and the size are the ones the toolchain draws, and a picture
 * decoded anyway is sized from that decode. Without one the JDK reads them. An animation that cannot be driven at all
 * is refused before the picture is read.
 *
 * @return The fragment, [EffectResolution.Degraded] by each limit the decode of its picture reports, or
 * [EffectResolution.Unsupported] when the picture or its animation cannot be lowered here.
 */
internal fun filterGraphOverlay(
  spec: ImageOverlay,
  attributes: Attributes,
  decoder: OverlayPictureDecoder?,
): EffectResolution {
  val drawnOn = attributes.inputSize
  val span = attributes.span
  val drawnRange = spec.drawnRangeWithin(span)
  spec.animationRefusal(attributes, drawnRange)?.let { return EffectResolution.Unsupported(spec.id, it) }
  val margin = (minOf(drawnOn.width, drawnOn.height) * spec.margin).roundToInt()
  val overlayWidth = (drawnOn.width * spec.scale).roundToInt().coerceAtLeast(1)
  val transfer = attributes.hdrTransfer
  if (transfer != null) {
    val picture =
      when (val read = storedPicture(spec.image, decoder)) {
        is StoredRead.Read -> read.picture
        is StoredRead.Undecoded -> return spec.refused(UNREADABLE_HDR_IMAGE.saying(read.reason))
        is StoredRead.Undrawn -> return spec.refused(read.reason)
        StoredRead.Unread -> return spec.refused(UNREADABLE_HDR_IMAGE)
        StoredRead.Unseen -> return spec.refused(UNSEEN_COLOR)
      }
    val graded = hdrOverlayRaster(picture, transfer)
    val driven = spec.commandsFor(attributes, drawnRange, graded = true, decoder, graded.size)
    if (driven is Driven.Refused) return EffectResolution.Unsupported(spec.id, driven.message)
    val animated = driven as? Driven.Commands
    val still = spec.stillRect(drawnOn, graded.size, overlayWidth, margin)
    val fragment = spec.gradedOverlay(attributes, drawnRange, transfer, graded, animated, still, overlayWidth)
    return resolution(PlatformEffect(fragment), picture.limits)
  }
  val drawn =
    when (val read = sdrOverlayImage(spec.image, decoder)) {
      is SdrOverlayImage.Drawn -> read
      is SdrOverlayImage.Refused -> return EffectResolution.Unsupported(spec.id, read.message)
    }
  val driven = spec.commandsFor(attributes, drawnRange, graded = false, decoder, drawn.size)
  if (driven is Driven.Refused) return EffectResolution.Unsupported(spec.id, driven.message)
  val animated = driven as? Driven.Commands
  val instance = animated?.instance.orEmpty()

  val prepare =
    buildList {
      addAll(orientationNodes(drawn.orientation))
      animated?.let { add(FilterNode("sendcmd", "f" to it.sidecar.placeholder)) }
      add(FilterNode("scale$instance", "w" to overlayWidth.toString(), "h" to "-1"))
      add(FilterNode("format", "pix_fmts" to "rgba"))
      addAll(spec.alphaNodes(drawnRange, span, attributes.gridRate, instance, animated != null))
      // ffmpeg's own limit, and only where a command can resize the branch. Where the frames the
      // overlay merges onto carry alpha, which is what a deferred fill leaves them holding, the
      // converter ffmpeg inserts to bring both inputs to one pixel format is a scale pinned to
      // the size it was configured at, and it silently undoes a commanded resize. A scale of its
      // own input's size, re-read every frame, carries the new size past it.
      if (animated != null) add(FilterNode("scale", PER_FRAME_SIZE))
    }

  val placement =
    buildList {
      add(FilterArgument("x", if (spec.corner.isTrailing) "W-w-$margin" else "$margin"))
      add(FilterArgument("y", if (spec.corner.isBottom) "H-h-$margin" else "$margin"))
      add(FilterArgument("format", "auto"))
      // What overlay does once the image branch ends: hand the main frame on undrawn. The branch
      // is looped for as long as the one it merges onto, so nothing reaches it.
      add(FilterArgument("eof_action", "pass"))
    }

  val fragment =
    FilterFragment(
      auxInputs = listOf(AuxInput(drawn.image, prepare, attributes.auxTimeline())),
      merge = FilterNode("overlay$instance", placement),
      sidecars = listOfNotNull(animated?.sidecar),
    )
  return resolution(PlatformEffect(fragment), drawn.limits)
}

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

/**
 * The nodes that lay a picture opened as stored out the way [orientation] shows it, ahead of the scale so the overlay's
 * width is the shown picture's.
 *
 * Each value maps to the permutation [PictureOrientation.sourceOf] reads. `transpose` turns a quarter turn, clockwise
 * at `clock` and anticlockwise at `cclock`, and its `_flip` forms mirror top to bottom after the turn. `hflip` mirrors
 * left to right and `vflip` top to bottom. Every node moves whole texels, so the picture keeps every sample.
 */
internal fun orientationNodes(orientation: PictureOrientation): List<FilterNode> =
  when (orientation) {
    PictureOrientation.Normal -> emptyList()
    PictureOrientation.FlipHorizontal -> listOf(FilterNode("hflip"))
    PictureOrientation.Rotate180 -> listOf(FilterNode("hflip"), FilterNode("vflip"))
    PictureOrientation.FlipVertical -> listOf(FilterNode("vflip"))
    PictureOrientation.Transpose -> listOf(FilterNode("transpose", "dir" to "cclock_flip"))
    PictureOrientation.Rotate90 -> listOf(FilterNode("transpose", "dir" to "clock"))
    PictureOrientation.Transverse -> listOf(FilterNode("transpose", "dir" to "clock_flip"))
    PictureOrientation.Rotate270 -> listOf(FilterNode("transpose", "dir" to "cclock"))
  }

// Degraded by each of the decoder's [limits], and resolved exactly when there are none.
private fun resolution(
  effect: PlatformEffect,
  limits: List<Degradation>,
): EffectResolution =
  if (limits.isEmpty()) EffectResolution.Resolved(effect) else EffectResolution.Degraded(effect, limits)

internal val Corner.isTrailing: Boolean
  get() = this == Corner.TopEnd || this == Corner.BottomEnd

internal val Corner.isBottom: Boolean
  get() = this == Corner.BottomStart || this == Corner.BottomEnd

private fun ImageOverlay.refused(message: String): EffectResolution = EffectResolution.Unsupported(id, message)

// A scale of its own input's size, re-read on every frame.
private val PER_FRAME_SIZE =
  listOf(FilterArgument("w", "iw"), FilterArgument("h", "ih"), FilterArgument("eval", "frame"))

private const val UNREADABLE_HDR_IMAGE =
  "The overlay image could not be read. On an HDR grade this backend decodes the picture to carry " +
    "its colors onto the grade's signal, and it could not decode it, so the same picture fails on an " +
    "SDR grade too. Check that the path or URL is readable by this process, and that the bytes are a " +
    "picture this ffmpeg build decodes."

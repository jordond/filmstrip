package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.AuxInput
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.FilterArgument
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.picture.PictureOrientation

/**
 * Lowers [spec] onto a filter graph fragment that draws it over the frames it merges onto.
 *
 * The picture is read for the grade it lands on and placed by [ImageOverlay.placedOn] against its shown size, then
 * drawn by [lowered]. A picture that describes its color is drawn on an SDR grade from the sRGB codes its description
 * gives it, through [sdrOverlayImage], an AVIF or HEIC from its decode, and any other untagged one from its own bytes.
 * On an HDR grade the picture is decoded and blended in linear display light instead. [decoder] decodes every picture
 * this needs decoded, so the texels are the ones the toolchain draws, and a picture decoded anyway is sized from that
 * decode. One that was not is sized by the frame [decoder] checks it yields. Without a decoder the JDK reads them all,
 * and [shownSize] reads the size off the header. A picture this process cannot read, one that yields no frame, or an
 * animation that cannot be driven at all, is refused before the graph is built.
 *
 * @param bytes The picture's bytes as this lowering read them, or null when this process cannot read them.
 *
 * @return The fragment, [EffectResolution.Degraded] by each limit the decode of its picture reports, or
 * [EffectResolution.Unsupported] when the picture or its animation cannot be lowered here.
 */
internal fun filterGraphOverlay(
  spec: ImageOverlay,
  attributes: Attributes,
  decoder: OverlayPictureDecoder?,
  read: HeldBytes = spec.image.encodedRead(),
): EffectResolution {
  read.refusal?.let { return spec.refused(it) }
  val bytes = read.bytes
  val drawnOn = attributes.inputSize
  spec.animationRefusal(attributes, spec.drawnRangeWithin(attributes.span))?.let { return spec.refused(it) }
  val transfer = attributes.hdrTransfer
  if (transfer != null) {
    val picture =
      when (val read = storedPicture(spec.image, decoder, bytes)) {
        is StoredRead.Read -> read.picture
        is StoredRead.Undecoded -> return spec.refused(UNREADABLE_HDR_IMAGE.saying(read.reason))
        is StoredRead.Undrawn -> return spec.refused(read.reason)
        StoredRead.Unread -> return spec.refused(UNREADABLE_HDR_IMAGE)
        StoredRead.Unseen -> return spec.refused(UNSEEN_COLOR)
      }
    val graded = hdrOverlayRaster(picture, transfer)
    val placement = spec.placedOn(drawnOn, graded.size)
    return PlacedPicture(spec, GraphPicture.Graded(graded, transfer), placement, spec.opacity, picture.limits)
      .lowered(attributes)
  }
  val drawn =
    when (val read = sdrOverlayImage(spec.image, decoder, bytes)) {
      is SdrOverlayImage.Drawn -> read
      is SdrOverlayImage.Refused -> return spec.refused(read.message)
    }
  val shown = drawn.size ?: drawn.image.shownSize { return spec.refused(it) }
  val placement = spec.placedOn(drawnOn, shown)
  val opened = GraphPicture.Opened(drawn.image, drawn.orientation)
  return PlacedPicture(spec, opened, placement, spec.opacity, drawn.limits).lowered(attributes)
}

/**
 * Lowers this picture onto a filter graph fragment that draws it over the frames it merges onto.
 *
 * Every size and position is the pixels [drawnRect] reads off [PlacedPicture.placement], or off each frame's placement
 * when the overlay is animated. A [GraphPicture.Opened] picture is scaled and merged with `overlay`, whose `format` the
 * backend sets for the frame it draws on, along with the grid that puts `x` and `y` on. A [GraphPicture.Graded] one is
 * blended in linear display light by [gradedOverlay].
 *
 * @return The fragment, [EffectResolution.Degraded] by each of [PlacedPicture.limits], or
 * [EffectResolution.Unsupported] when the animation cannot be driven here.
 */
internal fun PlacedPicture.lowered(attributes: Attributes): EffectResolution {
  val drawnRange = effect.drawnRangeWithin(attributes.span)
  val still = drawnRect(placement, attributes.inputSize)
  val driven = commandsFor(attributes, drawnRange, graded = picture is GraphPicture.Graded)
  if (driven is Driven.Refused) return EffectResolution.Unsupported(effect.id, driven.message)
  val animated = driven as? Driven.Commands
  val fragment =
    when (val picture = picture) {
      is GraphPicture.Graded -> gradedOverlay(attributes, drawnRange, picture, animated, still)
      is GraphPicture.Opened -> openedOverlay(attributes, drawnRange, picture, animated, still)
    }
  return if (limits.isEmpty()) {
    EffectResolution.Resolved(PlatformEffect(fragment))
  } else {
    EffectResolution.Degraded(PlatformEffect(fragment), limits)
  }
}

private fun PlacedPicture.openedOverlay(
  attributes: Attributes,
  drawnRange: TimeRange,
  picture: GraphPicture.Opened,
  animated: Driven.Commands?,
  still: PixelRect,
): FilterFragment {
  val instance = animated?.instance.orEmpty()
  val prepare =
    buildList {
      addAll(orientationNodes(picture.orientation))
      animated?.let { add(FilterNode("sendcmd", "f" to it.sidecar.placeholder)) }
      add(FilterNode("scale$instance", "w" to still.width.toString(), "h" to still.height.toString()))
      add(FilterNode("format", "pix_fmts" to "rgba"))
      addAll(alphaNodes(drawnRange, attributes.span, attributes.gridRate, instance, animated != null))
      // ffmpeg's own limit, and only where a command can resize the branch. The converter ffmpeg
      // inserts to bring the picture to the format the overlay runs in is a scale pinned to the
      // size it was configured at, and it silently undoes a commanded resize. A scale of its own
      // input's size, re-read every frame, carries the new size past it.
      if (animated != null) add(FilterNode("scale", PER_FRAME_SIZE))
    }

  val merge =
    listOf(
      FilterArgument("x", still.x.toString()),
      FilterArgument("y", still.y.toString()),
      // What overlay does once the image branch ends: hand the main frame on undrawn. The branch
      // is looped for as long as the one it merges onto, so nothing reaches it.
      FilterArgument("eof_action", "pass"),
    )

  return FilterFragment(
    auxInputs = listOf(AuxInput(picture.image, prepare, attributes.auxTimeline())),
    merge = FilterNode("overlay$instance", merge),
    sidecars = listOfNotNull(animated?.sidecar),
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

private fun ImageOverlay.refused(message: String): EffectResolution = EffectResolution.Unsupported(id, message)

// A scale of its own input's size, re-read on every frame.
private val PER_FRAME_SIZE =
  listOf(FilterArgument("w", "iw"), FilterArgument("h", "ih"), FilterArgument("eval", "frame"))

private const val UNREADABLE_HDR_IMAGE =
  "The overlay image could not be read. On an HDR grade this backend decodes the picture to carry " +
    "its colors onto the grade's signal, and it could not decode it, so the same picture fails on an " +
    "SDR grade too. Check that the path or URL is readable by this process, and that the bytes are a " +
    "picture this ffmpeg build decodes."

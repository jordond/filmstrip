package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.FilmstripError
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
 * animation that cannot be driven at all, is refused before the graph is built. A decode stopped at its deadline fails
 * with the [FilmstripError.TimedOut] it was stopped with.
 *
 * @param bytes The picture's bytes as this lowering read them, or null when this process cannot read them.
 *
 * @return The fragment, [EffectResolution.Degraded] by each limit the decode of its picture reports, or
 * [EffectResolution.Unsupported] when the picture or its animation cannot be lowered here, or [EffectResolution.Failed]
 * when its decode was stopped at its deadline.
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
        is StoredRead.Expired -> return EffectResolution.Failed(read.timedOut)
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
      is SdrOverlayImage.Expired -> return EffectResolution.Failed(read.timedOut)
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
  val frames =
    when (val sampling = sampledFrames(attributes, drawnRange)) {
      Sampling.Still -> null
      is Sampling.Refused -> return EffectResolution.Unsupported(effect.id, sampling.message)
      is Sampling.Sampled -> sampling.frames
    }
  val fragment =
    when (val picture = picture) {
      is GraphPicture.Graded -> gradedOverlay(attributes, drawnRange, picture, frames, still)
      is GraphPicture.Opened -> openedOverlay(attributes, drawnRange, picture, frames, still)
    }
  return if (limits.isEmpty()) {
    EffectResolution.Resolved(PlatformEffect(fragment))
  } else {
    EffectResolution.Degraded(PlatformEffect(fragment), limits)
  }
}

// An animated picture is drawn by expressions of each frame's own time, one for each property that moves, and a
// property that holds is written as its one value. geq reads the frame size it was configured at, so an opacity that
// moves is applied ahead of a size that moves, on the picture at its own size, and after a size that holds.
private fun PlacedPicture.openedOverlay(
  attributes: Attributes,
  drawnRange: TimeRange,
  picture: GraphPicture.Opened,
  frames: List<SampledFrame>?,
  still: PixelRect,
): FilterFragment {
  val rate = attributes.gridRate
  val animated = frames?.takeIf { rate != null }
  val x = animated?.runsOf { it.drawn.x.toString() } ?: heldAt(still.x)
  val y = animated?.runsOf { it.drawn.y.toString() } ?: heldAt(still.y)
  val width = animated?.runsOf { it.drawn.width.toString() } ?: heldAt(still.width)
  val height = animated?.runsOf { it.drawn.height.toString() } ?: heldAt(still.height)
  val sized = !width.held || !height.held
  val opacity =
    if (animated == null || rate == null) {
      stillOpacityNodes(opacity)
    } else {
      opacityNodes(animated, rate, SDR_PLANES)
    }
  val fadesFirst = sized && opacity.any { it.name == "geq" }
  val prepare =
    buildList {
      addAll(orientationNodes(picture.orientation))
      // A size that moves is read by a scale already handed RGBA and asked for RGBA, so no converter sits after it. A
      // converter there is configured at the first frame's size and pins every frame to it.
      if (sized) add(FilterNode("format", "pix_fmts" to SDR_RGBA))
      if (fadesFirst) addAll(opacity)
      add(scaleNode(width, height, rate))
      add(FilterNode("format", "pix_fmts" to SDR_RGBA))
      if (!fadesFirst) addAll(opacity)
      windowGate(drawnRange, attributes.span, rate)?.let { add(it) }
      // ffmpeg's own limit, and only where the size moves. The converter ffmpeg inserts to bring the picture to the
      // format the overlay runs in is a scale pinned to the size it was configured at, and it silently undoes a size
      // read per frame. A scale of its own input's size, re-read every frame, carries the new size past it.
      if (sized) add(FilterNode("scale", PER_FRAME_SIZE))
    }

  val merge =
    buildList {
      add(FilterArgument("x", x.on(mergeClockOf(rate))))
      add(FilterArgument("y", y.on(mergeClockOf(rate))))
      // What overlay does once the image branch ends: hand the main frame on undrawn. The branch
      // is looped for as long as the one it merges onto, so nothing reaches it.
      add(FilterArgument("eof_action", "pass"))
      if (!x.held || !y.held) add(FilterArgument("eval", "frame"))
    }

  return FilterFragment(
    auxInputs = listOf(AuxInput(picture.image, prepare, attributes.auxTimeline())),
    merge = FilterNode("overlay", merge),
  )
}

// A held series is written as its value and never reads a clock, so one with no rate to key on is still written.
private fun mergeClockOf(rate: Int?): String = rate?.let(::mergeFrameClock).orEmpty()

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

// Eight-bit RGBA, the format an SDR overlay's picture is drawn from, and the planar form geq reads it in.
private const val SDR_RGBA = "rgba"
private val SDR_PLANES = OpacityPlanes("gbrap", SDR_RGBA)

private const val UNREADABLE_HDR_IMAGE =
  "The overlay image could not be read. On an HDR grade this backend decodes the picture to carry " +
    "its colors onto the grade's signal, and it could not decode it, so the same picture fails on an " +
    "SDR grade too. Check that the path or URL is readable by this process, and that the bytes are a " +
    "picture this ffmpeg build decodes."

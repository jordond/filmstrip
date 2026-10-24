package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.AuxInput
import dev.jordond.filmstrip.effect.AuxTimeline
import dev.jordond.filmstrip.effect.Degradation
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.FilterArgument
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.geometry.Corner
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * Lowers [spec] onto a filter graph fragment that draws it over the frames it merges onto.
 *
 * On an SDR frame the picture is scaled and merged with `overlay`, whose corner arithmetic is written as an expression
 * of the main frame's W and H and the overlay's w and h. The margin is not. It is a fraction of the shorter side of
 * the frame the overlay is drawn on, which is the input, the clip's own for a clip overlay and the output for a
 * composition one. A picture that describes its colour is drawn from the sRGB codes its description gives it,
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
  val run = spec.runWithin(span)
  spec.animationRefusal(attributes, run)?.let { return EffectResolution.Unsupported(spec.id, it) }
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
        StoredRead.Unseen -> return spec.refused(UNSEEN_COLOUR)
      }
    val graded = hdrOverlayRaster(picture, transfer)
    val driven = spec.commandsFor(attributes, run, graded = true, decoder, graded.size)
    if (driven is Driven.Refused) return EffectResolution.Unsupported(spec.id, driven.message)
    val animated = driven as? Driven.Commands
    val still = spec.stillRect(drawnOn, graded.size, overlayWidth, margin)
    val fragment = spec.gradedOverlay(attributes, run, transfer, graded, animated, still, overlayWidth)
    return resolution(PlatformEffect(fragment), picture.limits)
  }
  val drawn =
    when (val read = sdrOverlayImage(spec.image, decoder)) {
      is SdrOverlayImage.Drawn -> read
      is SdrOverlayImage.Refused -> return EffectResolution.Unsupported(spec.id, read.message)
    }
  val driven = spec.commandsFor(attributes, run, graded = false, decoder, drawn.size)
  if (driven is Driven.Refused) return EffectResolution.Unsupported(spec.id, driven.message)
  val animated = driven as? Driven.Commands
  val instance = animated?.instance.orEmpty()

  val prepare =
    buildList {
      animated?.let { add(FilterNode("sendcmd", "f" to it.sidecar.placeholder)) }
      add(FilterNode("scale$instance", "w" to overlayWidth.toString(), "h" to "-1"))
      add(FilterNode("format", "pix_fmts" to "rgba"))
      addAll(spec.alphaNodes(run, span, attributes.gridRate, instance, animated != null))
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

// Degraded by each of the decoder's [limits], and resolved exactly when there are none.
private fun resolution(
  effect: PlatformEffect,
  limits: List<Degradation>,
): EffectResolution =
  if (limits.isEmpty()) EffectResolution.Resolved(effect) else EffectResolution.Degraded(effect, limits)

// The gate and the opacity, which is everything a branch does to its picture's alpha.
internal fun ImageOverlay.alphaNodes(
  run: TimeRange,
  span: TimeRange,
  rate: Int?,
  instance: String,
  driven: Boolean,
): List<FilterNode> =
  buildList {
    // After the format, which is the node that guarantees the alpha channel the gate zeroes.
    windowGate(run, span, rate)?.let { add(it) }
    when {
      // The sampler has already folded the authored opacity into every value it answers, so the
      // node the commands drive opens at one rather than at what was authored.
      driven -> add(FilterNode("colorchannelmixer$instance", "aa" to "1"))
      opacity < 1f -> add(FilterNode("colorchannelmixer", "aa" to opacity.toString()))
    }
  }

// The window, as a node on the overlay's own branch: outside the run the branch is handed on
// fully transparent, so the merge blends nothing onto the frame under it, and inside it the node
// is switched off and passes the picture through untouched.
//
// t is the image branch's own clock, read ahead of the setpts that rebases it: an export opens
// that branch at zero and a scrubbed preview carries it to the seek with -itsoffset, so either
// way it reads the composition time the frame belongs to. It counts from the slot the span opens
// on, which is where the branch's first frame sits.
//
// The bound is half open, the way TimeRange and frameWithin read it, so the frame sitting on the
// run's end is outside it, as it is on every other backend.
//
// The branch advances on the output frame grid, so the frames this opens over are the ones the
// shared sampler names. A clip's merge runs ahead of the tail's fps, and that resampler builds
// two output frames out of one source frame either side of a boundary, so on a clip whose own
// rate differs from the output's an edge lands within one output frame of the sampler's. That is
// the resampler's limit rather than the window's.
private fun ImageOverlay.windowGate(
  run: TimeRange,
  span: TimeRange,
  rate: Int?,
): FilterNode? {
  if (visibleDuring == null) return null
  val end = run.endExclusive ?: return null

  val open = "gte(t,${branchSeconds(run.start, span, rate)})*lt(t,${branchSeconds(end, span, rate)})"
  return FilterNode("colorchannelmixer", "aa" to "0", "enable" to "not($open)")
}

// An image input is one frame at t = 0, and a branch carrying one frame ends on it. The overlay
// is drawn for as long as the branch it merges onto, so the still is repeated across that whole
// run. A pipeline that resolved neither a rate nor a bounded span has nothing to repeat it over.
internal fun Attributes.auxTimeline(): AuxTimeline? {
  val rate = frameRate?.takeIf { it > 0f } ?: return null
  val length = span.duration ?: return null

  return AuxTimeline(rate, length)
}

internal val Corner.isTrailing: Boolean
  get() = this == Corner.TopEnd || this == Corner.BottomEnd

internal val Corner.isBottom: Boolean
  get() = this == Corner.BottomStart || this == Corner.BottomEnd

// Where a composition time lands on the branch the effect runs on, which is the clock every time
// expression on that branch is read against. With a rate, the bound sits half a frame ahead of the
// first frame at or after [time], so that frame's own time is clear of it however either side
// rounds its division. With none, the time is moved onto the branch as it stands.
private fun branchSeconds(
  time: Duration,
  span: TimeRange,
  rate: Int?,
): String =
  if (rate == null) {
    (time - span.start).toDouble(DurationUnit.SECONDS).toString()
  } else {
    ((frameIndexAt(time, span, rate) - HALF_FRAME) / rate).toString()
  }

private fun ImageOverlay.refused(message: String): EffectResolution = EffectResolution.Unsupported(id, message)

// A refusal with the toolchain's own account of the failure after it, when it gave one.
internal fun String.saying(reason: String?): String =
  if (reason.isNullOrBlank()) this else "$this The toolchain said: ${reason.trim()}"

// The output rate the planner resolved, which it carries as a Float holding a whole number.
internal val Attributes.gridRate: Int?
  get() = frameRate?.takeIf { it > 0f }?.roundToInt()

// Half a frame, in frames, which is how far ahead of a frame a window bound is written.
private const val HALF_FRAME = 0.5

// A scale of its own input's size, re-read on every frame.
private val PER_FRAME_SIZE =
  listOf(FilterArgument("w", "iw"), FilterArgument("h", "ih"), FilterArgument("eval", "frame"))

private const val UNREADABLE_HDR_IMAGE =
  "The overlay image could not be read. On an HDR grade this backend decodes the picture to carry " +
    "its colours onto the grade's signal, and it could not decode it, so the same picture fails on an " +
    "SDR grade too. Check that the path or URL is readable by this process, and that the bytes are a " +
    "picture this ffmpeg build decodes."

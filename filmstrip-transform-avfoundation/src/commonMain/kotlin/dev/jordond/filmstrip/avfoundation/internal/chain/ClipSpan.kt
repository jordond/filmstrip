package dev.jordond.filmstrip.avfoundation.internal.chain

import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.edit.outputSlotsOf
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.geometry.ClipPlacement
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedEffect
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
import dev.jordond.filmstrip.transform.internal.resolve.drawnRect
import kotlin.time.Duration

/**
 * One clip's slot on the composition timeline, and what to draw on the frames inside it.
 *
 * The filter handler is given a composition time and nothing else, so the only way back to the clip
 * a frame came from is a range lookup. Spans tile the whole timeline exactly. Each one's end is the
 * next one's start and the last reaches the composition's duration. A gap or an overlap fails the
 * render with `AVErrorInvalidVideoComposition` and no indication of which span is wrong.
 *
 * @property rotationDegrees What the container asked a player to rotate by. Baked into the pixels
 *   here, since the flag that would apply it is ignored once a video composition is set.
 * @property attributes The frame this clip's effects were resolved against, which is the clip's own
 *   size, never the output's.
 * @property still The image every frame of this span draws, or null when the clip is video and what
 *   AVFoundation decoded is already the picture.
 * @property heldFrom The time every frame from here to the span's end is drawn at, or null when each
 * frame is drawn at its own. Set on the primary's last span when an audio track outlasts it, to the
 * last slot the primary covers, so the held tail repeats that slot's frame, effects included.
 * @property placement Where the clip's pixels land in the output frame, under its own fit.
 * @property canvasEffectIndices The index in [effects] after which each of the placement's canvases is built, in the
 * same order.
 * @property drawnRect Where the frame [effects] leave is drawn in the output frame, as a fraction of it.
 */
internal class ClipSpan(
  val start: Duration,
  val end: Duration,
  val rotationDegrees: Int,
  val attributes: Attributes,
  val effects: List<ResolvedEffect>,
  val still: ImageSource? = null,
  val heldFrom: Duration? = null,
  val placement: ClipPlacement,
  val canvasEffectIndices: List<Int>,
  val drawnRect: NormalizedRect,
) {
  fun covers(time: Duration): Boolean = time in start..<end
}

/**
 * Rebuilds these spans against [resolved]'s clips, over the slots they already hold.
 *
 * The slot a span covers and the rotation baked into it both come from where the clip was laid onto
 * the `AVMutableComposition`, and a parameters-only edit moves neither, so they are carried across
 * from these spans and nothing is laid down again. A clip's placement is taken from [resolved], since a crop or a
 * scale changing its fit is a parameters-only edit too.
 *
 * The plan holds one clip per pass and `layOnto` lays one placement per clip, leaving out a clip
 * that covers no output slot when it lays on the grid. A span and the clip behind it line up by
 * index once those are left out here as well.
 */
internal fun List<ClipSpan>.respannedOnto(resolved: ResolvedComposition): List<ClipSpan> {
  val laid = resolved.videoTrack?.clips.orEmpty()
  if (laid.isEmpty()) return emptyList()
  val clips =
    if (resolved.laysVideoOnGrid) {
      val frameRate = resolved.output.frameRate
      laid.filterNot { outputSlotsOf(it.span, frameRate).isEmpty() }
    } else {
      laid
    }
  if (clips.isEmpty()) return emptyList()
  require(size == clips.size) {
    "This composition holds $size spans and the swap lays ${clips.size} clips, so a span has no " +
      "clip of its own to be rebuilt from."
  }

  return mapIndexed { index, span ->
    clips[index].spanning(
      start = span.start,
      end = span.end,
      rotationDegrees = span.rotationDegrees,
      outputSize = resolved.output.size,
      layoutSize = resolved.layoutSize,
      hdrTransfer = resolved.hdrTransfer,
      heldFrom = span.heldFrom,
    )
  }
}

/**
 * The span this clip draws over [start] until [end].
 *
 * The one place a span's attributes are derived, so a clip laid down by `layOnto` and the same clip
 * swapped in by [respannedOnto] are measured against the same frame.
 */
internal fun ResolvedClip.spanning(
  start: Duration,
  end: Duration,
  rotationDegrees: Int,
  outputSize: Size,
  layoutSize: Size,
  hdrTransfer: HdrTransfer?,
  heldFrom: Duration?,
): ClipSpan {
  val video = info.video
  return ClipSpan(
    start = start,
    end = end,
    rotationDegrees = rotationDegrees,
    attributes =
      Attributes(
        inputSize = video?.displaySize ?: outputSize,
        outputSize = outputSize,
        layoutSize = video?.displaySize ?: layoutSize,
        colorSpace = if (hdrTransfer != null) ColorSpace.Bt2020 else ColorSpace.Bt709,
        hdrTransfer = hdrTransfer,
        frameRate = video?.frameRate,
        // The planner's slot, not this lay-down's, so a clip effect is measured against the same
        // run media3 measures against.
        span = span,
      ),
    effects = effects,
    still = (source as? MediaSource.Image)?.image,
    heldFrom = heldFrom,
    placement = placement,
    canvasEffectIndices = canvasEffectIndices,
    drawnRect = drawnRect,
  )
}

/**
 * Whether the video track lands every clip on the output frame grid.
 *
 * A copy writes the source's own samples rather than rendering frames, so it has no output grid to
 * land on and keeps the exact spans the plan resolved.
 */
internal val ResolvedComposition.laysVideoOnGrid: Boolean
  get() = path != ExportPath.Copy

/**
 * Whether the video track laid for this plan holds the frames [other] would have laid, so a swap between the two shows
 * what a fresh graph would.
 *
 * Two plans on the same side of [laysVideoOnGrid] always do. Across it, the one clip a copy holds opens at zero and
 * reads the source on the same clock either way, so only the end can differ. A lay that stops short of the end of its
 * last slot holds that slot's frame, the frame a grid lay holds over the rest of the slot. One that runs past it, or
 * that covers no slot at all, can show a frame the grid leaves out.
 */
internal fun ResolvedComposition.laysSameFramesAs(other: ResolvedComposition): Boolean {
  if (laysVideoOnGrid == other.laysVideoOnGrid) return true
  val copied = if (laysVideoOnGrid) other else this
  val frameRate = copied.output.frameRate
  return copied.videoTrack?.clips.orEmpty().all { clip ->
    val slots = outputSlotsOf(clip.span, frameRate)
    !slots.isEmpty() && clip.span.start + clip.duration <= outputSlotTime(slots.last + 1, frameRate)
  }
}

/**
 * The track whose frames reach the video composition, or null when the output carries no video.
 */
internal val ResolvedComposition.videoTrack: ResolvedTrack?
  get() =
    if (audio == AudioSpec.AudioOnly) {
      null
    } else {
      tracks.firstOrNull { it.content != TrackContent.Audio }
    }

/**
 * How long each clip the video composition draws from runs for.
 *
 * The shape the spans are laid out against, so two compositions agreeing on it tile the timeline
 * identically and the spans of one can be reused for the other.
 */
internal val ResolvedComposition.videoClipDurations: List<Duration>
  get() = videoTrack?.clips?.map { it.duration }.orEmpty()

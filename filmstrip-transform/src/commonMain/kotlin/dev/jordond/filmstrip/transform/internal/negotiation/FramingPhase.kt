package dev.jordond.filmstrip.transform.internal.negotiation

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.OutputFrame
import dev.jordond.filmstrip.edit.OutputFrame.Aspect
import dev.jordond.filmstrip.edit.OutputFrame.Fixed
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.geometry.effectiveHeight
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.heldFor
import dev.jordond.filmstrip.transform.internal.geometry.MeasuredFrame
import dev.jordond.filmstrip.transform.internal.geometry.frameAfter
import dev.jordond.filmstrip.transform.internal.geometry.measuredThrough
import kotlin.math.roundToInt

/**
 * The frame the output is measured from and the size the composition asks for.
 *
 * A still is held for a span rather than decoded from one, and its bounds are a sensor's rather
 * than an encoder's, so it is never what the output frame and the output cadence are measured
 * from while a clip that decodes video is on the track. Only a leading still looks past the first
 * clip, and a track that carries no video at all still falls back to it.
 */
internal fun framingOf(
  edit: Composition,
  ask: Ask,
): Phase<Framing> {
  val primary = edit.tracks.first()
  val firstClip = primary.clips.first()
  val frameClip = frameClipOf(primary, ask.infos)
  val firstVideo =
    ask.infos.getValue(frameClip.source).video ?: return Phase.Refused(invalid(NO_PRIMARY_VIDEO))
  val inputFrame = checkNotNull(frameOf(edit, ask.infos))

  val compositionGeometry = edit.effects.filter { it.stage == EffectStage.Geometry }
  val natural = measuredThrough(inputFrame, listOf(compositionGeometry))
  val framedByStill = frameClip.source.heldFor != null
  val heightPx = effectiveHeight(ask.spec.heightPx, natural.size.height, framedByStill || edit.frame is Fixed)

  return Phase.Next(
    Framing(
      firstClip = firstClip,
      firstVideo = firstVideo,
      framedByStill = framedByStill,
      compositionGeometry = compositionGeometry,
      compositionInputSize = inputFrame.size,
      natural = natural,
      heightPx = heightPx,
      requestedSize = atHeight(natural, heightPx),
    ),
  )
}

/**
 * The frame [edit]'s clips land in, before its own geometry effects run, or null when its [OutputFrame] measures a
 * clip and the clip it measures carries no video or is a solid, which has no size to measure.
 *
 * [OutputFrame.Auto] is the frame clip's video after that clip's and its track's effects, since clip and track
 * geometry always finishes before the composition's starts. [OutputFrame.Aspect] is that frame after a
 * [Fit.Contain] crop to its aspect, and [OutputFrame.Fixed] is its size with no clip measured.
 */
internal fun frameOf(
  edit: Composition,
  infos: Map<MediaSource, MediaInfo>,
): MeasuredFrame? =
  when (val frame = edit.frame) {
    is Fixed -> MeasuredFrame(frame.size)
    is Aspect -> autoFrameOf(edit, infos)?.let { frameAfter(Crop(frame.aspect, Fit.Contain), it) }
    OutputFrame.Auto -> autoFrameOf(edit, infos)
  }

private fun autoFrameOf(
  edit: Composition,
  infos: Map<MediaSource, MediaInfo>,
): MeasuredFrame? {
  val primary = edit.tracks.first()
  val frameClip = frameClipOf(primary, infos).takeUnless { it.source is MediaSource.Solid } ?: return null
  val video = infos.getValue(frameClip.source).video ?: return null
  return measuredThrough(MeasuredFrame(video.displaySize), listOf(frameClip.effects + primary.effects))
}

/**
 * The clip the frame is measured from: the primary track's first clip, or past a leading still or solid to the first
 * clip that decodes video, falling back to the first still and then to the first clip when none does.
 */
private fun frameClipOf(
  primary: Track,
  infos: Map<MediaSource, MediaInfo>,
): Clip {
  val firstClip = primary.clips.first()
  return firstClip.takeUnless { it.source.heldFor != null }
    ?: primary.clips.firstOrNull { carriesVideo(it, infos) }
    ?: primary.clips.firstOrNull { it.source !is MediaSource.Solid }
    ?: firstClip
}

// The height moves the frame along its own aspect, the exact one a crop asked for, so the width does not follow the
// pixel rounding the first clip's size left on the frame.
private fun atHeight(
  frame: MeasuredFrame,
  heightPx: Int?,
): Size =
  if (heightPx != null && frame.size.height > 0) {
    Size((heightPx * frame.aspect).roundToInt().coerceAtLeast(1), heightPx)
  } else {
    frame.size
  }

/**
 * Whether this clip decodes video of its own, rather than holding a still for a span.
 */
private fun carriesVideo(
  clip: Clip,
  infos: Map<MediaSource, MediaInfo>,
): Boolean = clip.source.heldFor == null && infos.getValue(clip.source).video != null

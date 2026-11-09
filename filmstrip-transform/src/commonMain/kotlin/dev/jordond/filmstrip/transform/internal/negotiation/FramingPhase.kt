package dev.jordond.filmstrip.transform.internal.negotiation

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.geometry.effectiveHeight
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.geometry.frameThrough
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
  val frameClip =
    firstClip.takeUnless { it.source is MediaSource.Image }
      ?: primary.clips.firstOrNull { carriesVideo(it, ask.infos) }
      ?: firstClip
  val firstVideo =
    ask.infos.getValue(frameClip.source).video ?: return Phase.Refused(invalid(NO_PRIMARY_VIDEO))

  // Clip and track geometry always finishes before the composition's own runs, so the frame the
  // composition's geometry reads is chained from these two scopes alone.
  val clipStage = frameClip.effects + primary.effects
  val compositionGeometry = edit.effects.filter { it.stage == EffectStage.Geometry }
  val compositionInputSize = frameThrough(firstVideo.displaySize, listOf(clipStage))
  val naturalSize = frameThrough(compositionInputSize, listOf(compositionGeometry))
  val framedByStill = frameClip.source is MediaSource.Image
  val heightPx = effectiveHeight(ask.spec.heightPx, naturalSize.height, framedByStill)

  return Phase.Next(
    Framing(
      firstClip = firstClip,
      firstVideo = firstVideo,
      framedByStill = framedByStill,
      compositionGeometry = compositionGeometry,
      compositionInputSize = compositionInputSize,
      naturalSize = naturalSize,
      heightPx = heightPx,
      requestedSize = atHeight(naturalSize, heightPx),
    ),
  )
}

private fun atHeight(
  size: Size,
  heightPx: Int?,
): Size =
  if (heightPx != null && size.height > 0) {
    Size((heightPx * size.aspect).roundToInt().coerceAtLeast(1), heightPx)
  } else {
    size
  }

/**
 * Whether this clip decodes video of its own, rather than holding a still for a span.
 */
private fun carriesVideo(
  clip: Clip,
  infos: Map<MediaSource, MediaInfo>,
): Boolean = clip.source !is MediaSource.Image && infos.getValue(clip.source).video != null

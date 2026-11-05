package dev.jordond.filmstrip.media3.internal

import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.Effects
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.media3.internal.bounds.Media3FrameBounds
import dev.jordond.filmstrip.media3.internal.failure.Media3LoweringFailure
import dev.jordond.filmstrip.media3.internal.fill.FillFlatten
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
import dev.jordond.filmstrip.transform.internal.resolve.effectsOverFill
import dev.jordond.filmstrip.transform.internal.resolve.effectsUnderFill
import dev.jordond.filmstrip.transform.internal.resolve.showsFill

/**
 * Builds the media3 composition this plan runs as.
 *
 * The size stage is pinned on each clip: its own chain, composition-level geometry included, ends on a pass that places
 * it in the output frame at its own placement, which is what lets clips of differing sizes concatenate. A normalized
 * measurement in a composition-level effect is then a fraction of the frame that will really be written.
 *
 * A track's video lands on the output frame grid and its audio lands exactly where the plan put it, so a track carrying
 * both lowers to a video sequence followed by an audio one. A copy lays each track as one sequence at the exact times
 * the plan resolved, since it writes the source's own samples and has no grid to land them on.
 *
 * Every clip's chain is lowered before the composition's, in track and clip order, which is the order [wrapper] sees
 * them in. A clip that covers no slot of the grid lays no video, so its chain is never lowered.
 *
 * @param bounds The samples each clip decodes, read off its container by `readFrameBounds`.
 * @throws Media3LoweringFailure when a source cannot be lowered.
 */
@InternalFilmstripApi
public fun ResolvedComposition.toMedia3(bounds: Media3FrameBounds): Composition = toMedia3(UnwrappedEffects, bounds)

internal fun ResolvedComposition.toMedia3(
  wrapper: EffectWrapper,
  bounds: Media3FrameBounds,
): Composition {
  val keepAudio = output.audioCodec != AudioCodec.None
  val keepVideo = audio != AudioSpec.AudioOnly
  val lowering = Lowering(this, wrapper, bounds)
  val sequences =
    tracks
      .filter { it.clips.isNotEmpty() }
      .flatMap { track ->
        // The plan already holds every pass a looping track lays, so no sequence is ever asked to
        // repeat itself. media3 repeats by item index and a leading gap is one of those items, which
        // would open a pass every start plus length where the other three open one every length.
        if (path == ExportPath.Copy) {
          listOfNotNull(lowering.exactSequence(track, track.trackTypes(keepAudio, keepVideo)))
        } else {
          listOfNotNull(
            if (keepVideo && track.content != TrackContent.Audio) lowering.gridSequence(track) else null,
            if (keepAudio && track.content != TrackContent.Video) {
              lowering.exactSequence(track, setOf(C.TRACK_TYPE_AUDIO))
            } else {
              null
            },
          )
        }
      }

  if (sequences.isEmpty()) {
    throw Media3LoweringFailure("Nothing in this composition contributes a track to encode.")
  }

  return Composition
    .Builder(sequences)
    .setEffects(Effects(emptyList(), if (keepVideo) compositionVideoEffects(wrapper) else emptyList()))
    .setHdrMode(
      when (hdr) {
        ResolvedHdr.Keep -> Composition.HDR_MODE_KEEP_HDR
        // Google's own recommendation over the MediaCodec path.
        ResolvedHdr.ToneMap -> Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL
      },
    ).build()
}

/**
 * The output video MIME type, which is always one media3 will encode.
 */
internal fun VideoCodec.toMimeType(): String =
  when (this) {
    VideoCodec.H264 -> MimeTypes.VIDEO_H264
    VideoCodec.Hevc -> MimeTypes.VIDEO_H265
    // The planner resolves Auto against the device's real encoder list before a plan exists, and
    // media3's ladder never offers Vp9, Vp8 or Av1, so none of them should reach here. They error,
    // so a ladder change that forgets this function fails loudly.
    VideoCodec.Auto, VideoCodec.Vp9, VideoCodec.Vp8, VideoCodec.Av1 -> error("media3 has no encoder for $this.")
  }

/**
 * The output audio MIME type, or null when the output carries no audio track.
 */
internal fun AudioCodec.toMimeType(): String? =
  when (this) {
    AudioCodec.None -> null
    // Android's only muxer-supported audio encoder in the AAC family, and the only one the planner
    // ever resolves to on this platform.
    AudioCodec.Aac -> MimeTypes.AUDIO_AAC
    // These error, so a resolver change that starts picking one of them fails loudly.
    AudioCodec.Auto, AudioCodec.Alac, AudioCodec.Opus -> error("media3 has no encoder for $this.")
    AudioCodec.Mp3, AudioCodec.Flac, AudioCodec.Vorbis -> error("media3 has no encoder for $this.")
  }

/**
 * Names a MIME type media3 reported back, for an adjustment a caller reads.
 *
 * Falls through to the MIME type itself rather than guessing, because media3 negotiates against the
 * muxer's whole list and can land on a codec filmstrip has no name for.
 */
internal fun videoCodecName(mimeType: String): String =
  when (mimeType) {
    MimeTypes.VIDEO_H265 -> VideoCodec.Hevc.name
    MimeTypes.VIDEO_H264 -> VideoCodec.H264.name
    else -> mimeType
  }

/**
 * Names an audio MIME type media3 reported back. See [videoCodecName].
 */
internal fun audioCodecName(mimeType: String): String =
  when (mimeType) {
    MimeTypes.AUDIO_AAC -> AudioCodec.Aac.name
    else -> mimeType
  }

/**
 * The composition-level video effect chain, with a flatten pass between the effects under the fill
 * and the ones over it when a letterbox bar or a timeline gap can reach the output frame.
 *
 * [FillFlatten] runs after [effectsUnderFill] and before [effectsOverFill], so a grade never reaches
 * a bar or a gap it was not given a color for, and an overlay draws over the fill wherever it was
 * placed. That ordering is a rule every backend honours, not a media3 detail.
 */
internal fun ResolvedComposition.compositionVideoEffects(wrapper: EffectWrapper = UnwrappedEffects): List<Effect> {
  val flatten = if (showsFill) listOf(FillFlatten(fill.flattenColor(), hdrTransfer)) else emptyList()

  return (effectsUnderFill.toMedia3Effects() + flatten + effectsOverFill.toMedia3Effects()).map(wrapper::wrap)
}

// Only Fill.Solid names a color of its own. A gap has no frame to blur, so Fill.Blurred flattens to black.
private fun Fill.flattenColor(): Int =
  when (this) {
    is Fill.Solid -> color
    is Fill.Blurred -> BLACK
  }

private fun ResolvedTrack.trackTypes(
  keepAudio: Boolean,
  keepVideo: Boolean,
): Set<Int> =
  buildSet {
    if (keepVideo && content != TrackContent.Audio) add(C.TRACK_TYPE_VIDEO)
    if (keepAudio && content != TrackContent.Video) add(C.TRACK_TYPE_AUDIO)
  }

private const val BLACK = 0xFF000000.toInt()

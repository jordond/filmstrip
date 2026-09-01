package dev.jordond.filmstrip.media3.internal

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.ToInt16PcmAudioProcessor
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import dev.jordond.filmstrip.FilmstripContext
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.effects.MAX_OVERLAYS_PER_EFFECT
import dev.jordond.filmstrip.export.AudioCodec
import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.FormatHint
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.describe
import dev.jordond.filmstrip.transform.internal.ResolvedClip
import dev.jordond.filmstrip.transform.internal.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.ResolvedEffect
import dev.jordond.filmstrip.transform.internal.ResolvedHdr
import dev.jordond.filmstrip.transform.internal.ResolvedTrack
import dev.jordond.filmstrip.transform.internal.showsFill
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.time.Duration

/**
 * Why a resolved composition could not be built into a media3 one.
 *
 * Only reachable when a resolver returned something that is not a media3 `Effect`, or when a source
 * survived planning that media3 has no way to open.
 */
@InternalFilmstripApi
public class Media3LoweringFailure(
  public val reason: String,
) : Exception(reason)

/**
 * Sees every lowered video effect as it is produced, in chain order, and says what goes into the
 * chain in its place.
 *
 * A preview installs one to keep hold of the positions a parameter change can reach. An export
 * takes the default and lowers exactly what it always did.
 */
internal fun interface EffectWrapper {
  /**
   * Returns what belongs in the chain where [effect] was lowered.
   */
  fun wrap(effect: Effect): Effect
}

/**
 * Lowers each effect and passes it straight through.
 */
internal val PassThroughEffects: EffectWrapper = EffectWrapper { it }

/**
 * Builds the media3 composition this plan runs as.
 *
 * The size stage is pinned here rather than left to the effects: one [Presentation] on the
 * composition puts every clip on the output frame, which is what lets clips of differing sizes
 * concatenate. It sits after composition-level geometry and before everything else, so a
 * normalized measurement in a later effect is a fraction of the frame that will really be written.
 *
 * Every clip's chain is lowered before the composition's, in track and clip order, which is the
 * order [wrapper] sees them in.
 *
 * @throws Media3LoweringFailure when an effect or a source cannot be lowered.
 */
@InternalFilmstripApi
public fun ResolvedComposition.toMedia3(): Composition = toMedia3(PassThroughEffects)

internal fun ResolvedComposition.toMedia3(wrapper: EffectWrapper): Composition {
  val keepAudio = output.audioCodec != AudioCodec.None
  val keepVideo = audio != AudioSpec.AudioOnly
  val sequences =
    tracks.mapNotNull { track ->
      val trackTypes = track.trackTypes(keepAudio, keepVideo)
      if (trackTypes.isEmpty() || track.clips.isEmpty()) return@mapNotNull null

      val builder = EditedMediaItemSequence.Builder(trackTypes)
      if (track.start > Duration.ZERO) builder.addGap(track.start.inWholeMicroseconds)

      // A lone clip at full volume is already the format the sequence outputs, so it doesn't need mixing.
      val mixesAudio = track.clips.size > 1
      track.clips.forEach { clip ->
        builder.addItem(
          clip.toItem(
            content = track.content,
            keepAudio = keepAudio,
            keepVideo = keepVideo,
            frameRate = output.frameRate,
            startsAtKeyFrame = clip.startsAtKeyFrame,
            mixesAudio = mixesAudio,
            fit = fit,
            fill = fill,
            outputSize = output.size,
            wrapper = wrapper,
          ),
        )
      }

      builder.setIsLooping(track.looping).build()
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
    // media3's ladder never offers Vp9, Vp8 or Av1, so none of them should reach here. Erroring
    // rather than falling through to H264 is what makes a ladder change that forgets this function
    // fail loudly.
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
    // Erroring rather than falling through to AAC is what makes a resolver change that starts
    // picking one of these fail loudly instead of writing the wrong codec.
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
 * The composition-level video effect chain, with a flatten pass appended when a letterbox bar or a
 * timeline gap can reach the output frame.
 *
 * [FillFlatten] runs last, after every composition effect, so a grade never reaches a bar or a gap
 * it was not given a colour for. That ordering is a rule every backend honours, not a media3 detail.
 *
 * `internal` rather than `private` so a test can inspect the chain without building a full
 * [Composition].
 */
internal fun ResolvedComposition.compositionVideoEffects(wrapper: EffectWrapper = PassThroughEffects): List<Effect> {
  val effects =
    compositionGeometry.toMedia3Effects() +
      Presentation.createForWidthAndHeight(output.size.width, output.size.height, fit.toLayout()) +
      compositionEffects.toMedia3Effects()

  val whole = if (showsFill) effects + FillFlatten(fill.flattenColor(), hdrTransfer) else effects
  return whole.map(wrapper::wrap)
}

// Only Fill.Solid names a colour of its own. A gap has no frame to blur, so Fill.Blurred, and
// anything this backend does not recognise yet, flattens to black.
private fun Fill.flattenColor(): Int =
  when (this) {
    is Fill.Solid -> color
    else -> BLACK
  }

private fun ResolvedClip.toItem(
  content: TrackContent,
  keepAudio: Boolean,
  keepVideo: Boolean,
  frameRate: Int?,
  startsAtKeyFrame: Boolean,
  mixesAudio: Boolean,
  fit: Fit,
  fill: Fill,
  outputSize: Size,
  wrapper: EffectWrapper,
): EditedMediaItem {
  val removeAudio = !keepAudio || content == TrackContent.Video
  val removeVideo = !keepVideo || content == TrackContent.Audio

  val still = source as? MediaSource.Image
  if (still != null) {
    return toImageItem(still, frameRate, fit, fill, outputSize, wrapper, removeAudio, removeVideo)
  }

  val mediaItem =
    MediaItem
      .Builder()
      .setUri(source.toAndroidUri())
      .apply {
        val trims = start > Duration.ZERO || end < info.duration
        if (trims) {
          setClippingConfiguration(
            MediaItem.ClippingConfiguration
              .Builder()
              .setStartPositionMs(start.inWholeMilliseconds)
              .setEndPositionMs(end.inWholeMilliseconds)
              .setStartsAtKeyFrame(startsAtKeyFrame)
              .build(),
          )
        }
      }.build()

  return EditedMediaItem
    .Builder(mediaItem)
    .setRemoveAudio(removeAudio)
    .setRemoveVideo(removeVideo)
    .apply {
      // The duration media3 wants is the source's, before any clipping.
      if (info.duration > Duration.ZERO) {
        setDurationUs(info.duration.inWholeMicroseconds)
      }

      // A maximum rather than a target: a higher-rate source has frames dropped, a lower-rate one is left alone.
      if (frameRate != null && frameRate > 0) {
        setFrameRate(frameRate)
      }
    }.setEffects(
      Effects(
        if (removeAudio) emptyList() else audioProcessors(gain, mixesAudio),
        if (removeVideo) emptyList() else clipVideoEffects(fit, fill, outputSize, wrapper),
      ),
    ).build()
}

/**
 * The item a still lowers to.
 *
 * media3 chooses its image loader by MIME type, and the loader wants both a duration and a rate to
 * hold the picture at, since a still carries neither. The type is the one the probe read out of the
 * image's own header rather than a guess from the URI, because neither a cached copy of a byte
 * buffer nor a `content://` reference is guaranteed to carry a file extension.
 *
 * The span comes from the resolved clip, whose trim the plan has already collapsed into its length.
 * An image item has no samples to clip, so there is no clipping configuration here for one to reach.
 *
 * The removal flags are the ones every other clip in the sequence is built from, read the same way,
 * so a photo and the sequence's own track types cannot disagree about whether it contributes video.
 */
private fun ResolvedClip.toImageItem(
  source: MediaSource.Image,
  frameRate: Int?,
  fit: Fit,
  fill: Fill,
  outputSize: Size,
  wrapper: EffectWrapper,
  removeAudio: Boolean,
  removeVideo: Boolean,
): EditedMediaItem {
  val rate =
    frameRate?.takeIf { it > 0 }
      ?: throw Media3LoweringFailure(
        "${source.image.describe()} is a still, which has no cadence of its own, and the plan " +
          "settled on no frame rate to hold it at.",
      )

  val mediaItem =
    MediaItem
      .Builder()
      .setUri(source.image.toAndroidUri(stillFormat))
      .apply { stillFormat?.let { setMimeType("$IMAGE_MIME_PREFIX$it") } }
      .setImageDurationMs(duration.heldMilliseconds())
      .build()

  // A still carries no audio of its own, so removing it takes nothing away, and media3 ignores both
  // flags on an image item. They are set anyway so an audio-only export drops a photo's video
  // through the same reading that drops every other clip's.
  return EditedMediaItem
    .Builder(mediaItem)
    .setRemoveAudio(removeAudio)
    .setRemoveVideo(removeVideo)
    .setDurationUs(duration.inWholeMicroseconds)
    .setFrameRate(rate)
    .setEffects(
      Effects(emptyList(), if (removeVideo) emptyList() else clipVideoEffects(fit, fill, outputSize, wrapper)),
    ).build()
}

/**
 * This span in the whole milliseconds media3's own image duration is counted in.
 *
 * media3 takes that field in milliseconds and rejects a zero, so a span that is not a whole number
 * of them rounds to the nearest one and never falls below one. It is what routes the item to the
 * image loader and what a preview holds the picture for. The exact span rides on the edited item's
 * duration, which is what an export lays the picture out by.
 */
private fun Duration.heldMilliseconds(): Long =
  ((inWholeMicroseconds + MICROS_PER_MILLISECOND / 2) / MICROS_PER_MILLISECOND).coerceAtLeast(1)

// The still format the probe read out of the image's header, or null when it named none.
private val ResolvedClip.stillFormat: String?
  get() =
    info.video
      ?.codec
      ?.name
      ?.lowercase()
      ?.takeIf { it.isNotBlank() }

/**
 * A clip's own video effect chain, with a cover-blur pass appended when its frame would otherwise
 * leave a letterbox bar for [Fill.Blurred] to fill.
 *
 * `internal` rather than `private` so a test can inspect the chain without building a full
 * [EditedMediaItem].
 */
internal fun ResolvedClip.clipVideoEffects(
  fit: Fit,
  fill: Fill,
  outputSize: Size,
  wrapper: EffectWrapper = PassThroughEffects,
): List<Effect> {
  val lowered = effects.toMedia3Effects()
  val whole =
    when {
      fit != Fit.Contain || fill !is Fill.Blurred -> {
        lowered
      }
      else -> {
        val displaySize = info.video?.displaySize
        when {
          displaySize == null -> lowered
          abs(displaySize.aspect - outputSize.aspect) < ASPECT_EPSILON -> lowered
          else -> lowered + FillCoverBlur(fill, outputSize)
        }
      }
    }

  return whole.map(wrapper::wrap)
}

/**
 * The processors an audio-carrying clip runs through.
 *
 * Every item in a sequence has to reach the same output format, and a gain is applied by scaling
 * the mixing matrix that gets it there. Pinning 16-bit PCM and a channel count is what makes a
 * concatenation of a mono clip and a stereo clip mix at all, so it is done whenever a sequence has
 * more than one clip rather than only when a gain is asked for.
 */
private fun audioProcessors(
  gain: Float,
  mixes: Boolean,
): List<AudioProcessor> {
  if (!mixes && gain == 1f) return emptyList()

  val mixer = ChannelMixingAudioProcessor()
  INPUT_CHANNEL_COUNTS.forEach { inputs ->
    mixer.putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(inputs, CHANNEL_COUNT).scaleBy(gain))
  }
  return listOf(ToInt16PcmAudioProcessor(), mixer)
}

private fun ResolvedTrack.trackTypes(
  keepAudio: Boolean,
  keepVideo: Boolean,
): Set<Int> =
  buildSet {
    if (keepVideo && content != TrackContent.Audio) add(C.TRACK_TYPE_VIDEO)
    if (keepAudio && content != TrackContent.Video) add(C.TRACK_TYPE_AUDIO)
  }

private fun Fit.toLayout(): Int =
  when (this) {
    Fit.Contain -> Presentation.LAYOUT_SCALE_TO_FIT
    Fit.Crop -> Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
    Fit.Stretch -> Presentation.LAYOUT_STRETCH_TO_FIT
  }

/**
 * Lowers a resolved chain, gathering the overlays in it into as few passes as they fit into.
 *
 * A resolver hands back a [TextureOverlay] rather than an [Effect] for anything it composites,
 * because media3 blends every overlay in one [OverlayEffect] with a single draw call, whereas a
 * chain of N `OverlayEffect`s is N render passes over the whole frame. Canonical order already
 * leaves the overlays adjacent, so gathering each run where it appears keeps the declared order and
 * costs one pass however many of them there are, up to the sampler budget one GL program has.
 */
private fun List<ResolvedEffect>.toMedia3Effects(): List<Effect> {
  val effects = mutableListOf<Effect>()
  val overlays = mutableListOf<TextureOverlay>()

  fun flush() {
    if (overlays.isEmpty()) return
    effects += OverlayEffect(overlays.toList())
    overlays.clear()
  }

  forEach { resolved ->
    when (val handle = resolved.effect.handle) {
      is TextureOverlay -> {
        overlays += handle
        if (overlays.size == MAX_OVERLAYS_PER_EFFECT) flush()
      }
      is Effect -> {
        flush()
        effects += handle
      }
      else -> {
        throw Media3LoweringFailure(
          "The resolver that claimed ${resolved.specId} returned a ${handle::class.simpleName}, and " +
            "the Android pipeline renders an androidx.media3.common.Effect or an " +
            "androidx.media3.effect.TextureOverlay.",
        )
      }
    }
  }

  flush()
  return effects
}

private fun MediaSource.toAndroidUri(): Uri =
  when (this) {
    is MediaSource.Path -> {
      Uri.fromFile(File(path))
    }
    is MediaSource.Uri -> {
      Uri.parse(uri)
    }
    is MediaSource.Bytes -> {
      cached(bytes, hint.extension())
    }
    is MediaSource.Image -> {
      // Lowered by toImageItem, which reaches the still through ImageSource.toAndroidUri instead.
      throw Media3LoweringFailure("${image.describe()} is a still, which is not lowered as a plain URI.")
    }
  }

/**
 * The URI media3 opens a still through, written to the cache first when the still is bytes.
 *
 * @param format The still format the probe named, used as the extension a cached copy is written
 *   under.
 */
private fun ImageSource.toAndroidUri(format: String?): Uri =
  when (this) {
    is ImageSource.Path -> {
      Uri.fromFile(File(path))
    }
    is ImageSource.Uri -> {
      Uri.parse(uri)
    }
    is ImageSource.Bytes -> {
      val extension =
        format
          ?: throw Media3LoweringFailure(
            "A still handed over as bytes is written to a temporary file before it is encoded, " +
              "and nothing that read it named the format to write it under.",
          )
      cached(bytes, extension)
    }
  }

/**
 * The URI of [bytes] written into the cache under [extension].
 */
private fun cached(
  bytes: ByteArray,
  extension: String,
): Uri {
  val context =
    FilmstripContext.get()
      ?: throw Media3LoweringFailure(
        "An in-memory source is written to a temporary file before it is encoded, and " +
          FilmstripContext.MISSING_CONTEXT,
      )

  return try {
    Uri.fromFile(Media3Scratch.fileFor(context, bytes, extension))
  } catch (failure: IOException) {
    throw Media3LoweringFailure("An in-memory source could not be written to a temporary file: ${failure.message}")
  } catch (denied: SecurityException) {
    throw Media3LoweringFailure("An in-memory source could not be written to a temporary file: ${denied.message}")
  }
}

/**
 * The extension a buffer with this hint is cached under.
 *
 * media3 sniffs a container rather than trusting the name, so an unhinted buffer is written under a
 * name that claims nothing.
 */
private fun FormatHint?.extension(): String =
  when (this) {
    FormatHint.Mp4 -> "mp4"
    FormatHint.Mov -> "mov"
    FormatHint.M4a -> "m4a"
    FormatHint.ThreeGp -> "3gp"
    null -> "tmp"
  }

private const val CHANNEL_COUNT = 2
private val INPUT_CHANNEL_COUNTS = listOf(1, 2)

private const val BLACK = 0xFF000000.toInt()

// What media3 reads an item's MIME type for, which is only ever whether it starts with this.
private const val IMAGE_MIME_PREFIX = "image/"

private const val MICROS_PER_MILLISECOND = 1000L

// Aspect ratios closer than this are treated as the same shape, so a clip that already fills the
// output frame is never sent through a blur pass it would draw nothing observable with.
private const val ASPECT_EPSILON = 0.001f

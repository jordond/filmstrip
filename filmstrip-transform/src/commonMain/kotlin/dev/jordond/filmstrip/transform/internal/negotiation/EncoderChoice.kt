package dev.jordond.filmstrip.transform.internal.negotiation

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.capability.AudioEncoderCapability
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.export.Adjustment
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.AudioFormat
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.geometry.frameWithin
import dev.jordond.filmstrip.media.audioCodecOf
import dev.jordond.filmstrip.media.videoCodecOf
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The video codec to encode with, or null when the device encodes none of them.
 *
 * @param encodesHdr Whether the grade reaches the encoder. HDR needs a 10-bit profile, and not every
 *   codec this engine otherwise tries has one, so an HDR export chooses from [hdrLadder] rather than
 *   [ladder]. Leaving a codec with no such profile in it would have the plan name one that the
 *   platform then silently swaps out from under it.
 * @param ladder The order this engine tries codecs in when the request is [VideoCodec.Auto], or
 *   when the requested codec is unsupported and something has to be tried next.
 * @param hdrLadder The same, for an export that keeps HDR.
 */
internal fun resolveVideoCodec(
  spec: ExportSpec,
  device: DeviceCapabilities,
  encodesHdr: Boolean,
  ladder: List<VideoCodec>,
  hdrLadder: List<VideoCodec>,
): CodecChoice<VideoCodec>? {
  val requested = spec.videoCodec
  val tried =
    when {
      encodesHdr -> hdrLadder
      requested == VideoCodec.Auto -> ladder
      else -> listOf(requested) + ladder.filterNot { it == requested }
    }
  val supported = tried.firstOrNull { codec -> device.video.any { it.codec == codec } } ?: return null
  if (supported == requested || requested == VideoCodec.Auto) return CodecChoice(supported, null)

  val reason =
    if (encodesHdr) "Keeping the HDR grade needs a 10-bit encoder" else "This device has no encoder for $requested"
  val message = "$reason, so $supported is used instead."
  return CodecChoice(
    codec = supported,
    fallback =
      Concession(
        adjustment =
          Adjustment(
            kind = AdjustmentKind.CodecFallback,
            requested = requested.name,
            resolved = supported.name,
            message = message,
          ),
        strictRefusal = FilmstripError.NoEncoder(requested, message),
      ),
  )
}

/**
 * The audio codec to encode with, [AudioCodec.None] when the output carries no audio or the device
 * encodes none.
 *
 * @param silent Whether nothing survives to be encoded, which settles on [AudioCodec.None] whatever
 *   was asked for.
 */
internal fun resolveAudioCodec(
  spec: ExportSpec,
  device: DeviceCapabilities,
  silent: Boolean,
): CodecChoice<AudioCodec> {
  val requested = spec.audioCodec
  if (silent) return CodecChoice(AudioCodec.None, null)
  if (device.audio.any { it.codec == requested }) return CodecChoice(requested, null)
  val fallback = device.audio.firstOrNull()?.codec ?: return CodecChoice(AudioCodec.None, null)
  if (requested == AudioCodec.Auto) return CodecChoice(fallback, null)

  val message = "This device has no $requested encoder, so $fallback is used instead."
  return CodecChoice(
    codec = fallback,
    fallback =
      Concession(
        adjustment =
          Adjustment(
            kind = AdjustmentKind.CodecFallback,
            requested = requested.name,
            resolved = fallback.name,
            message = message,
          ),
        strictRefusal = FilmstripError.InvalidComposition(message),
      ),
  )
}

/**
 * The format the export writes.
 *
 * A copy writes the source's own codecs and audio format untouched, since nothing re-encodes to
 * change them. AudioSpec.AudioOnly writes a file with no video track, and OutputFormat has no way
 * to say that. The video half of the format is reported as resolved and then not written, which is
 * what the ffmpeg backend does with the same edit.
 */
internal fun outputFormatOf(
  ask: Ask,
  framing: Framing,
  grade: Grade,
  encoding: Encoding,
  audio: CodecChoice<AudioCodec>,
  tracks: List<ResolvedTrack>,
): OutputFormat {
  if (grade.path == ExportPath.Copy) {
    val sourceAudio = ask.infos.getValue(framing.firstClip.source).audio
    return OutputFormat(
      size = encoding.outputSize,
      videoCodec = checkNotNull(videoCodecOf(framing.firstVideo.codec.kind)) { COPY_UNNAMEABLE },
      audioCodec =
        sourceAudio?.let {
          checkNotNull(
            audioCodecOf(it.codec.kind),
          ) { COPY_UNNAMEABLE }
        } ?: AudioCodec.None,
      bitrate = ask.spec.bitrate,
      frameRate = encoding.target.frameRate,
      audioFormat = sourceAudio?.let { AudioFormat(sampleRate = it.sampleRate, channelCount = it.channelCount) },
    )
  }
  val audioEncoder = ask.device.audio.firstOrNull { it.codec == audio.codec }
  return OutputFormat(
    size = encoding.outputSize,
    videoCodec = encoding.video.codec,
    audioCodec = audio.codec,
    bitrate = ask.spec.bitrate,
    frameRate = encoding.target.frameRate,
    audioFormat = if (audio.codec == AudioCodec.None) null else audioFormat(tracks, audioEncoder),
  )
}

/**
 * The format every track's audio is normalized to before mixing.
 *
 * The first clip that has audio proposes the rate, because that is the stream the platform keeps,
 * and [encoder] gets the last word: a rate it does not list is snapped to the nearest one it does,
 * since an encoder handed a rate it cannot write resamples on its own and leaves the written file
 * disagreeing with the plan. An encoder that lists no rates at all is taken at its word and given
 * the source's. Channel count is fixed, since mixing tracks of differing widths needs one width to
 * mix into.
 */
internal fun audioFormat(
  tracks: List<ResolvedTrack>,
  encoder: AudioEncoderCapability?,
): AudioFormat {
  val source =
    tracks
      .flatMap { it.clips }
      .firstNotNullOfOrNull {
        it.info.audio
          ?.sampleRate
          ?.takeIf { rate -> rate > 0 }
      } ?: DEFAULT_SAMPLE_RATE
  val accepted = encoder?.sampleRates.orEmpty()
  val channels = encoder?.maxChannelCount?.takeIf { it > 0 } ?: CHANNEL_COUNT
  return AudioFormat(
    sampleRate = if (accepted.isEmpty() || source in accepted) source else accepted.minBy { abs(it - source) },
    channelCount = CHANNEL_COUNT.coerceAtMost(channels),
  )
}

/**
 * The rate the spec asks for, or the source's own, held under [maxRate] when the encoder has one.
 */
internal fun resolveFrameRate(
  spec: ExportSpec,
  sourceRate: Float?,
  maxRate: Int?,
): Int {
  val requested = spec.frameRate ?: sourceRate?.roundToInt()?.takeIf { it > 0 } ?: DEFAULT_RATE
  return if (maxRate != null && maxRate > 0) requested.coerceAtMost(maxRate) else requested
}

/**
 * What to do about high dynamic range, or null when nothing this engine can do satisfies the ask.
 *
 * An SDR source resolves to [ResolvedHdr.Keep] whatever was asked for, because there is no grade to
 * map. Saying so is what keeps a stream copy possible, since a platform told to tone-map has to
 * decode every frame to do it.
 *
 * A grade is only ever kept when every video source carries the same transfer, which [gradesAgree]
 * says. A mix has nothing that is true of the whole output, so keeping one would tag the rest of the
 * frames with a grade they do not have.
 *
 * [copyKeepsHdr] gives HDR a second way to reach [ResolvedHdr.Keep]: a copy carries whatever grade
 * the source already has without asking an encoder for anything, so a device that cannot encode HDR
 * still keeps it when the rest of the export would already have been a copy. An explicit tone map
 * still means what it says, since asking for one forces a transcode either way.
 *
 * An engine that can neither encode the grade, copy it across, nor bring it down has nowhere to put
 * an HDR source, and null is how it refuses, so the untouched grade never lands in an SDR file.
 */
internal fun resolveHdr(
  mode: HdrMode,
  sourceIsHdr: Boolean,
  gradesAgree: Boolean,
  deviceEncodesHdr: Boolean,
  canToneMap: Boolean,
  copyKeepsHdr: Boolean,
): ResolvedHdr? {
  if (!sourceIsHdr) return ResolvedHdr.Keep

  val canKeep = gradesAgree && (deviceEncodesHdr || copyKeepsHdr)
  return when (mode) {
    HdrMode.ToneMapToSdr -> {
      if (canToneMap) ResolvedHdr.ToneMap else null
    }
    HdrMode.KeepHdr -> {
      if (canKeep) ResolvedHdr.Keep else null
    }
    HdrMode.Auto -> {
      when {
        canKeep -> ResolvedHdr.Keep
        canToneMap -> ResolvedHdr.ToneMap
        else -> null
      }
    }
  }
}

/**
 * Scales the frame down to sit inside [max], then rounds each side down to a multiple the encoder
 * accepts.
 *
 * Rounding to the nearest multiple would carry a frame that already sits on the encoder's maximum
 * past that maximum.
 */
internal fun Size.fittedTo(
  max: Size?,
  alignment: Int,
): Size {
  val step = alignment.coerceAtLeast(1)
  val fitted = if (max == null) this else frameWithin(this, max)
  return Size(
    (fitted.width - fitted.width % step).coerceAtLeast(step),
    (fitted.height - fitted.height % step).coerceAtLeast(step),
  )
}

/**
 * The box both [first] and [second] fit inside, or whichever of them is the only one given.
 */
internal fun tightest(
  first: Size?,
  second: Size?,
): Size? =
  when {
    first == null -> second
    second == null -> first
    else -> Size(minOf(first.width, second.width), minOf(first.height, second.height))
  }

private const val DEFAULT_RATE = 30
private const val DEFAULT_SAMPLE_RATE = 44_100
private const val CHANNEL_COUNT = 2

private const val COPY_UNNAMEABLE = "A copy was planned over a stream whose codec VideoCodec or AudioCodec cannot name."

package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.export.AudioCodec
import dev.jordond.filmstrip.export.VideoCodec

/**
 * Reads a platform's own codec spelling into a [TrackCodec].
 *
 * MIME types, four-character codes and codecs-parameter strings all reduce to the same token before
 * they are matched, so `hvc1`, `video/hevc` and `hvc1.1.6.L93.B0` all read as [CodecKind.Hevc]. A
 * spelling filmstrip does not know reads as [CodecKind.Other].
 *
 * @param name The platform's spelling, such as `hvc1`, `video/hevc`, `hevc` or `hvc1.1.6.L93.B0`.
 */
@InternalFilmstripApi
public fun trackCodecOf(name: String): TrackCodec = TrackCodec(name = name, kind = name.toCodecKind())

/**
 * The [VideoCodec] a copy of [kind] carries into an output.
 *
 * Every video kind a copy is ever permitted to touch has one, since nothing that copies a source
 * allows a kind [VideoCodec] cannot name. Reaching the error means a copy ran on a stream nothing
 * checked first.
 */
@InternalFilmstripApi
public fun videoCodecOf(kind: CodecKind): VideoCodec =
  when (kind) {
    CodecKind.H264 -> VideoCodec.H264
    CodecKind.Hevc -> VideoCodec.Hevc
    CodecKind.Vp8 -> VideoCodec.Vp8
    CodecKind.Vp9 -> VideoCodec.Vp9
    CodecKind.Av1 -> VideoCodec.Av1
    CodecKind.Aac, CodecKind.Opus, CodecKind.Vorbis -> error("$kind names no video codec.")
    CodecKind.Flac, CodecKind.Mp3, CodecKind.Pcm, CodecKind.Other -> error("$kind names no video codec.")
  }

/**
 * The [AudioCodec] a copy of [kind] carries into an output.
 *
 * Every audio kind a copy is ever permitted to touch has one, since nothing that copies a source
 * allows a kind [AudioCodec] cannot name. Reaching the error means a copy ran on a stream nothing
 * checked first.
 */
@InternalFilmstripApi
public fun audioCodecOf(kind: CodecKind): AudioCodec =
  when (kind) {
    CodecKind.Aac -> AudioCodec.Aac
    CodecKind.Opus -> AudioCodec.Opus
    CodecKind.Vorbis -> AudioCodec.Vorbis
    CodecKind.Flac -> AudioCodec.Flac
    CodecKind.Mp3 -> AudioCodec.Mp3
    CodecKind.H264, CodecKind.Hevc, CodecKind.Vp8 -> error("$kind names no audio codec.")
    CodecKind.Vp9, CodecKind.Av1, CodecKind.Pcm, CodecKind.Other -> error("$kind names no audio codec.")
  }

/**
 * What [this] names, or [CodecKind.Other].
 *
 * An exact token match wins. Failing that, a substring sweep catches the vendor-shaped names that
 * survive tokenising with the codec buried in the middle, such as Android's `video/x-vnd.on2.vp9`.
 */
private fun String.toCodecKind(): CodecKind {
  val lowered = lowercase()
  TOKENS[lowered.token()]?.let { return it }

  return MARKERS.firstOrNull { (marker, _) -> lowered.contains(marker) }?.second ?: CodecKind.Other
}

/**
 * The part of a codec name that identifies the codec and nothing else.
 *
 * A MIME type carries a media type in front of it, a codecs parameter carries a profile and level
 * behind it, and a four-character code is padded out with spaces. All three are stripped here.
 */
private fun String.token(): String = substringAfterLast('/').substringBefore('.').trim()

private val TOKENS: Map<String, CodecKind> =
  buildMap {
    listOf("avc1", "avc3", "avc", "h264").forEach { put(it, CodecKind.H264) }
    listOf("hvc1", "hev1", "hevc", "h265").forEach { put(it, CodecKind.Hevc) }
    listOf("vp08", "vp8").forEach { put(it, CodecKind.Vp8) }
    listOf("vp09", "vp9").forEach { put(it, CodecKind.Vp9) }
    listOf("av01", "av1").forEach { put(it, CodecKind.Av1) }
    listOf("mp4a", "mp4a-latm", "aac").forEach { put(it, CodecKind.Aac) }
    put("opus", CodecKind.Opus)
    put("vorbis", CodecKind.Vorbis)
    put("flac", CodecKind.Flac)
    listOf("mp3", "mpga", "mpeg").forEach { put(it, CodecKind.Mp3) }
    listOf("lpcm", "pcm", "raw", "sowt").forEach { put(it, CodecKind.Pcm) }
  }

// Ordered, because "vp9" has to be tried before "vp8" would ever match something like "vp90".
private val MARKERS: List<Pair<String, CodecKind>> =
  listOf(
    "vp9" to CodecKind.Vp9,
    "vp8" to CodecKind.Vp8,
    "av01" to CodecKind.Av1,
    "hevc" to CodecKind.Hevc,
    "h265" to CodecKind.Hevc,
    "avc" to CodecKind.H264,
    "h264" to CodecKind.H264,
    "opus" to CodecKind.Opus,
    "vorbis" to CodecKind.Vorbis,
    "flac" to CodecKind.Flac,
  )

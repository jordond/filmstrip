@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal

import dev.jordond.filmstrip.codec.Bitrate
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.awaitCatching
import dev.jordond.filmstrip.media.AudioTrackInfo
import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.displaySizeOf
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.webcodecs.internal.interop.InputAudioTrack
import dev.jordond.filmstrip.webcodecs.internal.interop.InputVideoTrack
import dev.jordond.filmstrip.webcodecs.internal.interop.Rational
import kotlinx.coroutines.await
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.time.Duration.Companion.microseconds

/**
 * Reads a source's metadata with mediabunny, the same demuxer the export pipeline already opens
 * the source with.
 *
 * Registered the way `FfmpegProber` is, so `Filmstrip.probe()` and every backend that asks a
 * chained prober for a web source get a real audio track and, for HEVC, a real bit depth, rather
 * than the failure core's own platform prober answers with on a target that carries no demuxer.
 */
internal class BrowserProber : MediaProber {
  override suspend fun probe(source: MediaSource): ProbeResult {
    val reader = SourceReader.of(source) ?: return ProbeResult.Failure(unreadableSource(source))
    return try {
      val info = awaitCatching { readInfo(reader, fileBytes = null) }.getOrNull()
      if (info == null) ProbeResult.Failure(unreadableSource(source)) else ProbeResult.Success(info)
    } finally {
      reader.close()
    }
  }

  /**
   * Reads what [file] carries, which is the same answer probing that file later gives.
   *
   * @return What the file carries, or null when it reads as neither video nor audio.
   */
  suspend fun probe(file: EncodedFile): MediaInfo? {
    val reader = file.reader()
    return try {
      awaitCatching { readInfo(reader, file.byteLength.toLong()) }.getOrNull()
    } finally {
      reader.close()
    }
  }

  // The bitrate is the whole file's, so it describes a track only while that track is the only one. A file carrying
  // both gives it to the video, which is the larger part of it by far, and leaves the audio's null rather than counting
  // the same bytes twice. A source read through a stream has no byte count to work from.
  private suspend fun readInfo(
    reader: SourceReader,
    fileBytes: Long?,
  ): MediaInfo? {
    val durationUs = reader.durationUs()
    val bitrate =
      fileBytes?.takeIf { durationUs > 0 }?.let {
        Bitrate(
          (it * BITS_PER_BYTE * MICROS_PER_SECOND / durationUs).toLong(),
        )
      }
    val video = reader.videoTrack()?.toTrackInfo(bitrate)
    val audio = reader.audioTrack()?.toTrackInfo(if (video == null) bitrate else null)
    if (video == null && audio == null) return null

    return MediaInfo(
      duration = durationUs.microseconds,
      video = video,
      audio = audio,
      isExportable = true,
    )
  }

  private suspend fun InputVideoTrack.toTrackInfo(bitrate: Bitrate?): VideoTrackInfo? {
    val codec = getCodec().await()?.toString() ?: return null
    val trackCodec = trackCodecOf(codec)
    val coded = Size(getCodedWidth().await().toDouble().toInt(), getCodedHeight().await().toDouble().toInt())
    val rotation = getRotation().await().toDouble().toInt()
    val pixelAspect = getPixelAspectRatio().await().toFloat()
    val space = getColorSpace().await()
    val bitDepth =
      when (trackCodec.kind) {
        CodecKind.Hevc -> hevcBitDepth(getCodecParameterString().await()?.toString())
        else -> null
      }
    return VideoTrackInfo(
      codedSize = coded,
      displaySize = displaySizeOf(coded, rotation, pixelAspect),
      rotationDegrees = rotation,
      pixelAspectRatio = pixelAspect,
      frameRate = measuredFrameRate(),
      codec = trackCodec,
      bitDepth = bitDepth,
      colorSpace = space.primaries?.toString().toColorSpace(),
      hdrTransfer = space.transfer?.toString().toHdrTransfer(),
      bitrate = bitrate,
      hasAlpha = canBeTransparent().await().toBoolean(),
    )
  }

  private suspend fun InputAudioTrack.toTrackInfo(bitrate: Bitrate?): AudioTrackInfo? {
    val codec = getCodec().await()?.toString() ?: return null
    return AudioTrackInfo(
      codec = trackCodecOf(codec),
      sampleRate = getSampleRate().await().toDouble().toInt(),
      channelCount = getNumberOfChannels().await().toDouble().toInt(),
      bitrate = bitrate,
    )
  }
}

private const val BITS_PER_BYTE = 8L

/**
 * Bit depth for HEVC, read from the profile digit in the codecs parameter string: 1 and 3 are
 * eight-bit profiles, 2 is Main10. Every other codec has no depth findable this way, and answers
 * null here the same as it does on every other platform.
 */
private fun hevcBitDepth(codecParameterString: String?): Int? {
  val profile =
    codecParameterString
      ?.split('.')
      ?.getOrNull(1)
      ?.trimStart('A', 'B', 'C')
      ?.toIntOrNull() ?: return null
  return when (profile) {
    1, 3 -> 8
    2 -> 10
    else -> null
  }
}

/**
 * The fraction as a single number. A denominator of zero, which no real track reports, reads as
 * square so a bad file cannot hand back a non-finite ratio.
 */
private fun Rational.toFloat(): Float = if (den == 0.0) 1f else (num / den).toFloat()

private fun String?.toColorSpace(): ColorSpace =
  when (this) {
    "bt709" -> ColorSpace.Bt709
    "bt470bg", "smpte170m" -> ColorSpace.Bt601
    "bt2020" -> ColorSpace.Bt2020
    else -> ColorSpace.Unknown
  }

private fun String?.toHdrTransfer(): HdrTransfer? =
  when (this) {
    "pq" -> HdrTransfer.Pq
    "hlg" -> HdrTransfer.Hlg
    else -> null
  }

@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.AudioTrackInfo
import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.describe
import dev.jordond.filmstrip.media.displaySizeOf
import dev.jordond.filmstrip.media.trackCodecOf
import kotlinx.coroutines.await
import kotlin.coroutines.cancellation.CancellationException
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
@OptIn(InternalFilmstripApi::class)
internal class BrowserProber : MediaProber {
  override suspend fun probe(source: MediaSource): ProbeResult {
    val reader = SourceReader.of(source) ?: return unreadable(source)
    return try {
      readInfo(reader, source)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (unreadable: Throwable) {
      unreadable(source)
    } finally {
      reader.close()
    }
  }

  private suspend fun readInfo(
    reader: SourceReader,
    source: MediaSource,
  ): ProbeResult {
    val video = reader.videoTrack()?.toTrackInfo()
    val audio = reader.audioTrack()?.toTrackInfo()
    if (video == null && audio == null) return unreadable(source)

    return ProbeResult.Success(
      MediaInfo(
        duration = reader.durationUs().microseconds,
        video = video,
        audio = audio,
        isExportable = true,
      ),
    )
  }

  private suspend fun InputVideoTrack.toTrackInfo(): VideoTrackInfo? {
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
      frameRate = measuredFrameRate(this),
      codec = trackCodec,
      bitDepth = bitDepth,
      colorSpace = space.primaries?.toString().toColorSpace(),
      hdrTransfer = space.transfer?.toString().toHdrTransfer(),
      bitrate = null,
    )
  }

  private suspend fun InputAudioTrack.toTrackInfo(): AudioTrackInfo? {
    val codec = getCodec().await()?.toString() ?: return null
    return AudioTrackInfo(
      codec = trackCodecOf(codec),
      sampleRate = getSampleRate().await().toDouble().toInt(),
      channelCount = getNumberOfChannels().await().toDouble().toInt(),
      bitrate = null,
    )
  }

  /**
   * The track's real frame rate, measured over a bounded prefix of its packets rather than the
   * whole file.
   */
  private suspend fun measuredFrameRate(track: InputVideoTrack): Float? {
    val stats = track.computePacketStats(FRAME_RATE_SAMPLE_PACKETS).await()
    if (stats.packetCount < 2) return null
    val rate = stats.averagePacketRate
    return if (rate > 0.0 && rate.isFinite()) rate.toFloat() else null
  }

  private fun unreadable(source: MediaSource): ProbeResult.Failure =
    ProbeResult.Failure(ExportError.SourceUnreadable(source.describe(), UNREADABLE))

  private companion object {
    // Enough packets to measure a rate, few enough that measuring one does not walk a long file.
    const val FRAME_RATE_SAMPLE_PACKETS = 120

    const val UNREADABLE =
      "A browser reads URLs and in-memory bytes. A path has no meaning here, and a file has to be " +
        "loaded by the page first."
  }
}

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

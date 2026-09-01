package dev.jordond.filmstrip.internal

import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import dev.jordond.filmstrip.FilmstripContext
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.export.Bitrate
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.AudioTrackInfo
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.media.TrackCodec
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.describe
import dev.jordond.filmstrip.media.displaySizeOf
import dev.jordond.filmstrip.media.probeImage
import dev.jordond.filmstrip.media.trackCodecOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

@OptIn(InternalFilmstripApi::class)
internal actual class PlatformProber actual constructor() {
  actual suspend fun probe(source: MediaSource): ProbeResult =
    when (source) {
      // A still carries no container to open, and the retriever throws a bare RuntimeException on
      // an image file, so this is answered from the image's own header instead.
      is MediaSource.Image -> {
        probeImage(source)
      }
      is MediaSource.Path -> {
        source.readContainer { setDataSource(source.path) }
      }
      is MediaSource.Uri -> {
        // A content:// URI resolves through the app's ContentResolver.
        val android = FilmstripContext.get()
        if (android == null) {
          ProbeResult.Failure(
            ExportError.SourceUnreadable(source = source.uri, message = FilmstripContext.MISSING_CONTEXT),
          )
        } else {
          source.readContainer { setDataSource(android, Uri.parse(source.uri)) }
        }
      }
      is MediaSource.Bytes -> {
        ProbeResult.Failure(
          ExportError.SourceUnreadable(
            source = "bytes",
            message =
              "In-memory sources are written to a temporary file before probing, " +
                "which is not implemented yet.",
          ),
        )
      }
    }

  /**
   * Reads this source once [open] has pointed a retriever at it.
   *
   * The retriever is built before [open] runs, so a source that fails to open is still released.
   */
  private suspend fun MediaSource.readContainer(open: MediaMetadataRetriever.() -> Unit): ProbeResult =
    withContext(Dispatchers.IO) {
      val retriever = MediaMetadataRetriever()
      try {
        retriever.open()
        ProbeResult.Success(retriever.readInfo(tracks()))
      } catch (error: IllegalArgumentException) {
        ProbeResult.Failure(unreadable(error.message))
      } catch (error: RuntimeException) {
        // setDataSource throws a bare RuntimeException for a malformed or unsupported container.
        ProbeResult.Failure(unreadable(error.message))
      } finally {
        retriever.release()
      }
    }

  /**
   * Opens the source a second time and pulls out the first video and audio track formats.
   *
   * The retriever answers at the file level, so anything track-level has to come from here. Null
   * when it cannot be opened, not a failure. These fields refine what the retriever already read,
   * so a source the retriever could open stays a success with less detail.
   */
  private fun MediaSource.tracks(): SourceTracks? {
    val extractor = MediaExtractor()
    return try {
      when (this) {
        is MediaSource.Path -> extractor.setDataSource(path)
        is MediaSource.Uri -> extractor.setDataSource(FilmstripContext.get() ?: return null, Uri.parse(uri), null)
        // Neither names a container the extractor can open.
        is MediaSource.Bytes, is MediaSource.Image -> return null
      }
      extractor.readTracks()
    } catch (unreadable: IOException) {
      null
    } catch (unopened: RuntimeException) {
      // setDataSource can return having set no source for a content:// URI it could not open, which
      // surfaces as IllegalStateException on the first read, not as a failure to open.
      null
    } finally {
      extractor.release()
    }
  }

  private fun MediaExtractor.readTracks(): SourceTracks {
    var video: MediaFormat? = null
    var audio: MediaFormat? = null

    for (index in 0 until trackCount) {
      val format = getTrackFormat(index)
      when (format.string(MediaFormat.KEY_MIME)?.substringBefore('/')) {
        "video" -> if (video == null) video = format
        "audio" -> if (audio == null) audio = format
      }
    }

    return SourceTracks(video = video, audio = audio)
  }

  private fun MediaMetadataRetriever.readInfo(tracks: SourceTracks?): MediaInfo {
    val video = tracks?.video
    val audio = tracks?.audio
    val hasVideo = metadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
    val hasAudio = metadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
    val rotation = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: 0
    val coded =
      Size(
        width = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: 0,
        height = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: 0,
      )
    val pixelAspect = video?.pixelAspectRatio() ?: SQUARE

    return MediaInfo(
      duration = (long(MediaMetadataRetriever.METADATA_KEY_DURATION) ?: 0L).milliseconds,
      video =
        if (!hasVideo) {
          null
        } else {
          VideoTrackInfo(
            codedSize = coded,
            displaySize = displaySizeOf(coded, rotation, pixelAspect),
            rotationDegrees = rotation,
            pixelAspectRatio = pixelAspect,
            frameRate = video?.int(MediaFormat.KEY_FRAME_RATE)?.toFloat(),
            codec = trackCodecOf(video?.string(MediaFormat.KEY_MIME).orEmpty()),
            bitDepth = video?.bitDepth(),
            colorSpace = video?.colorSpace() ?: retrieverColorSpace(),
            hdrTransfer = video?.hdrTransfer() ?: retrieverHdrTransfer(),
            bitrate =
              video?.int(MediaFormat.KEY_BIT_RATE)?.toLong()?.let(::Bitrate)
                ?: long(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.let(::Bitrate),
          )
        },
      audio =
        if (!hasAudio) {
          null
        } else {
          AudioTrackInfo(
            codec = trackCodecOf(audio?.string(MediaFormat.KEY_MIME).orEmpty()),
            // METADATA_KEY_SAMPLERATE is API 31, so the track format is the only route that
            // answers across the whole supported range and does not read zero below it.
            sampleRate = audio?.int(MediaFormat.KEY_SAMPLE_RATE) ?: 0,
            channelCount = audio?.int(MediaFormat.KEY_CHANNEL_COUNT) ?: 0,
            bitrate = audio?.int(MediaFormat.KEY_BIT_RATE)?.toLong()?.let(::Bitrate),
          )
        },
      // Protection surfaces at the decoder, so every source reads as exportable up front.
      isExportable = true,
    )
  }

  private fun MediaMetadataRetriever.retrieverHdrTransfer(): HdrTransfer? =
    when (int(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER)) {
      COLOR_TRANSFER_HLG -> HdrTransfer.Hlg
      COLOR_TRANSFER_ST2084 -> HdrTransfer.Pq
      else -> null
    }

  /**
   * The two track formats a probe cares about.
   *
   * @property video The first video track, or null when the source has none the extractor could
   *   read.
   * @property audio The first audio track, on the same terms.
   */
  private class SourceTracks(
    val video: MediaFormat?,
    val audio: MediaFormat?,
  )

  /**
   * Bits per colour channel, or null when nothing in the track says.
   *
   * The profile is what carries this. Some vendor extractors publish the luma depth outright, and
   * where they do it is authoritative, but AOSP only ever sets that key on audio tracks, so a
   * stock device answers from the profile table or not at all. The clamp is what stops an audio
   * sixteen from being read back as a video bit depth if the wrong format is ever handed in.
   */
  private fun MediaFormat.bitDepth(): Int? {
    int(BITS_PER_SAMPLE)?.takeIf { it in PLAUSIBLE_DEPTHS }?.let { return it }
    val profile = int(MediaFormat.KEY_PROFILE) ?: return null

    // Profile constants are numbered per codec, never globally. 2 is Main10 for HEVC and AV1 but
    // Profile1 for VP9, so the MIME type has to choose the table.
    return when (string(MediaFormat.KEY_MIME)) {
      MediaFormat.MIMETYPE_VIDEO_HEVC -> HEVC_DEPTHS[profile]
      MediaFormat.MIMETYPE_VIDEO_AV1 -> AV1_DEPTHS[profile]
      MediaFormat.MIMETYPE_VIDEO_VP9 -> VP9_DEPTHS[profile]
      // Every H.264 profile other than High 10 is eight bit.
      MediaFormat.MIMETYPE_VIDEO_AVC -> if (profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10) 10 else 8
      else -> null
    }
  }

  private fun MediaFormat.colorSpace(): ColorSpace? =
    when (int(COLOR_STANDARD)) {
      COLOR_STANDARD_BT709 -> ColorSpace.Bt709
      COLOR_STANDARD_BT601_PAL, COLOR_STANDARD_BT601_NTSC -> ColorSpace.Bt601
      COLOR_STANDARD_BT2020 -> ColorSpace.Bt2020
      else -> null
    }

  private fun MediaFormat.hdrTransfer(): HdrTransfer? =
    when (int(COLOR_TRANSFER)) {
      COLOR_TRANSFER_HLG -> HdrTransfer.Hlg
      COLOR_TRANSFER_ST2084 -> HdrTransfer.Pq
      else -> null
    }

  /**
   * Width of a stored pixel over its height, or null when the track does not carry the ratio.
   */
  private fun MediaFormat.pixelAspectRatio(): Float? {
    val width = int(SAR_WIDTH) ?: return null
    val height = int(SAR_HEIGHT)?.takeIf { it != 0 } ?: return null
    return width.toFloat() / height
  }

  private fun MediaMetadataRetriever.retrieverColorSpace(): ColorSpace =
    when (int(MediaMetadataRetriever.METADATA_KEY_COLOR_STANDARD)) {
      COLOR_STANDARD_BT709 -> ColorSpace.Bt709
      COLOR_STANDARD_BT601_PAL, COLOR_STANDARD_BT601_NTSC -> ColorSpace.Bt601
      COLOR_STANDARD_BT2020 -> ColorSpace.Bt2020
      else -> ColorSpace.Unknown
    }

  /**
   * [MediaFormat.getInteger] throws on a key the format does not carry, and the overload that
   * takes a default is above this module's minimum.
   */
  private fun MediaFormat.int(key: String): Int? = if (containsKey(key)) getInteger(key) else null

  private fun MediaFormat.string(key: String): String? = if (containsKey(key)) getString(key) else null

  private fun MediaMetadataRetriever.metadata(key: Int): String? = extractMetadata(key)

  private fun MediaMetadataRetriever.int(key: Int): Int? = extractMetadata(key)?.toIntOrNull()

  private fun MediaMetadataRetriever.long(key: Int): Long? = extractMetadata(key)?.toLongOrNull()

  private fun MediaSource.unreadable(detail: String?): ExportError.SourceUnreadable =
    ExportError.SourceUnreadable(
      source = describe(),
      message = detail ?: "The source could not be opened.",
    )

  private companion object {
    // MediaFormat colour keys and constants, inlined because MediaFormat declares them above
    // minSdk. A device too old to have them never writes them, so the reads fall through.
    const val COLOR_STANDARD = "color-standard"
    const val COLOR_TRANSFER = "color-transfer"
    const val COLOR_TRANSFER_HLG = 7
    const val COLOR_TRANSFER_ST2084 = 6
    const val COLOR_STANDARD_BT709 = 1
    const val COLOR_STANDARD_BT601_PAL = 2
    const val COLOR_STANDARD_BT601_NTSC = 4
    const val COLOR_STANDARD_BT2020 = 6

    // KEY_PIXEL_ASPECT_RATIO_*, whose constants are above minSdk but whose keys every extractor
    // has always written.
    const val SAR_WIDTH = "sar-width"
    const val SAR_HEIGHT = "sar-height"

    // Not an AOSP key on a video track. Some vendor extractors set it, and the profile table is
    // what answers on a stock one.
    const val BITS_PER_SAMPLE = "bits-per-sample"

    const val SQUARE = 1f

    val PLAUSIBLE_DEPTHS = setOf(8, 10, 12)

    val HEVC_DEPTHS =
      mapOf(
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain to 8,
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMainStill to 8,
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 to 10,
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10 to 10,
        MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus to 10,
      )

    val AV1_DEPTHS =
      mapOf(
        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain8 to 8,
        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10 to 10,
        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10 to 10,
        MediaCodecInfo.CodecProfileLevel.AV1ProfileMain10HDR10Plus to 10,
      )

    // Profile 2 and 3 are ten or twelve bit and the profile alone cannot separate the two, so
    // these are the common reading, not a guarantee.
    val VP9_DEPTHS =
      mapOf(
        MediaCodecInfo.CodecProfileLevel.VP9Profile0 to 8,
        MediaCodecInfo.CodecProfileLevel.VP9Profile1 to 8,
        MediaCodecInfo.CodecProfileLevel.VP9Profile2 to 10,
        MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR to 10,
        MediaCodecInfo.CodecProfileLevel.VP9Profile2HDR10Plus to 10,
        MediaCodecInfo.CodecProfileLevel.VP9Profile3 to 12,
        MediaCodecInfo.CodecProfileLevel.VP9Profile3HDR to 12,
        MediaCodecInfo.CodecProfileLevel.VP9Profile3HDR10Plus to 12,
      )
  }
}

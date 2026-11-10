package dev.jordond.filmstrip.internal

import android.content.res.AssetFileDescriptor
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import dev.jordond.filmstrip.FilmstripContext
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.codec.Bitrate
import dev.jordond.filmstrip.color.CodestreamSignal
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaBytesFileFailure
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.describe
import dev.jordond.filmstrip.media.displaySizeOf
import dev.jordond.filmstrip.media.movieDurationOf
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.media.trackHeadersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.time.Duration.Companion.milliseconds

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
            FilmstripError.SourceUnreadable(source = source.uri, message = FilmstripContext.MISSING_CONTEXT),
          )
        } else {
          source.readContainer { setDataSource(android, Uri.parse(source.uri)) }
        }
      }
      // The retriever opens a path, so the buffer is written to the cache and probed as the file it
      // landed on. The export that follows finds the same file rather than writing its own.
      is MediaSource.Bytes -> {
        when (val cached = source.cached()) {
          is Cached.Failed -> ProbeResult.Failure(cached.error)
          is Cached.Written -> probe(MediaSource.Path(cached.file.path))
        }
      }
    }

  /**
   * Writes this source's bytes to the cache, or says why they could not be written.
   */
  private suspend fun MediaSource.Bytes.cached(): Cached =
    withContext(Dispatchers.IO) {
      val context =
        FilmstripContext.get()
          ?: return@withContext Cached.Failed(
            FilmstripError.SourceUnreadable(source = describe(), message = FilmstripContext.MISSING_CONTEXT),
          )

      try {
        Cached.Written(AndroidScratch.fileFor(context, this@cached))
      } catch (failure: MediaBytesFileFailure) {
        Cached.Failed(FilmstripError.SourceUnreadable(source = describe(), message = failure.message))
      }
    }

  /**
   * Where an in-memory source landed, or why it did not land anywhere.
   */
  private sealed interface Cached {
    class Written(
      val file: File,
    ) : Cached

    class Failed(
      val error: FilmstripError.SourceUnreadable,
    ) : Cached
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
        ProbeResult.Success(retriever.readInfo(tracks(), movieHeaders()))
      } catch (error: IllegalArgumentException) {
        ProbeResult.Failure(unreadable(error.message))
      } catch (cancelled: CancellationException) {
        throw cancelled
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
        // A still names no container, and bytes reach the extractor as the path they were cached
        // to rather than as themselves.
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

  /**
   * How long the source plays for by its MP4 or QuickTime movie header and what its track headers say, each null when
   * the file has none [movieDurationOf] and [trackHeadersOf] can read or it cannot be opened for random access.
   *
   * A URI is opened the way the retriever and the extractor open it, as an asset file descriptor, which can name a
   * range of a larger file such as a resource packed into an APK. The walk reads that range alone.
   */
  private suspend fun MediaSource.movieHeaders(): MovieHeaders =
    try {
      when (this) {
        is MediaSource.Path -> {
          FileInputStream(path).channel.use { it.movieHeaders(start = 0, length = it.size()) }
        }
        is MediaSource.Uri -> {
          val android = FilmstripContext.get() ?: return MovieHeaders.None
          val asset = android.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r") ?: return MovieHeaders.None
          ParcelFileDescriptor.AutoCloseInputStream(asset.parcelFileDescriptor).channel.use {
            val length = asset.declaredLength.takeIf { it != AssetFileDescriptor.UNKNOWN_LENGTH }
            it.movieHeaders(asset.startOffset, length ?: (it.size() - asset.startOffset))
          }
        }
        is MediaSource.Bytes, is MediaSource.Image -> {
          MovieHeaders.None
        }
      }
    } catch (unreadable: IOException) {
      MovieHeaders.None
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (unopened: RuntimeException) {
      // A content:// URI the resolver cannot open throws SecurityException or IllegalArgumentException.
      MovieHeaders.None
    }

  private suspend fun FileChannel.movieHeaders(
    start: Long,
    length: Long,
  ): MovieHeaders =
    MovieHeaders(
      duration = movieDurationOf(length) { at, count -> readAt(start + at, count) },
      tracks = trackHeadersOf(length) { at, count -> readAt(start + at, count) },
    )

  private fun FileChannel.readAt(
    start: Long,
    count: Int,
  ): ByteArray {
    val buffer = ByteBuffer.allocate(count)
    while (buffer.hasRemaining()) {
      if (read(buffer, start + buffer.position()) < 0) break
    }
    return buffer.array().copyOf(buffer.position())
  }

  private fun MediaExtractor.readTracks(): SourceTracks {
    var video: MediaFormat? = null
    val audio = mutableListOf<MediaFormat>()

    for (index in 0 until trackCount) {
      val format = getTrackFormat(index)
      when (format.string(MediaFormat.KEY_MIME)?.substringBefore('/')) {
        "video" -> if (video == null) video = format
        "audio" -> audio += format
      }
    }

    return SourceTracks(video = video, audio = audio)
  }

  private fun MediaMetadataRetriever.readInfo(
    tracks: SourceTracks?,
    headers: MovieHeaders,
  ): MediaInfo {
    val video = tracks?.video
    val hasVideo = extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
    val hasAudio = extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
    val rotation = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: 0
    val coded =
      Size(
        width = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: 0,
        height = int(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: 0,
      )
    val pixelAspect = video?.pixelAspectRatio() ?: SQUARE
    // Below API 28 the extractor reads no color off an MP4, and the retriever's color keys are API 30, so the sample
    // entry's own description is what answers there.
    val signal = headers.tracks?.firstNotNullOfOrNull { it.signal }

    return MediaInfo(
      // On API 30 the retriever reports an MP4 track's length ahead of its edit list, so an AAC track that opens on an
      // encoder delay runs long by that delay. The movie header carries the edited length whatever the API level.
      duration = headers.duration ?: (long(MediaMetadataRetriever.METADATA_KEY_DURATION) ?: 0L).milliseconds,
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
            colorSpace = video?.colorSpace() ?: signal?.colorSpace() ?: retrieverColorSpace(),
            hdrTransfer = video?.hdrTransfer() ?: signal?.hdrTransfer() ?: retrieverHdrTransfer(),
            bitrate =
              video?.int(MediaFormat.KEY_BIT_RATE)?.toLong()?.let(::Bitrate)
                ?: long(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.let(::Bitrate),
            // MediaCodec hands a video decoder's pictures back opaque and the extractor names no alpha track, so a
            // codec's alpha is dropped here and the track reads as opaque.
            hasAlpha = false,
          )
        },
      audioTracks = if (hasAudio) audioTracksOf(tracks?.audio.orEmpty(), headers) else emptyList(),
      // Protection surfaces at the decoder, so every source reads as exportable up front.
      isExportable = true,
    )
  }

  private fun CodestreamSignal.colorSpace(): ColorSpace? =
    when (primaries) {
      PRIMARIES_BT709 -> ColorSpace.Bt709
      PRIMARIES_BT601_PAL, PRIMARIES_BT601_NTSC -> ColorSpace.Bt601
      PRIMARIES_BT2020 -> ColorSpace.Bt2020
      else -> null
    }

  private fun CodestreamSignal.hdrTransfer(): HdrTransfer? =
    when (transfer) {
      TRANSFER_PQ -> HdrTransfer.Pq
      TRANSFER_HLG -> HdrTransfer.Hlg
      else -> null
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
   * @property audio Every audio track, in the container's order.
   */
  private class SourceTracks(
    val video: MediaFormat?,
    val audio: List<MediaFormat>,
  )

  /**
   * Bits per color channel, or null when nothing in the track says.
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

  private fun MediaMetadataRetriever.int(key: Int): Int? = extractMetadata(key)?.toIntOrNull()

  private fun MediaMetadataRetriever.long(key: Int): Long? = extractMetadata(key)?.toLongOrNull()

  private fun MediaSource.unreadable(detail: String?): FilmstripError.SourceUnreadable =
    FilmstripError.SourceUnreadable(
      source = describe(),
      message = detail ?: "The source could not be opened.",
    )

  private companion object {
    // MediaFormat color keys and constants, inlined because MediaFormat declares them above
    // minSdk. A device too old to have them never writes them, so the reads fall through.
    const val COLOR_STANDARD = "color-standard"
    const val COLOR_TRANSFER = "color-transfer"
    const val COLOR_TRANSFER_HLG = 7
    const val COLOR_TRANSFER_ST2084 = 6
    const val COLOR_STANDARD_BT709 = 1
    const val COLOR_STANDARD_BT601_PAL = 2
    const val COLOR_STANDARD_BT601_NTSC = 4
    const val COLOR_STANDARD_BT2020 = 6

    // ITU-T H.273 code points, as a `colr` box and an HEVC parameter set number them.
    const val PRIMARIES_BT709 = 1
    const val PRIMARIES_BT601_PAL = 5
    const val PRIMARIES_BT601_NTSC = 6
    const val PRIMARIES_BT2020 = 9
    const val TRANSFER_PQ = 16
    const val TRANSFER_HLG = 18

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

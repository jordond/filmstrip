package dev.jordond.filmstrip.media3.internal

import android.os.ParcelFileDescriptor
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Muxer
import androidx.media3.transformer.InAppMp4Muxer
import com.google.common.collect.ImmutableList
import java.nio.ByteBuffer
import kotlin.time.Duration

/**
 * Why a copy wrote no audio track though its source has one.
 */
internal const val COPY_AUDIO_SILENT: String =
  "The copy is shorter than one unit of the source's audio, so it holds no audio to write."

/**
 * Makes the muxer a copy writes through, which is media3's own with the end of each track set from what the copy
 * wrote.
 *
 * media3's muxer gives a track's last sample the length of the one before it, and none when the track holds fewer than
 * three, so a copy of a frame or two would record a video track of no length and an audio track shorter than its
 * priming. Here the video ends on [sourceEnd] when the copy runs to the end of its source, and otherwise where
 * [copiedVideoEndOf] puts it against [gridEnd], when there is a grid. The audio ends where [copiedAudioEndOf] puts
 * it, and a track of nothing but priming is never written.
 *
 * A lone audio unit of a codec other than AAC has no length to end on, and keeps the muxer's.
 */
internal class CopyEndMuxerFactory(
  sourceEnd: Duration?,
  gridEnd: Duration?,
) : Muxer.Factory {
  private val media3 =
    InAppMp4Muxer.Factory().apply { sourceEnd?.let { setVideoDurationUs(it.inWholeMicroseconds) } }
  private val gridEnd = gridEnd?.inWholeMicroseconds.takeIf { sourceEnd == null }

  /**
   * Whether the copy held an audio track with nothing to hear in it, which was left out of the file.
   */
  @Volatile
  var leftAudioOut: Boolean = false
    private set

  override fun create(path: String): Muxer = CopyEndMuxer(media3.create(path))

  override fun create(pfd: ParcelFileDescriptor): Muxer = CopyEndMuxer(media3.create(pfd))

  override fun getSupportedSampleMimeTypes(trackType: Int): ImmutableList<String> =
    media3.getSupportedSampleMimeTypes(trackType)

  override fun supportsWritingNegativeTimestampsInEditList(): Boolean =
    media3.supportsWritingNegativeTimestampsInEditList()

  private inner class CopyEndMuxer(
    private val muxer: Muxer,
  ) : Muxer {
    private var video: Int? = null
    private var audio: Int? = null
    private var audioUnit: Long? = null
    private val videoTimes = mutableListOf<Long>()
    private val audioTimes = mutableListOf<Long>()

    // The priming written so far, kept back until a unit that is heard follows it.
    private val priming = mutableListOf<Pair<ByteBuffer, BufferInfo>>()
    private var heard = false

    override fun addTrack(format: Format): Int {
      val track = muxer.addTrack(format)
      if (MimeTypes.isVideo(format.sampleMimeType)) video = track
      if (MimeTypes.isAudio(format.sampleMimeType)) {
        audio = track
        audioUnit = fixedAudioUnitOf(format.sampleMimeType, format.sampleRate)
      }
      return track
    }

    override fun writeSampleData(
      trackId: Int,
      byteBuffer: ByteBuffer,
      bufferInfo: BufferInfo,
    ) {
      when (trackId) {
        video -> {
          videoTimes += bufferInfo.presentationTimeUs
        }
        audio -> {
          audioTimes += bufferInfo.presentationTimeUs
          if (!heard && bufferInfo.presentationTimeUs < 0) {
            priming += byteBuffer.copied() to bufferInfo
            return
          }
          heard = true
          priming.forEach { (bytes, info) -> muxer.writeSampleData(trackId, bytes, info) }
          priming.clear()
        }
      }
      muxer.writeSampleData(trackId, byteBuffer, bufferInfo)
    }

    override fun addMetadataEntry(metadataEntry: Metadata.Entry) = muxer.addMetadataEntry(metadataEntry)

    override fun close() {
      video?.let { track -> gridEnd?.let { copiedVideoEndOf(videoTimes, it) }?.let { end(track, it) } }
      audio?.let { track ->
        when (val end = copiedAudioEndOf(audioTimes, audioUnit)) {
          is CopiedAudioEnd.At -> end(track, end.time)
          CopiedAudioEnd.Silent -> leftAudioOut = true
          CopiedAudioEnd.Unstamped -> Unit
        }
      }
      muxer.close()
    }

    private fun end(
      track: Int,
      time: Long,
    ) = muxer.writeSampleData(track, ByteBuffer.allocateDirect(0), BufferInfo(time, 0, C.BUFFER_FLAG_END_OF_STREAM))

    private fun ByteBuffer.copied(): ByteBuffer =
      ByteBuffer.allocateDirect(remaining()).also {
        it.put(duplicate())
        it.flip()
      }
  }
}

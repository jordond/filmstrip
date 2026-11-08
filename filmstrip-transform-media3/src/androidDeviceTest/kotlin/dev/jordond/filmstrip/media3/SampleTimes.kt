package dev.jordond.filmstrip.media3

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/**
 * The presentation time of every video sample [file] carries, earliest first.
 *
 * Read off the container rather than decoded, so a sample is counted where the file says it sits and not where a
 * decoder chose to show it.
 */
internal fun videoSampleTimes(file: File): List<Duration> =
  MediaExtractor().releasing { extractor ->
    extractor.setDataSource(file.path)
    extractor.selectTrack(extractor.trackOf(VIDEO))
    buildList {
      while (extractor.sampleTime >= 0) {
        add(extractor.sampleTime)
        extractor.advance()
      }
    }.sorted().map { it.microseconds }
  }

/**
 * How long [file]'s video track runs, as its container records it.
 *
 * The length is the track's own, not the file's, so an audio track running longer leaves it alone.
 */
internal fun videoTrackLength(file: File): Duration =
  MediaExtractor().releasing { extractor ->
    extractor.setDataSource(file.path)
    extractor.getTrackFormat(extractor.trackOf(VIDEO)).getLong(MediaFormat.KEY_DURATION).microseconds
  }

/**
 * How long [file]'s audio track runs, as its container records it.
 */
internal fun audioTrackLength(file: File): Duration =
  MediaExtractor().releasing { extractor ->
    extractor.setDataSource(file.path)
    extractor.getTrackFormat(extractor.trackOf(AUDIO)).getLong(MediaFormat.KEY_DURATION).microseconds
  }

/**
 * Copies the opening of [source]'s picture into [target] without decoding it, ending the copy part way into its last
 * frame.
 *
 * The copy keeps every video sample up to and including the source's second sync sample, so nothing kept leans on a
 * frame that was left out, and no sound. Its track ends [lastFrameLasts] after that last sample, which is shorter than
 * the frame it cut into.
 *
 * @return when the copy's last frame is shown.
 */
internal fun writePictureEndingInsideItsLastFrame(
  source: File,
  target: File,
  lastFrameLasts: Duration,
): Duration {
  target.delete()
  val extractor = MediaExtractor().apply { setDataSource(source.path) }
  val muxer = MediaMuxer(target.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
  return extractor.releasing {
    try {
      val index = it.trackOf(VIDEO)
      it.selectTrack(index)
      val track = muxer.addTrack(it.getTrackFormat(index))
      muxer.start()

      val buffer = ByteBuffer.allocate(SAMPLE_BUFFER_BYTES)
      val info = MediaCodec.BufferInfo()
      var syncs = 0
      var last = 0L
      // Stops once the second sync sample is written. Everything before it in decode order belongs to the first group.
      while (it.sampleTime >= 0 && syncs < 2) {
        val sync = (it.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
        if (sync) syncs++
        last = maxOf(last, it.sampleTime)
        info.set(0, it.readSampleData(buffer, 0), it.sampleTime, if (sync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        muxer.writeSampleData(track, buffer, info)
        it.advance()
      }
      // An empty sample marking the end sets how long the one before it lasts.
      info.set(0, 0, last + lastFrameLasts.inWholeMicroseconds, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
      muxer.writeSampleData(track, ByteBuffer.allocate(0), info)
      muxer.stop()
      last.microseconds
    } finally {
      muxer.release()
    }
  }
}

private inline fun <T> MediaExtractor.releasing(block: (MediaExtractor) -> T): T =
  try {
    block(this)
  } finally {
    release()
  }

private fun MediaExtractor.trackOf(kind: String): Int =
  (0 until trackCount).first { getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(kind) == true }

private const val VIDEO = "video/"

private const val AUDIO = "audio/"

// Comfortably more than one encoded frame of any fixture this suite copies.
private const val SAMPLE_BUFFER_BYTES = 4 * 1024 * 1024

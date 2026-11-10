package dev.jordond.filmstrip.media3

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.inspector.MediaExtractorCompat
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Mp4Muxer
import androidx.media3.muxer.SeekableMuxerOutput
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
  MediaExtractorCompat().releasing { extractor ->
    extractor.setDataSource(Uri.fromFile(file), 0L)
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
  MediaExtractorCompat().releasing { extractor ->
    extractor.setDataSource(Uri.fromFile(file), 0L)
    extractor.getTrackFormat(extractor.trackOf(VIDEO)).getLong(MediaFormat.KEY_DURATION).microseconds
  }

/**
 * How long [file]'s audio track runs, as its container records it.
 */
internal fun audioTrackLength(file: File): Duration =
  MediaExtractorCompat().releasing { extractor ->
    extractor.setDataSource(Uri.fromFile(file), 0L)
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
 * Samples are read with media3's extractor and written with media3's muxer, so the file is the same on every API
 * level. The framework muxer ignores the duration of the last sample below API 30.
 *
 * @return when the copy's last frame is shown.
 */
internal fun writePictureEndingInsideItsLastFrame(
  source: File,
  target: File,
  lastFrameLasts: Duration,
): Duration {
  target.delete()
  val extractor = MediaExtractorCompat().apply { setDataSource(Uri.fromFile(source), 0L) }
  val muxer = Mp4Muxer.Builder(SeekableMuxerOutput.of(target.path)).build()
  try {
    val index = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).isVideo() }
    extractor.selectTrack(index)
    val track = muxer.addTrack(MediaFormatUtil.createFormatFromMediaFormat(extractor.getTrackFormat(index)))

    val buffer = ByteBuffer.allocate(SAMPLE_BUFFER_BYTES)
    var syncs = 0
    var last = 0L
    // Stops once the second sync sample is written. Everything before it in decode order belongs to the first group.
    while (extractor.sampleTime >= 0 && syncs < 2) {
      val sync = (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
      if (sync) syncs++
      last = maxOf(last, extractor.sampleTime)
      buffer.clear()
      val size = extractor.readSampleData(buffer, 0)
      buffer.limit(size)
      val flags = if (sync) C.BUFFER_FLAG_KEY_FRAME else 0
      muxer.writeSampleData(track, buffer, BufferInfo(extractor.sampleTime, size, flags))
      extractor.advance()
    }
    // An empty sample marking the end sets how long the one before it lasts.
    val end = BufferInfo(last + lastFrameLasts.inWholeMicroseconds, 0, C.BUFFER_FLAG_END_OF_STREAM)
    muxer.writeSampleData(track, ByteBuffer.allocate(0), end)
    return last.microseconds
  } finally {
    extractor.release()
    muxer.close()
  }
}

private fun MediaFormat.isVideo(): Boolean = getString(MediaFormat.KEY_MIME)?.startsWith(VIDEO) == true

private inline fun <T> MediaExtractorCompat.releasing(block: (MediaExtractorCompat) -> T): T =
  try {
    block(this)
  } finally {
    release()
  }

private fun MediaExtractorCompat.trackOf(kind: String): Int =
  (0 until trackCount).first { getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(kind) == true }

private const val VIDEO = "video/"

private const val AUDIO = "audio/"

// Comfortably more than one encoded frame of any fixture this suite copies.
private const val SAMPLE_BUFFER_BYTES = 4 * 1024 * 1024

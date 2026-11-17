package dev.jordond.filmstrip.media3

import android.media.MediaExtractor
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

/**
 * Copies the picture of [source] into [target] with its samples [spacing] apart, in the order they are shown, without
 * decoding it.
 *
 * Each sample is written as it is, so the file is the source's own pictures at another rate. This is how a test gets a
 * source running at a rate no fixture holds.
 *
 * @return [target].
 */
internal fun retimeSamples(
  source: File,
  target: File,
  spacing: Duration,
): File {
  target.delete()
  val extractor = MediaExtractorCompat().apply { setDataSource(Uri.fromFile(source), 0L) }
  val muxer = Mp4Muxer.Builder(SeekableMuxerOutput.of(target.path)).build()
  try {
    val index = (0 until extractor.trackCount).first { isVideo(extractor, it) }
    extractor.selectTrack(index)
    val track = muxer.addTrack(MediaFormatUtil.createFormatFromMediaFormat(extractor.getTrackFormat(index)))
    val shownAt = shownTimes(source)

    val buffer = ByteBuffer.allocate(SAMPLE_BYTES)
    var written = 0L
    while (extractor.sampleTime >= 0) {
      val sync = (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
      buffer.clear()
      val size = extractor.readSampleData(buffer, 0)
      buffer.limit(size)
      val at = spacing.inWholeMicroseconds * shownAt.getValue(extractor.sampleTime)
      muxer.writeSampleData(track, buffer, BufferInfo(at, size, if (sync) C.BUFFER_FLAG_KEY_FRAME else 0))
      extractor.advance()
      written++
    }
    muxer.writeSampleData(
      track,
      ByteBuffer.allocate(0),
      BufferInfo(spacing.inWholeMicroseconds * written, 0, C.BUFFER_FLAG_END_OF_STREAM),
    )
    return target
  } finally {
    extractor.release()
    muxer.close()
  }
}

// Each sample's place in the order they are shown, keyed by its own time.
private fun shownTimes(source: File): Map<Long, Int> =
  videoSampleTimes(source).mapIndexed { order, time -> time.inWholeMicroseconds to order }.toMap()

private fun isVideo(
  extractor: MediaExtractorCompat,
  index: Int,
): Boolean = extractor.getTrackFormat(index).getString("mime")?.startsWith("video/") == true

// Comfortably more than one encoded frame of any fixture this suite retimes.
private const val SAMPLE_BYTES = 4 * 1024 * 1024

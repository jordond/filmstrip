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
import kotlin.math.roundToLong

/**
 * One copy of a source's audio track in a remuxed file.
 *
 * @property sampleRate The AAC-LC sample rate the copy is relabelled to, which puts its access units on another grid,
 * or null to keep the source's.
 */
internal class AudioCopy(
  val sampleRate: Int? = null,
)

/**
 * Writes [source]'s video track to [target] followed by one audio track per entry in [copies], each a copy of the
 * source's first audio track.
 */
internal fun remuxed(
  source: File,
  target: File,
  copies: List<AudioCopy>,
) {
  target.delete()
  val reader = MediaExtractorCompat()
  val formats =
    try {
      reader.setDataSource(Uri.fromFile(source), 0L)
      (0 until reader.trackCount).map { reader.getTrackFormat(it) }
    } finally {
      reader.release()
    }
  val video = formats.indexOfFirst { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
  val audio = formats.indexOfFirst { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
  // media3's muxer, since the framework one takes a single audio track below API 29.
  val muxer = Mp4Muxer.Builder(SeekableMuxerOutput.of(target.path)).build()
  try {
    val videoOut = muxer.addTrack(MediaFormatUtil.createFormatFromMediaFormat(formats[video]))
    val audioOut =
      copies.map { copy ->
        muxer.addTrack(MediaFormatUtil.createFormatFromMediaFormat(relabelled(formats[audio], copy)))
      }
    copySamples(source, video, muxer, videoOut, 1.0)
    copies.forEachIndexed { index, copy ->
      val rate = formats[audio].getInteger(MediaFormat.KEY_SAMPLE_RATE)
      copySamples(source, audio, muxer, audioOut[index], rate.toDouble() / (copy.sampleRate ?: rate))
    }
  } finally {
    muxer.close()
  }
}

private fun copySamples(
  source: File,
  track: Int,
  muxer: Mp4Muxer,
  into: Int,
  timeScale: Double,
) {
  val extractor = MediaExtractorCompat()
  try {
    extractor.setDataSource(Uri.fromFile(source), 0L)
    extractor.selectTrack(track)
    val buffer = ByteBuffer.allocate(SAMPLE_BUFFER_BYTES)
    while (true) {
      buffer.clear()
      val size = extractor.readSampleData(buffer, 0)
      if (size < 0) break
      buffer.limit(size)
      val sync = (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
      val flags = if (sync) C.BUFFER_FLAG_KEY_FRAME else 0
      muxer.writeSampleData(into, buffer, BufferInfo((extractor.sampleTime * timeScale).roundToLong(), size, flags))
      extractor.advance()
    }
  } finally {
    extractor.release()
  }
}

/**
 * [format] relabelled to [copy]'s sample rate by an AAC-LC config naming that rate, or [format] itself when [copy]
 * names no rate.
 *
 * The format is built afresh because copying one takes a constructor from API 29.
 */
private fun relabelled(
  format: MediaFormat,
  copy: AudioCopy,
): MediaFormat {
  val rate = copy.sampleRate ?: return format
  val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
  val index = AAC_RATES.indexOf(rate)
  check(index >= 0) { "$rate Hz is not an AAC sample rate" }
  // AudioSpecificConfig: five bits of object type, four of rate index, four of channel layout.
  val config = (AAC_LC shl 11) or (index shl 7) or (channels shl 3)
  return MediaFormat.createAudioFormat(checkNotNull(format.getString(MediaFormat.KEY_MIME)), rate, channels).apply {
    setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf((config shr 8).toByte(), config.toByte())))
  }
}

private const val AAC_LC = 2
private val AAC_RATES =
  listOf(96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000, 22_050, 16_000, 12_000, 11_025, 8_000)
private const val SAMPLE_BUFFER_BYTES = 1 shl 20

package dev.jordond.filmstrip.media3

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import kotlin.math.roundToLong

/**
 * One copy of a source's audio track in a remuxed file.
 *
 * @property language The ISO 639-2 code the copy is tagged with.
 * @property sampleRate The AAC-LC sample rate the copy is relabelled to, which puts its access units on another grid,
 * or null to keep the source's.
 */
internal class AudioCopy(
  val language: String,
  val sampleRate: Int? = null,
)

/**
 * The ISO 639-2 code of the device's own language, which media3's track selector prefers.
 */
internal val deviceLanguage: String get() = Locale.getDefault().isO3Language

/**
 * A language other than the device's own, for a track media3's selector passes over.
 */
internal val otherLanguage: String get() = if (deviceLanguage == "fra") "deu" else "fra"

/**
 * Writes [source]'s video track to [target] followed by one audio track per entry in [copies], each a copy of the
 * source's first audio track.
 */
internal fun remuxed(
  source: File,
  target: File,
  copies: List<AudioCopy>,
) {
  val reader = MediaExtractor()
  val formats =
    try {
      reader.setDataSource(source.path)
      (0 until reader.trackCount).map { reader.getTrackFormat(it) }
    } finally {
      reader.release()
    }
  val video = formats.indexOfFirst { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
  val audio = formats.indexOfFirst { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
  val muxer = MediaMuxer(target.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
  try {
    val videoOut = muxer.addTrack(formats[video])
    val audioOut =
      copies.map { copy ->
        muxer.addTrack(relabelled(formats[audio], copy))
      }
    muxer.start()
    copySamples(source, video, muxer, videoOut, 1.0)
    copies.forEachIndexed { index, copy ->
      val rate = formats[audio].getInteger(MediaFormat.KEY_SAMPLE_RATE)
      copySamples(source, audio, muxer, audioOut[index], rate.toDouble() / (copy.sampleRate ?: rate))
    }
    muxer.stop()
  } finally {
    muxer.release()
  }
}

private fun copySamples(
  source: File,
  track: Int,
  muxer: MediaMuxer,
  into: Int,
  timeScale: Double,
) {
  val extractor = MediaExtractor()
  try {
    extractor.setDataSource(source.path)
    extractor.selectTrack(track)
    val buffer = ByteBuffer.allocate(SAMPLE_BUFFER_BYTES)
    val info = MediaCodec.BufferInfo()
    while (true) {
      val size = extractor.readSampleData(buffer, 0)
      if (size < 0) break
      val flags =
        if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC !=
          0
        ) {
          MediaCodec.BUFFER_FLAG_KEY_FRAME
        } else {
          0
        }
      info.set(0, size, (extractor.sampleTime * timeScale).roundToLong(), flags)
      muxer.writeSampleData(into, buffer, info)
      extractor.advance()
    }
  } finally {
    extractor.release()
  }
}

/**
 * [format] tagged with [copy]'s language, and relabelled to its sample rate by an AAC-LC config naming that rate.
 */
private fun relabelled(
  format: MediaFormat,
  copy: AudioCopy,
): MediaFormat =
  MediaFormat(format).apply {
    setString(MediaFormat.KEY_LANGUAGE, copy.language)
    val rate = copy.sampleRate ?: return@apply
    val channels = getInteger(MediaFormat.KEY_CHANNEL_COUNT)
    val index = AAC_RATES.indexOf(rate)
    check(index >= 0) { "$rate Hz is not an AAC sample rate" }
    setInteger(MediaFormat.KEY_SAMPLE_RATE, rate)
    // AudioSpecificConfig: five bits of object type, four of rate index, four of channel layout.
    val config = (AAC_LC shl 11) or (index shl 7) or (channels shl 3)
    setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf((config shr 8).toByte(), config.toByte())))
  }

private const val AAC_LC = 2
private val AAC_RATES =
  listOf(96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000, 22_050, 16_000, 12_000, 11_025, 8_000)
private const val SAMPLE_BUFFER_BYTES = 1 shl 20

package dev.jordond.filmstrip.media3

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import dev.jordond.filmstrip.test.ToneAnalysis
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The audio of one file, decoded to mono samples on the -1 to 1 scale.
 *
 * @property samples Every frame the decoder produced, averaged across its channels.
 * @property sampleRate The rate the decoder ran at, which is what a window measured in time is
 *   converted against.
 */
internal class DecodedAudio(
  val samples: FloatArray,
  val sampleRate: Int,
) {
  /**
   * How long the decoded run is.
   */
  val duration: Duration get() = samplesToDuration(samples.size, sampleRate)

  /**
   * The amplitude of [frequencyHz] over the [length] starting at [from].
   *
   * Reads through the shared [ToneAnalysis] rather than measuring here, so this backend and the
   * others cannot disagree about what a level means.
   */
  fun amplitudeOver(
    from: Duration,
    length: Duration,
    frequencyHz: Int,
  ): Float =
    ToneAnalysis.amplitudeOver(
      samples = samples,
      from = durationToSamples(from, sampleRate),
      length = durationToSamples(length, sampleRate),
      frequencyHz = frequencyHz,
      sampleRate = sampleRate,
    )
}

/**
 * Decodes the audio track of [file] to PCM.
 *
 * MediaExtractor feeds the compressed samples to a MediaCodec decoder in synchronous mode and the
 * output buffers are concatenated, which is the only way to read what an export actually wrote.
 * The prober reports what the container claims, and a mix that never reached the encoder claims the
 * same thing as one that did.
 *
 * @throws AssertionError when the file carries no audio track, or when the decoder stalls.
 */
internal fun decodeAudio(file: File): DecodedAudio {
  val extractor = MediaExtractor()
  try {
    extractor.setDataSource(file.path)
    val track =
      (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).isAudio }
        ?: throw AssertionError("${file.name} carries no audio track to decode")
    extractor.selectTrack(track)

    val format = extractor.getTrackFormat(track)
    val mimeType = format.getString(MediaFormat.KEY_MIME) ?: throw AssertionError("the audio track names no MIME type")
    val codec = MediaCodec.createDecoderByType(mimeType)
    return try {
      codec.configure(format, null, null, 0)
      codec.start()
      codec.drain(extractor)
    } finally {
      // Stopped before it is released, which is the order MediaCodec documents for a codec that
      // reached Executing. Stopping can throw if the codec already died, and that must not mask
      // the failure that got us here.
      runCatching { codec.stop() }
      codec.release()
    }
  } finally {
    extractor.release()
  }
}

/**
 * Pumps [extractor] through this decoder until the end of the stream comes back out.
 */
private fun MediaCodec.drain(extractor: MediaExtractor): DecodedAudio {
  val pcm = ByteArrayOutputStream()
  val info = MediaCodec.BufferInfo()
  var format = outputFormat
  var fed = false
  var drained = false
  val deadline = System.nanoTime() + DECODE_BUDGET.inWholeNanoseconds

  while (!drained) {
    if (System.nanoTime() > deadline) throw AssertionError("the audio decoder stalled after $DECODE_BUDGET")

    if (!fed) {
      val input = dequeueInputBuffer(WAIT_MICROS)
      if (input >= 0) {
        val buffer = requireNotNull(getInputBuffer(input)) { "the codec handed back no input buffer" }
        val read = extractor.readSampleData(buffer, 0)
        if (read < 0) {
          queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
          fed = true
        } else {
          queueInputBuffer(input, 0, read, extractor.sampleTime, 0)
          extractor.advance()
        }
      }
    }

    val output = dequeueOutputBuffer(info, WAIT_MICROS)
    if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) format = outputFormat
    if (output < 0) continue

    if (info.size > 0) {
      val buffer = requireNotNull(getOutputBuffer(output)) { "the codec handed back no output buffer" }
      val chunk = ByteArray(info.size)
      buffer.position(info.offset)
      buffer.get(chunk)
      pcm.write(chunk)
    }
    releaseOutputBuffer(output, false)
    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) drained = true
  }

  val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
  return DecodedAudio(
    samples = ToneAnalysis.toMono(pcm.toByteArray().toSamples(format.pcmEncoding), channels),
    sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
  )
}

/**
 * These raw bytes as samples on the -1 to 1 scale.
 *
 * MediaCodec writes PCM in the platform's own byte order, so the buffer is read in native order
 * rather than in a fixed one.
 */
private fun ByteArray.toSamples(encoding: Int): FloatArray {
  val buffer = ByteBuffer.wrap(this).order(ByteOrder.nativeOrder())
  return when (encoding) {
    AudioFormat.ENCODING_PCM_FLOAT -> FloatArray(size / Float.SIZE_BYTES) { buffer.getFloat() }
    AudioFormat.ENCODING_PCM_16BIT -> FloatArray(size / Short.SIZE_BYTES) { buffer.getShort() / SHORT_FULL_SCALE }
    else -> throw AssertionError("the decoder produced PCM encoding $encoding, which nothing here reads")
  }
}

private val MediaFormat.isAudio: Boolean
  get() = getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true

// Absent on most devices, and 16-bit is what MediaCodec produces when it says nothing.
private val MediaFormat.pcmEncoding: Int
  get() =
    if (containsKey(MediaFormat.KEY_PCM_ENCODING)) {
      getInteger(MediaFormat.KEY_PCM_ENCODING)
    } else {
      AudioFormat.ENCODING_PCM_16BIT
    }

private fun durationToSamples(
  time: Duration,
  sampleRate: Int,
): Int = (time.inWholeMicroseconds * sampleRate / MICROS_PER_SECOND).toInt()

private fun samplesToDuration(
  samples: Int,
  sampleRate: Int,
): Duration = (samples.toDouble() / sampleRate).seconds

// Long enough that a slow decoder is not called a stall, short enough that a wedged one fails the
// test rather than the whole instrumentation run.
private val DECODE_BUDGET = 60.seconds

private const val WAIT_MICROS = 10_000L
private const val MICROS_PER_SECOND = 1_000_000L
private const val SHORT_FULL_SCALE = 32_768f

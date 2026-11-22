package dev.jordond.filmstrip.internal

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

/**
 * Whether the first sync sample of [track] fails to decode, on a device with a decoder that takes [video].
 *
 * The sample goes through a decoder of its own, followed by the end of the stream. A picture out before the decoder
 * ends the stream reads as whole. A stream that ends with no picture, or a decoder that fails once it holds the
 * sample, reads as damaged.
 *
 * A format no decoder here takes, a decoder that fails to start and a decoder that is still silent after
 * [DECODER_HANG_BOUND] say nothing about the bytes, so each reads as whole. This blocks until the decoder answers and
 * leaves [track] selected.
 */
internal fun MediaExtractor.firstSampleDamaged(
  track: Int,
  video: MediaFormat,
): Boolean {
  val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(video) ?: return false
  var codec: MediaCodec? = null
  var queued = false
  return try {
    selectTrack(track)
    seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
    codec = MediaCodec.createByCodecName(name)
    codec.configure(video, null, null, 0)
    codec.start()
    val deadline = System.nanoTime() + DECODER_HANG_BOUND.inWholeNanoseconds

    val sample = codec.dequeueInputBuffer(deadline.microsecondsLeft())
    val buffer = (if (sample >= 0) codec.getInputBuffer(sample) else null) ?: return false
    val size = readSampleData(buffer, 0).coerceAtLeast(0)
    codec.queueInputBuffer(sample, 0, size, sampleTime.coerceAtLeast(0), 0)
    queued = true

    val end = codec.dequeueInputBuffer(deadline.microsecondsLeft())
    if (end < 0) return false
    codec.queueInputBuffer(end, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)

    codec.endsWithoutPicture(deadline)
  } catch (failed: MediaCodec.CodecException) {
    queued
  } catch (unusable: IllegalStateException) {
    false
  } catch (unopened: IOException) {
    false
  } catch (refused: IllegalArgumentException) {
    false
  } finally {
    codec?.release()
  }
}

/**
 * Drains this decoder until it puts out a picture, which answers false, or ends the stream without one, which answers
 * true. A decoder that does neither before [deadline], a [System.nanoTime] reading, answers false.
 */
private fun MediaCodec.endsWithoutPicture(deadline: Long): Boolean {
  val info = MediaCodec.BufferInfo()
  while (true) {
    val left = deadline.microsecondsLeft()
    if (left == 0L) return false
    val output = dequeueOutputBuffer(info, left)
    if (output < 0) continue
    releaseOutputBuffer(output, false)
    if (info.size > 0) return false
    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return true
  }
}

private fun Long.microsecondsLeft(): Long = ((this - System.nanoTime()) / 1_000).coerceAtLeast(0)

// How long a decoder may stay silent. Past it the decoder has hung, which is not a verdict on the sample.
private val DECODER_HANG_BOUND = 5.seconds

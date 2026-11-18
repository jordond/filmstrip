package dev.jordond.filmstrip.media3.internal

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import dev.jordond.filmstrip.edit.audioFramesOf
import java.nio.ByteBuffer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/**
 * Holds a clip's audio to exactly [length], cutting what runs past it and padding with silence what falls short.
 *
 * It sits after the clip's speed change, whose output length is an estimate. Each flush starts the count again from
 * where it opens the item, so a seek into the middle of a clip is held to what is left of the length past that point.
 * A stream that ends before its first frame since a flush has no audio to fall short and ends with nothing written.
 * media3 ends a stream that way to reconfigure its item, and reads nothing it writes until the item queues a frame.
 *
 * @param length How long the item's audio runs.
 * @param reportsLength Whether media3 lays the item after this one by [length]. An item media3 changes the speed of
 * refuses any processor that reports a length of its own, so the pin on one passes the length it is given through.
 * media3 asks the same question to place the processors after this one on a flush, and the start of the item stays
 * the start of the item, so those count from where the item opens.
 */
internal class AudioLengthPin(
  private val length: Duration,
  private val reportsLength: Boolean,
) : BaseAudioProcessor() {
  private var bytesLeft = 0L
  private var started = false
  private var ended = false

  override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
    if (inputAudioFormat.encoding == C.ENCODING_INVALID) {
      throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
    } else {
      inputAudioFormat
    }

  override fun queueInput(inputBuffer: ByteBuffer) {
    val available = inputBuffer.remaining()
    if (available == 0) return
    started = true
    val taken = minOf(available.toLong(), bytesLeft).toInt()
    val output = replaceOutputBuffer(taken)
    val limit = inputBuffer.limit()
    inputBuffer.limit(inputBuffer.position() + taken)
    output.put(inputBuffer)
    inputBuffer.limit(limit)
    inputBuffer.position(limit)
    output.flip()
    bytesLeft -= taken
  }

  override fun getOutput(): ByteBuffer {
    if (pads() && ended && !hasPendingOutput()) {
      val frameBytes = inputAudioFormat.bytesPerFrame
      val size = minOf(bytesLeft, PADDING_BLOCK_BYTES - PADDING_BLOCK_BYTES % frameBytes).toInt()
      val output = replaceOutputBuffer(size)
      repeat(size) { output.put(0) }
      output.flip()
      bytesLeft -= size
    }
    return super.getOutput()
  }

  override fun getDurationAfterProcessorApplied(durationUs: Long): Long =
    if (reportsLength && durationUs != 0L) length.inWholeMicroseconds else durationUs

  override fun isEnded(): Boolean = super.isEnded() && !pads()

  private fun pads(): Boolean = started && bytesLeft > 0

  override fun onQueueEndOfStream() {
    ended = true
  }

  // media3 hands each processor the offset in its own input, which past the speed change is the clip's output time.
  override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
    ended = false
    started = false
    val format = inputAudioFormat
    val opens = audioFramesOf(streamMetadata.positionOffsetUs.microseconds, format.sampleRate)
    val frames = (audioFramesOf(length, format.sampleRate) - opens).coerceAtLeast(0L)
    bytesLeft = frames * format.bytesPerFrame
  }

  override fun onReset() {
    bytesLeft = 0L
    started = false
    ended = false
  }

  private companion object {
    const val PADDING_BLOCK_BYTES = 16_384L
  }
}

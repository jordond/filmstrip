package dev.jordond.filmstrip.media3.internal

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import dev.jordond.filmstrip.edit.audioFramesOf
import java.nio.ByteBuffer
import kotlin.time.Duration

/**
 * Holds a clip's audio to exactly [length], cutting what runs past it and padding with silence what falls short.
 *
 * It sits after the clip's speed change, whose output length is an estimate. The count runs from the first frame the
 * item plays, so a flush after that, which is a seek in a preview, leaves the rest of the item as it comes.
 *
 * @param length How long the item's audio runs.
 * @param reportsLength Whether media3 lays the item after this one by [length]. An item media3 changes the speed of
 * refuses any processor that reports a length of its own, so the pin on one passes the length it is given through.
 */
internal class AudioLengthPin(
  private val length: Duration,
  private val reportsLength: Boolean,
) : BaseAudioProcessor() {
  private var bytesLeft = 0L
  private var started = false
  private var pinned = true
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
    val taken = if (pinned) minOf(available.toLong(), bytesLeft).toInt() else available
    val output = replaceOutputBuffer(taken)
    val limit = inputBuffer.limit()
    inputBuffer.limit(inputBuffer.position() + taken)
    output.put(inputBuffer)
    inputBuffer.limit(limit)
    inputBuffer.position(limit)
    output.flip()
    if (pinned) bytesLeft -= taken
  }

  override fun getOutput(): ByteBuffer {
    if (pinned && ended && bytesLeft > 0 && !hasPendingOutput()) {
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
    if (reportsLength) length.inWholeMicroseconds else durationUs

  override fun isEnded(): Boolean = super.isEnded() && (!pinned || bytesLeft == 0L)

  override fun onQueueEndOfStream() {
    ended = true
  }

  override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
    ended = false
    if (started) {
      pinned = false
    } else {
      bytesLeft = audioFramesOf(length, inputAudioFormat.sampleRate) * inputAudioFormat.bytesPerFrame
    }
  }

  override fun onReset() {
    bytesLeft = 0L
    started = false
    pinned = true
    ended = false
  }

  private companion object {
    const val PADDING_BLOCK_BYTES = 16_384L
  }
}

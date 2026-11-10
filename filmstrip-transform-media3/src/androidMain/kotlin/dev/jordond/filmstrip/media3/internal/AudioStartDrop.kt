package dev.jordond.filmstrip.media3.internal

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import dev.jordond.filmstrip.edit.audioFramesOf
import java.nio.ByteBuffer
import kotlin.time.Duration

/**
 * Drops the first [lead] of a clip's decoded audio, so the item opens on the source frame at its trim start.
 *
 * The item is clipped from the start of the access unit before the one holding the trim start, which media3 keeps
 * whole, and this cuts the frames between that unit's start and the trim start. Like [AudioLengthPin] the count runs
 * from the first frame the item plays, so a flush after that leaves the rest of the item as it comes.
 *
 * @param lead How far the trim start sits past the first decoded frame.
 */
internal class AudioStartDrop(
  private val lead: Duration,
) : BaseAudioProcessor() {
  private var bytesLeft = 0L
  private var started = false

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
    val dropped = minOf(available.toLong(), bytesLeft).toInt()
    bytesLeft -= dropped
    inputBuffer.position(inputBuffer.position() + dropped)
    val output = replaceOutputBuffer(inputBuffer.remaining())
    output.put(inputBuffer)
    output.flip()
  }

  override fun getDurationAfterProcessorApplied(durationUs: Long): Long =
    if (durationUs == C.TIME_UNSET) durationUs else (durationUs - lead.inWholeMicroseconds).coerceAtLeast(0)

  override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
    bytesLeft = if (started) 0L else audioFramesOf(lead, inputAudioFormat.sampleRate) * inputAudioFormat.bytesPerFrame
  }

  override fun onReset() {
    bytesLeft = 0L
    started = false
  }
}

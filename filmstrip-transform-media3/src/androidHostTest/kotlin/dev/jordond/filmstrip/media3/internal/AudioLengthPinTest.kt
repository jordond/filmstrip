package dev.jordond.filmstrip.media3.internal

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.audioFramesOf
import dev.jordond.filmstrip.edit.outputLengthOf
import io.kotest.matchers.shouldBe
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What [AudioLengthPin] does to a stream that runs past its length, one that falls short and one a seek interrupts, and
 * the length it reports.
 */
@OptIn(InternalFilmstripApi::class)
class AudioLengthPinTest {
  private val format = AudioProcessor.AudioFormat(RATE, CHANNELS, C.ENCODING_PCM_16BIT)

  @Test
  fun `a stream running past the length is cut on it`() {
    val pin = configured()

    val written = pin.pass(FRAMES + EXTRA_FRAMES)

    written shouldBe FRAMES.toLong()
    pin.isEnded shouldBe true
  }

  @Test
  fun `a stream falling short is padded with silence up to the length`() {
    val pin = configured()

    val written = pin.pass(FRAMES - EXTRA_FRAMES)

    written shouldBe FRAMES.toLong()
    pin.isEnded shouldBe true
  }

  @Test
  fun `a stream landing on the length passes through`() {
    val pin = configured()

    pin.pass(FRAMES) shouldBe FRAMES.toLong()
    pin.isEnded shouldBe true
  }

  @Test
  fun `a flush after the first frame leaves the rest of the item as it comes`() {
    val pin = configured()
    pin.queue(FRAMES / 2)
    pin.drain()

    pin.flush(AudioProcessor.StreamMetadata.DEFAULT)

    pin.pass(FRAMES + EXTRA_FRAMES) shouldBe (FRAMES + EXTRA_FRAMES).toLong()
    pin.isEnded shouldBe true
  }

  @Test
  fun `silence above two channels pads in whole frames up to the length`() {
    val surround = AudioProcessor.AudioFormat(RATE, SURROUND_CHANNELS, C.ENCODING_PCM_16BIT)
    val pin =
      AudioLengthPin(LENGTH, reportsLength = true).apply {
        configure(surround)
        flush(AudioProcessor.StreamMetadata.DEFAULT)
      }

    pin.queueEndOfStream()

    var frames = 0
    while (true) {
      val output = pin.getOutput()
      if (!output.hasRemaining()) break
      output.remaining() % surround.bytesPerFrame shouldBe 0
      frames += output.remaining() / surround.bytesPerFrame
      output.position(output.limit())
    }
    frames shouldBe FRAMES
    pin.isEnded shouldBe true
  }

  @Test
  fun `a length that is no whole microsecond pins to the shared count of frames`() {
    val length = outputLengthOf(0.8.seconds, 1.9)
    val pin = configured(length)

    pin.pass(FRAMES) shouldBe audioFramesOf(length, RATE)
  }

  @Test
  fun `a pin that reports its length hands media3 that length`() {
    AudioLengthPin(LENGTH, reportsLength = true).getDurationAfterProcessorApplied(INPUT_US) shouldBe
      LENGTH.inWholeMicroseconds
  }

  @Test
  fun `a pin on an item media3 changes the speed of reports the length it is given`() {
    AudioLengthPin(LENGTH, reportsLength = false).getDurationAfterProcessorApplied(INPUT_US) shouldBe INPUT_US
  }

  private fun configured(length: Duration = LENGTH): AudioLengthPin =
    AudioLengthPin(length, reportsLength = true).apply {
      configure(format)
      flush(AudioProcessor.StreamMetadata.DEFAULT)
    }

  private fun AudioLengthPin.queue(frames: Int) {
    val bytes = ByteBuffer.allocateDirect(frames * format.bytesPerFrame).order(ByteOrder.nativeOrder())
    queueInput(bytes)
  }

  private fun AudioLengthPin.drain(): Int {
    var frames = 0
    while (true) {
      val output = getOutput()
      if (!output.hasRemaining()) return frames
      frames += output.remaining() / format.bytesPerFrame
      output.position(output.limit())
    }
  }

  private fun AudioLengthPin.pass(frames: Int): Long {
    queue(frames)
    var written = drain().toLong()
    queueEndOfStream()
    written += drain()
    return written
  }

  private companion object {
    const val RATE = 48_000
    const val CHANNELS = 2
    const val SURROUND_CHANNELS = 6
    val LENGTH = 500.milliseconds
    const val FRAMES = 24_000
    const val EXTRA_FRAMES = 1_000
    const val INPUT_US = 1_234_567L
  }
}

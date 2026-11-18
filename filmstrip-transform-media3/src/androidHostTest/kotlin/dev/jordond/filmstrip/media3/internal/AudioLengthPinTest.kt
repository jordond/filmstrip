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

  // media3 ends the stream of an item to reconfigure it, and reads none of what that stream writes until the item
  // has queued a frame, so silence written for a stream that never started would hold the item there for good.
  @Test
  fun `a stream that ends before its first frame writes nothing and ends`() {
    val pin = configured()

    pin.pass(0) shouldBe 0L
    pin.isEnded shouldBe true
  }

  @Test
  fun `a seek into the middle of the item cuts the rest on the length`() {
    val pin = configured()
    pin.queue(FRAMES / 4)
    pin.drain()

    pin.flush(AudioProcessor.StreamMetadata(HALF.inWholeMicroseconds))

    pin.pass(FRAMES) shouldBe (FRAMES / 2).toLong()
    pin.isEnded shouldBe true
  }

  @Test
  fun `a seek into the middle of the item pads only what is left of the length`() {
    val pin = configured()

    pin.flush(AudioProcessor.StreamMetadata(HALF.inWholeMicroseconds))

    pin.pass(FRAMES / 2 - EXTRA_FRAMES) shouldBe (FRAMES / 2).toLong()
    pin.isEnded shouldBe true
  }

  @Test
  fun `a seek past the length writes nothing past it`() {
    val pin = configured()

    pin.flush(AudioProcessor.StreamMetadata(LENGTH.inWholeMicroseconds + INPUT_US))

    pin.pass(EXTRA_FRAMES) shouldBe 0L
    pin.isEnded shouldBe true
  }

  @Test
  fun `a replay from the start after the item played pins the whole length again`() {
    val pin = configured()
    pin.pass(FRAMES)

    pin.flush(AudioProcessor.StreamMetadata.DEFAULT)

    pin.pass(FRAMES - EXTRA_FRAMES) shouldBe FRAMES.toLong()
    pin.isEnded shouldBe true
  }

  @Test
  fun `a stream that ends before its first frame since a seek writes nothing`() {
    val pin = configured()
    pin.queue(FRAMES / 4)
    pin.drain()

    pin.flush(AudioProcessor.StreamMetadata(HALF.inWholeMicroseconds))

    pin.pass(0) shouldBe 0L
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

    pin.queueInput(ByteBuffer.allocateDirect(surround.bytesPerFrame).order(ByteOrder.nativeOrder()))
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
  fun `a pin that reports its length leaves the start of the item where it is`() {
    AudioLengthPin(LENGTH, reportsLength = true).getDurationAfterProcessorApplied(0L) shouldBe 0L
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
    val HALF = LENGTH / 2
    const val FRAMES = 24_000
    const val EXTRA_FRAMES = 1_000
    const val INPUT_US = 1_234_567L
  }
}

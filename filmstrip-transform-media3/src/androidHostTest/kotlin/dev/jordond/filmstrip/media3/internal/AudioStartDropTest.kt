package dev.jordond.filmstrip.media3.internal

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import io.kotest.matchers.shouldBe
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

/**
 * What [AudioStartDrop] cuts from the head of a stream, across buffers and after a seek, and the length it reports.
 */
class AudioStartDropTest {
  private val format = AudioProcessor.AudioFormat(RATE, CHANNELS, C.ENCODING_PCM_16BIT)

  @Test
  fun `the lead is cut from the head of the stream`() {
    val drop = configured()

    drop.passFrom(0, FRAMES) shouldBe (LEAD_FRAMES until FRAMES).toList()
  }

  @Test
  fun `a lead longer than the first buffer is cut across buffers`() {
    val drop = configured()

    drop.passFrom(0, LEAD_FRAMES / 2) shouldBe emptyList()
    drop.passFrom(LEAD_FRAMES / 2, FRAMES) shouldBe (LEAD_FRAMES until FRAMES).toList()
  }

  @Test
  fun `a flush after the first frame cuts nothing more`() {
    val drop = configured()
    drop.passFrom(0, LEAD_FRAMES / 2)

    drop.flush(AudioProcessor.StreamMetadata.DEFAULT)

    drop.passFrom(0, FRAMES) shouldBe (0 until FRAMES).toList()
  }

  @Test
  fun `a lead that is no whole frame cuts the nearest count of frames`() {
    val drop = configured(LEAD + 15.microseconds)

    drop.passFrom(0, FRAMES).first() shouldBe LEAD_FRAMES + 1
  }

  @Test
  fun `the length it reports is the input less the lead`() {
    AudioStartDrop(LEAD).getDurationAfterProcessorApplied(1_000_000L) shouldBe 1_000_000L - LEAD.inWholeMicroseconds
    AudioStartDrop(LEAD).getDurationAfterProcessorApplied(C.TIME_UNSET) shouldBe C.TIME_UNSET
  }

  private fun configured(lead: Duration = LEAD): AudioStartDrop =
    AudioStartDrop(lead).apply {
      configure(format)
      flush(AudioProcessor.StreamMetadata.DEFAULT)
    }

  // Queues the frames from [from] until [until], each holding its own index on every channel, and reads back the index
  // of every frame that comes out.
  private fun AudioStartDrop.passFrom(
    from: Int,
    until: Int,
  ): List<Int> {
    val bytes = ByteBuffer.allocateDirect((until - from) * format.bytesPerFrame).order(ByteOrder.nativeOrder())
    (from until until).forEach { frame -> repeat(CHANNELS) { bytes.putShort(frame.toShort()) } }
    bytes.flip()
    queueInput(bytes)
    val output = getOutput().order(ByteOrder.nativeOrder())
    val frames = mutableListOf<Int>()
    while (output.hasRemaining()) {
      frames += output.short.toInt()
      repeat(CHANNELS - 1) { output.short }
    }
    return frames
  }

  private companion object {
    const val RATE = 48_000
    const val CHANNELS = 2
    val LEAD = 25.milliseconds
    const val LEAD_FRAMES = 1_200
    const val FRAMES = 4_800
  }
}

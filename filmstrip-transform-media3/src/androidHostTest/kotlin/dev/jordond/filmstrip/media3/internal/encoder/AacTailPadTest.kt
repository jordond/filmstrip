package dev.jordond.filmstrip.media3.internal.encoder

import android.media.MediaCodec
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.transformer.Codec
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * How [AacTailPad] turns the end of a stream into silence and an empty end of stream, against a fake encoder whose
 * input buffers hold [BUFFER_BYTES] bytes each.
 */
class AacTailPadTest {
  @Test
  fun `the end of the stream is queued as the pad in silence then an empty end of stream`() {
    val encoder = FakeEncoder()
    val pad = AacTailPad(encoder, 0)

    feed(pad, FED_FRAMES)
    endStream(pad, END_US)
    drain(pad)

    val tail = encoder.queued.drop(FED_FRAMES / BUFFER_FRAMES + 1)
    val padFrames = aacTailPadFrames(FED_FRAMES.toLong())
    tail.sumOf { it.bytes.size } shouldBe padFrames * FRAME_BYTES
    tail.all { queued -> queued.bytes.all { it == 0.toByte() } } shouldBe true
    tail.dropLast(1).none { it.endOfStream } shouldBe true
    tail.last().endOfStream shouldBe true
    tail.last().bytes.size shouldBe 0
    var frames = 0L
    tail.forEach { queued ->
      queued.timeUs shouldBe END_US + frames * C.MICROS_PER_SECOND / SAMPLE_RATE
      frames += queued.bytes.size / FRAME_BYTES
    }
  }

  @Test
  fun `a pad leaves out the units the encoder writes past its input itself`() {
    val encoder = FakeEncoder()
    val pad = AacTailPad(encoder, 1)

    feed(pad, FED_FRAMES)
    endStream(pad, END_US)
    drain(pad)

    val tail = encoder.queued.drop(FED_FRAMES / BUFFER_FRAMES + 1)
    tail.sumOf { it.bytes.size }.toLong() shouldBe
      (aacTailPadFrames(FED_FRAMES.toLong()) - AAC_UNIT_FRAMES) * FRAME_BYTES
    tail.last().endOfStream shouldBe true
  }

  @Test
  fun `media3 is told no buffer is free once its end of stream is in`() {
    val encoder = FakeEncoder()
    val pad = AacTailPad(encoder, 0)
    feed(pad, FED_FRAMES)
    endStream(pad, END_US)
    drain(pad)
    val queued = encoder.queued.size

    pad.maybeDequeueInputBuffer(DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DISABLED)) shouldBe false

    encoder.queued.size shouldBe queued
  }

  @Test
  fun `a pad waits for the encoder to free a buffer then resumes`() {
    val encoder = FakeEncoder()
    val pad = AacTailPad(encoder, 0)
    feed(pad, FED_FRAMES)
    endStream(pad, END_US)
    encoder.hasFreeBuffer = false
    val queued = encoder.queued.size

    pad.maybeDequeueInputBuffer(DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DISABLED)) shouldBe false

    encoder.queued.size shouldBe queued
    encoder.queued.filter { it.endOfStream }.shouldBeEmpty()

    encoder.hasFreeBuffer = true
    drain(pad)

    val tail = encoder.queued.drop(FED_FRAMES / BUFFER_FRAMES + 1)
    tail.sumOf { it.bytes.size }.toLong() shouldBe aacTailPadFrames(FED_FRAMES.toLong()) * FRAME_BYTES
    encoder.queued.filter { it.endOfStream } shouldHaveSize 1
    encoder.queued.last().endOfStream shouldBe true
  }

  @Test
  fun `an end of stream that carries pcm fails`() {
    val pad = AacTailPad(FakeEncoder(), 0)
    feed(pad, FED_FRAMES)
    val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DISABLED)
    pad.maybeDequeueInputBuffer(buffer) shouldBe true
    checkNotNull(buffer.data).apply { put(1) }.flip()
    buffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM)

    assertFailsWith<IllegalStateException> { pad.queueInputBuffer(buffer) }
  }

  @Test
  fun `a pad is read in the configured pcm encoding and channels or in 16 bit when none is named`() {
    val cases =
      listOf(
        Triple(C.ENCODING_PCM_FLOAT, 2, FLOAT_FRAME_BYTES),
        Triple(Format.NO_VALUE, 2, FRAME_BYTES),
        Triple(C.ENCODING_PCM_16BIT, 1, MONO_FRAME_BYTES),
      )

    cases.forEach { (encoding, channels, frameBytes) ->
      val encoder = FakeEncoder(encoding, channels)
      val pad = AacTailPad(encoder, 0)
      feed(pad, FED_FRAMES, frameBytes)
      endStream(pad, END_US)
      drain(pad)

      withClue("encoding $encoding over $channels channels") {
        val padBytes = encoder.queued.drop(FED_FRAMES / (BUFFER_BYTES / frameBytes) + 1).sumOf { it.bytes.size }
        padBytes.toLong() shouldBe aacTailPadFrames(FED_FRAMES.toLong()) * frameBytes
        encoder.queued.last().endOfStream shouldBe true
      }
    }
  }

  @Test
  fun `a pad fills the stream to the access units a flushing encoder writes`() {
    // Access units c2.android.aac.encoder wrote on API 36 for each stream length, under, at and past a unit edge.
    val measured = mapOf(1_024L to 3L, 1_124L to 4L, 2_448L to 5L, 18_000L to 20L, 48_000L to 49L, 48_228L to 50L)

    measured.forEach { (frames, units) ->
      (frames + aacTailPadFrames(frames)) shouldBe units * AAC_UNIT_FRAMES
    }
  }

  private fun feed(
    pad: Codec,
    frames: Int,
    frameBytes: Int = FRAME_BYTES,
  ) {
    var fed = 0
    while (fed < frames) {
      val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DISABLED)
      pad.maybeDequeueInputBuffer(buffer) shouldBe true
      val data = checkNotNull(buffer.data)
      val count = minOf(data.remaining() / frameBytes, frames - fed)
      repeat(count * frameBytes) { data.put(1) }
      data.flip()
      buffer.timeUs = fed * C.MICROS_PER_SECOND / SAMPLE_RATE
      pad.queueInputBuffer(buffer)
      fed += count
    }
  }

  private fun endStream(
    pad: Codec,
    timeUs: Long,
  ) {
    val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DISABLED)
    pad.maybeDequeueInputBuffer(buffer) shouldBe true
    checkNotNull(buffer.data).flip()
    buffer.timeUs = timeUs
    buffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
    pad.queueInputBuffer(buffer)
  }

  private fun drain(pad: Codec) {
    repeat(DRAIN_TRIES) {
      pad.maybeDequeueInputBuffer(DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DISABLED))
    }
  }

  private class Queued(
    val bytes: ByteArray,
    val timeUs: Long,
    val endOfStream: Boolean,
  )

  private class FakeEncoder(
    private val pcmEncoding: Int = C.ENCODING_PCM_16BIT,
    private val channels: Int = 2,
  ) : Codec {
    val queued = mutableListOf<Queued>()
    var hasFreeBuffer = true
    private var ended = false

    override fun maybeDequeueInputBuffer(inputBuffer: DecoderInputBuffer): Boolean {
      check(!ended) { "a buffer was asked for after the end of stream" }
      if (!hasFreeBuffer) return false
      inputBuffer.data = ByteBuffer.allocate(BUFFER_BYTES)
      inputBuffer.clear()
      return true
    }

    override fun queueInputBuffer(inputBuffer: DecoderInputBuffer) {
      check(!ended) { "a buffer was queued after the end of stream" }
      val data = checkNotNull(inputBuffer.data)
      val bytes = ByteArray(data.remaining()).also { data.get(it) }
      queued += Queued(bytes, inputBuffer.timeUs, inputBuffer.isEndOfStream)
      ended = inputBuffer.isEndOfStream
      inputBuffer.data = null
    }

    override fun getConfigurationFormat(): Format =
      Format
        .Builder()
        .setSampleMimeType(MimeTypes.AUDIO_AAC)
        .setSampleRate(SAMPLE_RATE)
        .setChannelCount(channels)
        .setPcmEncoding(pcmEncoding)
        .build()

    override fun getInputFormat(): Format = throw UnsupportedOperationException()

    override fun getName(): String = "c2.android.aac.encoder"

    override fun getInputSurface(): Surface = throw UnsupportedOperationException()

    override fun signalEndOfInputStream(): Unit = throw UnsupportedOperationException()

    override fun getOutputFormat(): Format? = null

    override fun getOutputBuffer(): ByteBuffer? = null

    override fun getOutputBufferInfo(): MediaCodec.BufferInfo? = null

    override fun releaseOutputBuffer(render: Boolean): Unit = throw UnsupportedOperationException()

    override fun releaseOutputBuffer(renderPresentationTimeUs: Long): Unit = throw UnsupportedOperationException()

    override fun isEnded(): Boolean = ended

    override fun release() = Unit
  }

  private companion object {
    const val SAMPLE_RATE = 48_000
    const val FRAME_BYTES = 4
    const val FLOAT_FRAME_BYTES = 8
    const val MONO_FRAME_BYTES = 2
    const val BUFFER_FRAMES = 1_024
    const val BUFFER_BYTES = BUFFER_FRAMES * FRAME_BYTES
    const val FED_FRAMES = 18_000
    const val END_US = 375_000L
    const val DRAIN_TRIES = 10
  }
}

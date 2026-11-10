package dev.jordond.filmstrip.media3.internal

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.durationAfterEffectsUs
import com.google.common.collect.ImmutableList
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.audioFramesOf
import dev.jordond.filmstrip.edit.outputLengthOf
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * What [clipAudioSpeed] makes of a ramp decoded from an access unit ahead of the trim start, at a speed of one, the
 * slowest speed and two in between.
 */
@OptIn(InternalFilmstripApi::class)
class ClipAudioSpeedTest {
  @Test
  fun `speed one opens on the trim start and runs the trim`() = opensOnTheTrimStart(1.0)

  @Test
  fun `the slowest speed opens on the trim start and runs the shared length`() = opensOnTheTrimStart(1.0 / 32)

  @Test
  fun `a slow speed opens on the trim start and runs the shared length`() = opensOnTheTrimStart(0.3)

  @Test
  fun `a fast speed opens on the trim start and runs the shared length`() = opensOnTheTrimStart(1.7)

  @Test
  fun `media3 lays the next item after the shared length`() {
    listOf(1.0, 1.0 / 32, 0.3, 1.7).forEach { speed ->
      val length = outputLengthOf(TRIM, speed)
      val item =
        EditedMediaItem
          .Builder(MediaItem.EMPTY)
          .setRemoveVideo(true)
          .setEffects(Effects(clipAudioSpeed(LEAD, speed, length), emptyList()))
          .build()

      item.durationAfterEffectsUs((LEAD + TRIM).inWholeMicroseconds) shouldBe length.inWholeMicroseconds
    }
  }

  @Test
  fun `speed one runs no stretch`() {
    clipAudioSpeed(LEAD, 1.0, TRIM).map { it::class } shouldContainExactly
      listOf(AudioStartDrop::class, AudioLengthPin::class)
  }

  // A processor seeded at the pinned length reads a gain ramp at the end of its curve from the first frame.
  @Test
  fun `a processor run over the result starts counting from the trim start`() {
    listOf(1.0, 1.0 / 32, 0.3, 1.7).forEach { speed ->
      val seen = PositionSeen()
      val pipeline =
        AudioProcessingPipeline(
          ImmutableList.copyOf(clipAudioSpeed(LEAD, speed, outputLengthOf(TRIM, speed), listOf(seen))),
        )
      pipeline.configure(FORMAT)
      pipeline.flush(AudioProcessor.StreamMetadata.DEFAULT)

      seen.positionOffsetUs shouldBe 0L
    }
  }

  @Test
  fun `a stretch keeps the pitch of what it plays`() {
    val speed = 0.3
    val tone = ShortArray(LEAD_FRAMES + TRIM_FRAMES) { (sin(2 * PI * TONE_HZ * it / RATE) * PEAK).toInt().toShort() }

    val output = run(clipAudioSpeed(LEAD, speed, outputLengthOf(TRIM, speed)), tone)

    // The first half, so the silence a pin pads a short stretch out with is left out.
    val played = output.take(output.size / 2)
    val rises = played.zipWithNext().count { (before, after) -> before < 0 && after >= 0 }
    (rises * RATE.toDouble() / played.size) shouldBe (TONE_HZ plusOrMinus TONE_HZ * PITCH_TOLERANCE)
  }

  private fun opensOnTheTrimStart(speed: Double) {
    val length = outputLengthOf(TRIM, speed)
    val output = run(clipAudioSpeed(LEAD, speed, length), ramp(LEAD_FRAMES + TRIM_FRAMES))

    output.first() shouldBe LEAD_FRAMES.toShort()
    output.size.toLong() shouldBe audioFramesOf(length, RATE)
  }

  // A mono ramp whose frame at each position holds that position, so a frame's value names the source frame it is.
  private fun ramp(frames: Int): ShortArray = ShortArray(frames) { it.toShort() }

  private fun run(
    processors: List<AudioProcessor>,
    input: ShortArray,
  ): ShortArray {
    val pipeline = AudioProcessingPipeline(ImmutableList.copyOf(processors))
    pipeline.configure(FORMAT)
    pipeline.flush(AudioProcessor.StreamMetadata.DEFAULT)
    val output = mutableListOf<Short>()

    fun drain() {
      val bytes = pipeline.output.order(ByteOrder.nativeOrder())
      val samples = bytes.asShortBuffer()
      while (samples.hasRemaining()) output += samples.get()
      bytes.position(bytes.limit())
    }

    input.toList().chunked(CHUNK_FRAMES).forEach { chunk ->
      val bytes = ByteBuffer.allocateDirect(chunk.size * Short.SIZE_BYTES).order(ByteOrder.nativeOrder())
      chunk.forEach(bytes::putShort)
      bytes.flip()
      while (bytes.hasRemaining()) {
        pipeline.queueInput(bytes)
        drain()
      }
    }
    pipeline.queueEndOfStream()
    while (!pipeline.isEnded) drain()
    return output.toShortArray()
  }

  // Passes its input through and keeps the position media3 last flushed it at.
  private class PositionSeen : BaseAudioProcessor() {
    var positionOffsetUs: Long? = null

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat =
      inputAudioFormat

    override fun queueInput(inputBuffer: ByteBuffer) {
      val output = replaceOutputBuffer(inputBuffer.remaining())
      output.put(inputBuffer)
      output.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
      positionOffsetUs = streamMetadata.positionOffsetUs
    }
  }

  private companion object {
    const val RATE = 48_000
    val FORMAT = AudioProcessor.AudioFormat(RATE, 1, C.ENCODING_PCM_16BIT)
    val LEAD = 25.milliseconds
    const val LEAD_FRAMES = 1_200
    val TRIM = 100.milliseconds
    const val TRIM_FRAMES = 4_800
    const val CHUNK_FRAMES = 1_024
    const val TONE_HZ = 1_000.0
    const val PEAK = 16_000
    const val PITCH_TOLERANCE = 0.02
  }
}

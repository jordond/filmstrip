package dev.jordond.filmstrip.media3.internal

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.audio.AudioEffectSpec
import dev.jordond.filmstrip.audio.AudioKernel
import dev.jordond.filmstrip.audio.PlatformAudioEffect
import dev.jordond.filmstrip.effect.EffectId
import dev.jordond.filmstrip.export.AudioFormat
import dev.jordond.filmstrip.transform.internal.audio.KernelChain
import dev.jordond.filmstrip.transform.internal.audio.timelineFrameOf
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedAudioEffect
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * What [KernelAudioProcessor] hands its kernels and writes back: the timeline position of each block, the samples a
 * [KernelChain] makes of the same stream, the frames a kernel's latency holds back, and a swap of the effects.
 */
@OptIn(InternalFilmstripApi::class)
class KernelAudioProcessorTest {
  @Test
  fun `a stream opens on the timeline frame of its start`() {
    val seen = Positions()
    val processor = processorOf(listOf(seen), START)

    processor.pass(ramp(BLOCK * 2), BLOCK)

    val opens = timelineFrameOf(START, RATE)
    seen.positions shouldContainExactly listOf(opens, opens + BLOCK)
  }

  @Test
  fun `a flush part way into the stream lands on its start plus how far in it is`() {
    val seen = Positions()
    val processor = processorOf(listOf(seen), START)
    processor.pass(ramp(BLOCK), BLOCK)

    processor.flush(
      AudioProcessor.StreamMetadata
        .Builder()
        .setPositionOffsetUs(SEEK.inWholeMicroseconds)
        .build(),
    )
    processor.queue(ramp(BLOCK))

    seen.resets.last() shouldBe timelineFrameOf(START + SEEK, RATE)
    seen.positions.last() shouldBe timelineFrameOf(START + SEEK, RATE)
  }

  @Test
  fun `sixteen bit samples come out as the chain makes them, however the stream is split`() {
    val input = ramp(BLOCK * 3 + 7)
    val expected = input.copyOf()
    KernelChain(listOf(Halve()), CHANNELS, 0L).process(expected, expected.size / CHANNELS)

    listOf(BLOCK, 5, input.size / CHANNELS).forEach { split ->
      processorOf(listOf(Halve())).pass(input, split) shouldBe expected
    }
  }

  @Test
  fun `float samples run through the kernels as they are`() {
    val format = AudioProcessor.AudioFormat(RATE, CHANNELS, C.ENCODING_PCM_FLOAT)
    val processor = processorOf(listOf(Halve()), format = format)
    val input = ByteBuffer.allocateDirect(2 * CHANNELS * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
    listOf(1.5f, -0.5f, 0.25f, 0f).forEach(input::putFloat)
    input.flip()

    processor.queueInput(input)
    val output = processor.getOutput().asFloatBuffer()

    List(output.remaining()) { output.get() } shouldContainExactly listOf(0.75f, -0.25f, 0.125f, 0f)
  }

  @Test
  fun `a kernel with latency keeps the stream's length and its place`() {
    val input = ramp(BLOCK * 2)
    val processor = processorOf(listOf(Delay(LATENCY)))

    val output = processor.pass(input, BLOCK)

    output shouldBe input
    processor.isEnded shouldBe true
  }

  @Test
  fun `a stream that ends before it starts ends with nothing held`() {
    val processor = processorOf(listOf(Delay(LATENCY)))

    processor.queueEndOfStream()

    processor.getOutput().hasRemaining() shouldBe false
    processor.isEnded shouldBe true
  }

  @Test
  fun `each kernel is built for the format the stream has`() {
    val asked = mutableListOf<AudioFormat>()
    val effect =
      ResolvedAudioEffect(
        EffectId("test"),
        PlatformAudioEffect { format ->
          asked += format
          Halve()
        },
      )
    val mono = AudioProcessor.AudioFormat(MONO_RATE, 1, C.ENCODING_PCM_16BIT)

    KernelAudioProcessor(listOf(effect), Duration.ZERO).apply {
      configure(mono)
      flush(AudioProcessor.StreamMetadata.DEFAULT)
    }

    asked.map { it.sampleRate to it.channelCount } shouldContainExactly listOf(MONO_RATE to 1)
  }

  @Test
  fun `a swap runs the new effects from the next block, at the stream's place`() {
    val seen = Positions()
    val processor = processorOf(listOf(Halve()))
    val input = ramp(BLOCK)
    processor.queue(input)
    val before = processor.drain()

    processor.swap(listOf(effectOf(seen)))
    processor.queue(input)

    before shouldBe input.copyOf().also { KernelChain(listOf(Halve()), CHANNELS, 0L).process(it, BLOCK) }
    processor.drain() shouldBe input
    seen.positions shouldContainExactly listOf(BLOCK.toLong())
  }

  // A plan resolves its effects again on every change, so the swap is handed new objects for effects that did not
  // change. The stream keeps the kernels it has, and what they hold, and builds new ones only for a spec that differs.
  @Test
  fun `a swap to the effects a stream already runs keeps its kernels`() {
    var built = 0

    fun effect(amount: Float) =
      ResolvedAudioEffect(
        EffectId("test"),
        PlatformAudioEffect {
          built++
          Halve()
        },
        Amount(amount),
      )
    val processor =
      KernelAudioProcessor(listOf(effect(0.5f)), Duration.ZERO).apply {
        configure(FORMAT)
        flush(AudioProcessor.StreamMetadata.DEFAULT)
      }

    processor.swap(listOf(effect(0.5f)))
    val afterTheSame = built
    processor.swap(listOf(effect(0.6f)))

    afterTheSame shouldBe 1
    built shouldBe 2
  }

  @Test
  fun `a swap ahead of the first flush is what the stream opens on`() {
    val processor = KernelAudioProcessor(listOf(effectOf(Halve())), Duration.ZERO)

    processor.swap(emptyList())
    processor.configure(FORMAT)
    processor.flush(AudioProcessor.StreamMetadata.DEFAULT)

    val input = ramp(BLOCK)
    processor.pass(input, BLOCK) shouldBe input
  }

  @Test
  fun `a flush ahead of the format builds no kernels`() {
    var built = 0
    val effect =
      ResolvedAudioEffect(
        EffectId("test"),
        PlatformAudioEffect {
          built++
          Halve()
        },
      )
    val processor = KernelAudioProcessor(listOf(effect), Duration.ZERO)

    processor.flush(AudioProcessor.StreamMetadata.DEFAULT)

    built shouldBe 0
    processor.configure(FORMAT)
    processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
    built shouldBe 1
  }

  @Test
  fun `an encoding that is neither sixteen bit nor float is refused`() {
    val processor = KernelAudioProcessor(listOf(effectOf(Halve())), Duration.ZERO)

    assertFailsWith<AudioProcessor.UnhandledAudioFormatException> {
      processor.configure(AudioProcessor.AudioFormat(RATE, CHANNELS, C.ENCODING_PCM_24BIT))
    }
  }

  @Test
  fun `no effects lower to no processor`() {
    kernelProcessors(emptyList(), START) shouldBe emptyList()
  }

  private fun processorOf(
    kernels: List<AudioKernel>,
    start: Duration = Duration.ZERO,
    format: AudioProcessor.AudioFormat = FORMAT,
  ): KernelAudioProcessor =
    KernelAudioProcessor(kernels.map(::effectOf), start).apply {
      configure(format)
      flush(AudioProcessor.StreamMetadata.DEFAULT)
    }

  private fun effectOf(kernel: AudioKernel) = ResolvedAudioEffect(EffectId("test"), PlatformAudioEffect { kernel })

  // Interleaved stereo whose samples count up, so a sample's value names where in the stream it came from.
  private fun ramp(frames: Int): ShortArray = ShortArray(frames * CHANNELS) { (it + 1).toShort() }

  private fun KernelAudioProcessor.queue(samples: ShortArray) {
    val bytes = ByteBuffer.allocateDirect(samples.size * Short.SIZE_BYTES).order(ByteOrder.nativeOrder())
    samples.forEach(bytes::putShort)
    bytes.flip()
    queueInput(bytes)
  }

  private fun KernelAudioProcessor.drain(): ShortArray {
    val output = getOutput().order(ByteOrder.nativeOrder())
    val samples = output.asShortBuffer()
    val read = ShortArray(samples.remaining()).also(samples::get)
    output.position(output.limit())
    return read
  }

  // Everything the processor writes for [input] handed over [split] frames at a time and then ended.
  private fun KernelAudioProcessor.pass(
    input: ShortArray,
    split: Int,
  ): ShortArray {
    val output = mutableListOf<Short>()
    input.toList().chunked(split * CHANNELS).forEach { chunk ->
      queue(chunk.toShortArray())
      output += drain().toList()
    }
    queueEndOfStream()
    while (!isEnded) output += drain().toList()
    return output.toShortArray()
  }

  private data class Amount(
    val amount: Float,
  ) : AudioEffectSpec {
    override val id: EffectId get() = EffectId("test.amount")
  }

  // Leaves the samples alone and keeps every position it was handed.
  private class Positions : AudioKernel() {
    val positions = mutableListOf<Long>()
    val resets = mutableListOf<Long>()

    override fun process(
      buffer: FloatArray,
      frames: Int,
      position: Long,
    ) {
      positions += position
    }

    override fun reset(position: Long) {
      resets += position
    }
  }

  private class Halve : AudioKernel() {
    override fun process(
      buffer: FloatArray,
      frames: Int,
      position: Long,
    ) {
      for (index in 0 until frames * CHANNELS) buffer[index] = buffer[index] / 2
    }
  }

  // Hands back what it was given [frames] frames late, which is what a kernel with that latency does.
  private class Delay(
    private val frames: Int,
  ) : AudioKernel() {
    private val held = FloatArray(frames * CHANNELS)
    private var at = 0

    override val latencyFrames: Int get() = frames

    override fun process(
      buffer: FloatArray,
      frames: Int,
      position: Long,
    ) {
      for (index in 0 until frames * CHANNELS) {
        val delayed = held[at]
        held[at] = buffer[index]
        buffer[index] = delayed
        at = if (at + 1 == held.size) 0 else at + 1
      }
    }

    override fun reset(position: Long) {
      held.fill(0f)
      at = 0
    }
  }

  private companion object {
    const val RATE = 48_000
    const val MONO_RATE = 44_100
    const val CHANNELS = 2
    const val BLOCK = 256
    const val LATENCY = 100
    val FORMAT = AudioProcessor.AudioFormat(RATE, CHANNELS, C.ENCODING_PCM_16BIT)
    val START = 1_500.milliseconds
    val SEEK = 250.milliseconds
  }
}

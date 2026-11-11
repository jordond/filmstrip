package dev.jordond.filmstrip.transform.internal.audio

import dev.jordond.filmstrip.audio.AudioKernel
import dev.jordond.filmstrip.edit.audioFramesOf
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How [KernelChain] hosts kernels: the position each is handed, the latency it takes out, the swap, and the 16-bit
 * path. Every backend runs its kernels through this, so these are the figures they all agree on.
 */
class KernelChainTest {
  @Test
  fun `a time lands on the nearest frame of the timeline`() {
    timelineFrameOf(1.seconds, 48_000) shouldBe 48_000L
    timelineFrameOf(1_500.milliseconds, 44_100) shouldBe 66_150L
    timelineFrameOf(2_750.milliseconds, 48_000) shouldBe audioFramesOf(2_750.milliseconds, 48_000)
    // 30 microseconds is 1.44 frames at 48 kHz and 34 is 1.632, either side of the half.
    timelineFrameOf(30.microseconds, 48_000) shouldBe 1L
    timelineFrameOf(34.microseconds, 48_000) shouldBe 2L
  }

  @Test
  fun `a kernel is handed the timeline frame each block starts on`() {
    val chain = KernelChain(listOf(WritesPosition(channels = 2)), channels = 2, start = 1_000)

    val out = chain.feed(silence(363, channels = 2), splits = listOf(256, 100, 7), channels = 2)

    out shouldBe FloatArray(363 * 2) { 1_000f + it / 2 }
  }

  @Test
  fun `a reset moves the position and the next block starts there`() {
    val chain = KernelChain(listOf(WritesPosition()), channels = 1, start = 0)
    chain.feed(silence(300), splits = listOf(300))

    chain.reset(5_000)

    chain.feed(silence(4), splits = listOf(4)) shouldBe floatArrayOf(5_000f, 5_001f, 5_002f, 5_003f)
  }

  @Test
  fun `any split of the stream into blocks gives the same samples`() {
    val input = ramp(1_000, channels = 2)
    val whole = newMixedChain().feed(input, splits = listOf(1_000), channels = 2)

    listOf(
      List(1_000) { 1 },
      listOf(7, 64, 1, 500, 428),
      listOf(3, 2, 995),
      listOf(999, 1),
    ).forEach { splits ->
      newMixedChain().feed(input, splits, channels = 2) shouldBe whole
    }
  }

  @Test
  fun `latency comes out of the head and the tail is drained so the stream keeps its length and its place`() {
    val input = ramp(1_000)
    val chain = KernelChain(listOf(Delay(48)), channels = 1, start = 0)

    chain.latencyFrames shouldBe 48
    chain.feed(input, splits = listOf(100, 400, 500)) shouldBe input
  }

  @Test
  fun `a stream shorter than the latency still comes out whole`() {
    val input = ramp(10)

    KernelChain(listOf(Delay(48)), channels = 1, start = 0).feed(input, splits = listOf(4, 6), drainBy = 5) shouldBe
      input
  }

  @Test
  fun `a kernel behind a delay is handed the position of the frames it is given`() {
    val seen = RecordsPositions()
    val chain = KernelChain(listOf(Delay(5), seen, Delay(3)), channels = 1, start = 200)

    val out = chain.feed(ramp(40), splits = listOf(16, 24))

    out shouldBe ramp(40)
    chain.latencyFrames shouldBe 8
    // The ramp's frame n sits at timeline frame 200 + n, and reaches this kernel five frames late.
    seen.pairs.filter { (position, _) -> position in 200L until 240L } shouldContainExactly
      List(40) { 200L + it to it + 1f }
    seen.pairs.first().first shouldBe 195L
  }

  @Test
  fun `a swap takes effect at the next block and never inside one`() {
    lateinit var chain: KernelChain
    val swapsMidBlock =
      object : AudioKernel() {
        override fun process(
          buffer: FloatArray,
          frames: Int,
          position: Long,
        ) {
          if (position == 0L) chain.swap(listOf(Gain(3f)))
        }
      }
    chain = KernelChain(listOf(swapsMidBlock, Gain(2f)), channels = 1, start = 0)

    val first = FloatArray(4) { 1f }.also { chain.process(it, 4) }
    val second = FloatArray(4) { 1f }.also { chain.process(it, 4) }

    first shouldBe FloatArray(4) { 2f }
    second shouldBe FloatArray(4) { 3f }
  }

  @Test
  fun `a swapped in kernel is reset to where the stream is`() {
    val swapped = RecordsPositions()
    val chain = KernelChain(listOf(Gain(1f)), channels = 1, start = 100)
    chain.process(FloatArray(32), 32)

    chain.swap(listOf(swapped))
    chain.process(FloatArray(8), 8)

    swapped.resets shouldContainExactly listOf(132L)
    swapped.pairs.first().first shouldBe 132L
  }

  @Test
  fun `a swap to kernels with less latency keeps the stream in place and whole`() {
    val input = ramp(200)
    val chain = KernelChain(listOf(Delay(8)), channels = 1, start = 0)
    val out = mutableListOf<Float>()

    out += chain.push(input.copyOfRange(0, 100))
    chain.swap(listOf(Gain(1f)))
    out += chain.push(input.copyOfRange(100, 200))
    out += chain.drained()

    out.size shouldBe 200
    chain.latencyFrames shouldBe 8
    // The frames the old kernel still held at the swap are lost with it, and everything after is where it belongs.
    out.subList(0, 92) shouldBe input.toList().subList(0, 92)
    out.subList(92, 100) shouldBe List(8) { 0f }
    out.subList(100, 200) shouldBe input.toList().subList(100, 200)
  }

  @Test
  fun `a swap to kernels with more latency keeps the stream in place and whole`() {
    val input = ramp(200)
    val chain = KernelChain(listOf(Gain(1f)), channels = 1, start = 0)
    val out = mutableListOf<Float>()

    out += chain.push(input.copyOfRange(0, 100))
    chain.swap(listOf(Delay(8)))
    out += chain.push(input.copyOfRange(100, 200))
    out += chain.drained()

    out.size shouldBe 200
    chain.latencyFrames shouldBe 8
    out.subList(0, 100) shouldBe input.toList().subList(0, 100)
    // The new kernel starts empty, so its first eight frames out are what it was primed with, and they are the ones
    // left out.
    out.subList(100, 200) shouldBe input.toList().subList(100, 200)
  }

  @Test
  fun `a reset starts the stream again with its latency taken out`() {
    val chain = KernelChain(listOf(Delay(4)), channels = 1, start = 0)
    chain.push(ramp(10))

    chain.reset(1_000)

    chain.feed(ramp(10), splits = listOf(10)) shouldBe ramp(10)
  }

  // What a seek costs is the kernel's own memory and nothing the chain adds: once the kernel has been handed as many
  // frames as it remembers, the stream is the one an unbroken run plays.
  @Test
  fun `a stream is back to what an unbroken run plays once a kernel's memory has passed a reset`() {
    val input = ramp(1_000)
    val unbroken = KernelChain(listOf(Delay(16), Echo(MEMORY)), channels = 1, start = 0).feed(input, listOf(1_000))
    val chain = KernelChain(listOf(Delay(16), Echo(MEMORY)), channels = 1, start = 0)
    chain.push(input.copyOfRange(0, 300))

    chain.reset(SEEK.toLong())
    val after = chain.feed(input.copyOfRange(SEEK, 1_000), splits = listOf(100, 200))

    after.size shouldBe 1_000 - SEEK
    after.drop(MEMORY) shouldBe unbroken.drop(SEEK + MEMORY)
    after.take(MEMORY) shouldNotBe unbroken.toList().subList(SEEK, SEEK + MEMORY)
  }

  @Test
  fun `a chain that does not compensate hands every frame back late by the latency`() {
    val input = ramp(200)
    val chain = KernelChain(listOf(Delay(48)), channels = 1, start = 0, compensates = false)

    val out = chain.feed(input, splits = listOf(30, 70, 100))

    chain.latencyFrames shouldBe 0
    out.size shouldBe 200
    out.take(48) shouldBe List(48) { 0f }
    out.drop(48) shouldBe input.take(152)
  }

  @Test
  fun `a chain that does not compensate hands every frame back across a swap`() {
    val chain = KernelChain(listOf(Delay(48)), channels = 1, start = 0, compensates = false)
    val head = chain.push(ramp(100))

    chain.swap(listOf(Delay(8)))
    val tail = chain.push(ramp(100))

    head.size shouldBe 100
    tail.take(8) shouldBe List(8) { 0f }
    tail.drop(8) shouldBe ramp(92).toList()
    chain.drained() shouldBe emptyList()
  }

  @Test
  fun `sixteen bit samples pass through unchanged and a kernel reads them as floats`() {
    val samples = shortArrayOf(0, 1, -1, 12_345, -12_345, Short.MAX_VALUE, Short.MIN_VALUE, 16_384)
    val seen = RecordsPositions()
    val chain = KernelChain(listOf(seen), channels = 2, start = 0, maxFrames = 2)

    val buffer = samples.copyOf()
    chain.process(buffer, 4) shouldBe 4

    buffer.toList() shouldBe samples.toList()
    seen.samples shouldContainExactly samples.map { it / 32_768f }
  }

  @Test
  fun `sixteen bit samples pushed past full scale are clamped`() {
    val chain = KernelChain(listOf(Gain(4f)), channels = 1, start = 0)
    val buffer = shortArrayOf(20_000, -20_000, 100)

    chain.process(buffer, 3)

    buffer.toList() shouldBe listOf<Short>(Short.MAX_VALUE, Short.MIN_VALUE, 400)
  }

  @Test
  fun `sixteen bit streams take the latency out the way float ones do`() {
    val input = ShortArray(100) { (it * 300).toShort() }
    val chain = KernelChain(listOf(Delay(16)), channels = 1, start = 0)
    val out = mutableListOf<Short>()

    val head = input.copyOf()
    val written = chain.process(head, 100)
    out += head.take(written)
    val tail = ShortArray(64)
    while (true) {
      val drained = chain.drain(tail, 64)
      if (drained == 0) break
      out += tail.take(drained)
    }

    written shouldBe 84
    out shouldBe input.toList()
  }

  @Test
  fun `no kernels leave the stream as it was`() {
    val input = ramp(50, channels = 2)

    KernelChain(emptyList(), channels = 2, start = 0).feed(input, splits = listOf(20, 30), channels = 2) shouldBe input
  }

  @Test
  fun `a stream needs a channel and a latency cannot be negative`() {
    assertFailsWith<IllegalArgumentException> { KernelChain(emptyList(), channels = 0, start = 0) }
    assertFailsWith<IllegalArgumentException> { KernelChain(listOf(Delay(-1)), channels = 1, start = 0) }
  }

  private fun newMixedChain() =
    KernelChain(listOf(Delay(5, channels = 2), AddsPosition(), Gain(0.5f), Delay(11, channels = 2)), 2, start = 777)

  // Feeds [input] in blocks of [splits] frames, then drains [drainBy] frames at a time, and answers everything out.
  private fun KernelChain.feed(
    input: FloatArray,
    splits: List<Int>,
    channels: Int = 1,
    drainBy: Int = 64,
  ): FloatArray {
    val out = mutableListOf<Float>()
    var at = 0
    splits.forEach { frames ->
      out += push(input.copyOfRange(at * channels, (at + frames) * channels), channels)
      at += frames
    }
    out += drained(channels, drainBy)
    return out.toFloatArray()
  }

  private fun KernelChain.push(
    block: FloatArray,
    channels: Int = 1,
  ): List<Float> {
    val written = process(block, block.size / channels)
    return block.take(written * channels)
  }

  private fun KernelChain.drained(
    channels: Int = 1,
    by: Int = 64,
  ): List<Float> {
    val out = mutableListOf<Float>()
    val tail = FloatArray(by * channels)
    while (true) {
      val frames = drain(tail, by)
      if (frames == 0) return out
      out += tail.take(frames * channels)
    }
  }

  private fun silence(
    frames: Int,
    channels: Int = 1,
  ) = FloatArray(frames * channels)

  // Frame n holds n + 1 on every channel, so no frame of it reads as silence.
  private fun ramp(
    frames: Int,
    channels: Int = 1,
  ) = FloatArray(frames * channels) { it / channels + 1f }

  private class WritesPosition(
    private val channels: Int = 1,
  ) : AudioKernel() {
    override fun process(
      buffer: FloatArray,
      frames: Int,
      position: Long,
    ) {
      for (frame in 0 until frames) {
        for (channel in 0 until channels) buffer[frame * channels + channel] = (position + frame).toFloat()
      }
    }
  }

  private class AddsPosition : AudioKernel() {
    override fun process(
      buffer: FloatArray,
      frames: Int,
      position: Long,
    ) {
      for (frame in 0 until frames) {
        buffer[frame * 2] += (position + frame).toFloat()
        buffer[frame * 2 + 1] -= (position + frame).toFloat()
      }
    }
  }

  private class Gain(
    private val gain: Float,
  ) : AudioKernel() {
    override fun process(
      buffer: FloatArray,
      frames: Int,
      position: Long,
    ) {
      for (index in buffer.indices) buffer[index] *= gain
    }
  }

  // Delays the stream by its latency, the way a kernel that looks ahead does.
  private class Delay(
    frames: Int,
    private val channels: Int = 1,
  ) : AudioKernel() {
    private val held = FloatArray(maxOf(frames, 0) * channels)
    private var at = 0

    override val latencyFrames: Int = frames

    override fun process(
      buffer: FloatArray,
      frames: Int,
      position: Long,
    ) {
      if (held.isEmpty()) return
      for (index in 0 until frames * channels) {
        val out = held[at]
        held[at] = buffer[index]
        buffer[index] = out
        at = (at + 1) % held.size
      }
    }

    override fun reset(position: Long) {
      held.fill(0f)
      at = 0
    }
  }

  // Adds what it was handed [memory] frames ago at half level, so it remembers exactly that many frames.
  private class Echo(
    private val memory: Int,
  ) : AudioKernel() {
    private val held = FloatArray(memory)
    private var at = 0

    override fun process(
      buffer: FloatArray,
      frames: Int,
      position: Long,
    ) {
      for (index in 0 until frames) {
        val dry = buffer[index]
        buffer[index] = dry + held[at] * 0.5f
        held[at] = dry
        at = (at + 1) % held.size
      }
    }

    override fun reset(position: Long) {
      held.fill(0f)
      at = 0
    }
  }

  // Records the position of every mono frame it is handed with the sample there, and every reset.
  private class RecordsPositions : AudioKernel() {
    val pairs = mutableListOf<Pair<Long, Float>>()
    val samples = mutableListOf<Float>()
    val resets = mutableListOf<Long>()

    override fun process(
      buffer: FloatArray,
      frames: Int,
      position: Long,
    ) {
      for (frame in 0 until frames) pairs += position + frame to buffer[frame]
      samples += buffer.toList()
    }

    override fun reset(position: Long) {
      resets += position
    }
  }

  private companion object {
    const val MEMORY = 37
    const val SEEK = 700
  }
}

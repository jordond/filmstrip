package dev.jordond.filmstrip.media3.internal

import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.util.SpeedProviderUtil.SpeedProviderMapper
import androidx.media3.effect.GlShaderProgram
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.maps.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The effect that hands a frame on again at the slots after the one it is written on.
 */
class SlotRepeatTest {
  private val rig = Rig()

  @Test
  fun `a frame with later slots is handed on at its own time and at each of them`() {
    val program = programOf(mapOf(0L to listOf(33_333L, 66_666L)))

    program.queueInputFrame(GL, FRAME, 0L)

    rig.outputs shouldBe listOf(0L, 33_333L, 66_666L)
  }

  @Test
  fun `a frame is keyed by how far it sits past where the item starts`() {
    val program = programOf(mapOf(0L to listOf(33_333L), 100_000L to listOf(133_333L)), startUs = 7_000_000L)

    program.queueInputFrame(GL, FRAME, 7_000_000L)
    program.releaseOutputFrame(FRAME)
    program.releaseOutputFrame(FRAME)
    program.queueInputFrame(GL, FRAME, 7_100_000L)

    rig.outputs shouldBe listOf(7_000_000L, 7_033_333L, 7_100_000L, 7_133_333L)
  }

  @Test
  fun `the frame is given back upstream only after the last time it was handed on at is released`() {
    val program = programOf(mapOf(0L to listOf(33_333L, 66_666L)))
    program.queueInputFrame(GL, FRAME, 0L)

    program.releaseOutputFrame(FRAME)
    program.releaseOutputFrame(FRAME)
    rig.processed.shouldBeEmpty()
    program.releaseOutputFrame(FRAME)

    rig.processed shouldBe listOf(FRAME)
  }

  @Test
  fun `a frame the map does not name is handed on once`() {
    val program = programOf(mapOf(40_000L to listOf(60_000L)))

    program.queueInputFrame(GL, FRAME, 5_000L)
    program.queueInputFrame(GL, FRAME, 25_000L)

    rig.outputs shouldBe listOf(5_000L, 25_000L)
  }

  @Test
  fun `an empty map hands every frame on once`() {
    val program = programOf(emptyMap())

    program.queueInputFrame(GL, FRAME, 0L)
    program.releaseOutputFrame(FRAME)

    rig.outputs shouldBe listOf(0L)
    rig.processed shouldBe listOf(FRAME)
  }

  @Test
  fun `a frame written within the tolerance of its key still takes its repeats`() {
    val program = programOf(mapOf(0L to listOf(33_333L), 100_000L to listOf(133_333L)), toleranceUs = 3L)

    program.queueInputFrame(GL, FRAME, 100_002L)

    rig.outputs shouldBe listOf(100_002L, 133_333L)
  }

  @Test
  fun `a frame further from every key than the tolerance is handed on once`() {
    val program = programOf(mapOf(0L to listOf(33_333L), 100_000L to listOf(133_333L)), toleranceUs = 3L)

    program.queueInputFrame(GL, FRAME, 100_010L)

    rig.outputs shouldBe listOf(100_010L)
  }

  // A seek or a redraw hands the effect a frame from the middle of the item, so its repeats cannot depend on having
  // seen the first.
  @Test
  fun `a frame from the middle of an item after a flush takes its own repeats`() {
    val program = programOf(mapOf(0L to listOf(33_333L), 100_000L to listOf(133_333L)), startUs = 5_000_000L)
    program.queueInputFrame(GL, FRAME, 5_000_000L)
    program.flush()

    program.queueInputFrame(GL, FRAME, 5_100_000L)

    rig.flushed shouldBe 1
    rig.outputs shouldBe listOf(5_000_000L, 5_033_333L, 5_100_000L, 5_133_333L)
  }

  // Each sample is held for longer than a float speed can land a stretch of exactly, so the pace writes the next one
  // a step off its key, and the key still has to be found.
  @Test
  fun `a hold of seconds is matched where the pace writes its next sample`() {
    val frames = framesOf(sourceRate = 1, rate = 30, speed = SLOW)
    val mapper = SpeedProviderMapper(SlotPace(frames, SLOW))
    val written = frames.shown.map { (_, position) -> mapper.getAdjustedTimeUs(position) }
    written.zip(frames.shown).any { (at, shown) -> at != shown.first } shouldBe true
    val program = programOf(frames.repeatsUs, toleranceUs = slotRepeatToleranceUs(30))

    written.forEach { at ->
      val before = rig.outputs.size
      program.queueInputFrame(GL, FRAME, at)
      repeat(rig.outputs.size - before) { program.releaseOutputFrame(FRAME) }
    }

    frames.repeatsUs.shouldNotBeEmpty()
    frames.shown.indices.forEach { index ->
      val repeats = frames.repeatsUs[frames.shown[index].first].orEmpty()
      repeats.forEachIndexed { which, repeat ->
        val at = rig.outputs[rig.outputs.indexOf(written[index]) + 1 + which]
        at shouldBe repeat
      }
    }
    rig.outputs.size shouldBe frames.shown.size + frames.repeatsUs.values.sumOf { it.size }
    rig.outputs.first().shouldBeInRange(0L..0L)
  }

  @Test
  fun `a pinned slot one of a frame's slots is handed on alone`() {
    val pin = SlotPin().apply { atUs = 33_333L }
    val program = programOf(mapOf(0L to listOf(33_333L, 66_666L)), pin = pin)

    program.queueInputFrame(GL, FRAME, 0L)
    program.releaseOutputFrame(FRAME)

    rig.outputs shouldBe listOf(33_333L)
    rig.processed shouldBe listOf(FRAME)
  }

  @Test
  fun `a pinned slot that no slot of the frame is leaves it handed on at each of its slots`() {
    val pin = SlotPin().apply { atUs = 500_000L }
    val program = programOf(mapOf(0L to listOf(33_333L)), pin = pin)

    program.queueInputFrame(GL, FRAME, 0L)

    rig.outputs shouldBe listOf(0L, 33_333L)
  }

  @Test
  fun `a pin let go leaves every frame handed on at each of its slots`() {
    val pin = SlotPin().apply { atUs = 33_333L }
    val program = programOf(mapOf(0L to listOf(33_333L)), pin = pin)
    pin.atUs = null

    program.queueInputFrame(GL, FRAME, 0L)

    rig.outputs shouldBe listOf(0L, 33_333L)
  }

  @Test
  fun `the end of an item is passed on`() {
    val program = programOf(mapOf(0L to listOf(33_333L)))

    program.signalEndOfCurrentInputStream()

    rig.ended shouldBe 1
  }

  @Test
  fun `two effects over the same repeats are equal and effects over other repeats are not`() {
    SlotRepeat(mapOf(0L to listOf(1L)), 0L, 1L) shouldBe SlotRepeat(mapOf(0L to listOf(1L)), 0L, 1L)
    SlotRepeat(mapOf(0L to listOf(1L)), 0L, 1L) shouldNotBe SlotRepeat(emptyMap(), 0L, 1L)
    SlotRepeat(mapOf(0L to listOf(1L)), 0L, 1L) shouldNotBe SlotRepeat(mapOf(0L to listOf(1L)), 5L, 1L)
  }

  private fun programOf(
    repeats: Map<Long, List<Long>>,
    startUs: Long = 0L,
    toleranceUs: Long = 0L,
    pin: SlotPin? = null,
  ): SlotRepeatProgram =
    SlotRepeatProgram(repeats, startUs, toleranceUs, pin).also { program ->
      program.setInputListener(rig.input)
      program.setOutputListener(rig.output)
    }

  private fun framesOf(
    sourceRate: Int,
    rate: Int,
    speed: Double,
  ): ShownFrames {
    val length = 3.seconds
    val clip = resolvedClip(sourceRate, length, speed)
    return checkNotNull(ShownFrames.of(clip, samplesAt(sourceRate, seconds = 6), rate))
  }

  private class Rig {
    val outputs = mutableListOf<Long>()
    val processed = mutableListOf<GlTextureInfo>()
    var ended = 0
    var flushed = 0

    val input =
      object : GlShaderProgram.InputListener {
        override fun onInputFrameProcessed(inputTexture: GlTextureInfo) {
          processed += inputTexture
        }

        override fun onFlush() {
          flushed++
        }
      }

    val output =
      object : GlShaderProgram.OutputListener {
        override fun onOutputFrameAvailable(
          outputTexture: GlTextureInfo,
          presentationTimeUs: Long,
        ) {
          outputs += presentationTimeUs
        }

        override fun onCurrentOutputStreamEnded() {
          ended++
        }
      }
  }

  private companion object {
    // A sample held 10 s, past where a float speed lands a stretch exactly.
    const val SLOW = 0.1
    val GL = mockk<GlObjectsProvider>()
    val FRAME = GlTextureInfo(7, -1, -1, 640, 360)
  }
}

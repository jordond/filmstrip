package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.FilterBranch
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.support.FILTER_RATE
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.channelAt
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit

/**
 * A clip opening between two slots, under an effect that paints the composition time it reads.
 *
 * The clip opens once past its first slot and once ahead of it. It is read plain from a source at the output's rate and
 * retimed from one at twice it, and every frame has to read the time of the slot it lands on, in the preview and in the
 * export.
 */
class FfmpegEffectClockMediaTest {
  private val resolver =
    EffectResolver { spec, _, _ ->
      if (spec is ClockRamp) EffectResolution.Resolved(PlatformEffect(fragment())) else null
    }
  private val draw = FilterFrames(resolver)

  @Test
  fun `a plain read lands each frame on its slot time`() =
    runBlocking<Unit> {
      val plan = planOpening(PAST_SLOT, rate = "$FILTER_RATE")
      draw.graphOf(plan) shouldNotContain RETIMED_TICKS

      assertReadsSlotTimes(plan, PAST_SLOT_SLOTS)
    }

  @Test
  fun `a retimed read lands each frame on its slot time`() =
    runBlocking<Unit> {
      val plan = planOpening(PAST_SLOT, rate = "${FILTER_RATE * 2}")
      draw.graphOf(plan) shouldContain RETIMED_TICKS

      assertReadsSlotTimes(plan, PAST_SLOT_SLOTS)
    }

  @Test
  fun `the plain read's export reads the slot times`() =
    runBlocking<Unit> { assertExportReadsSlotTimes(planOpening(PAST_SLOT, rate = "$FILTER_RATE"), PAST_SLOT_SLOTS) }

  @Test
  fun `the retimed read's export reads the slot times`() =
    runBlocking<Unit> {
      assertExportReadsSlotTimes(planOpening(PAST_SLOT, rate = "${FILTER_RATE * 2}"), PAST_SLOT_SLOTS)
    }

  @Test
  fun `a plain read opening ahead of its slot lands each frame on its slot time`() =
    runBlocking<Unit> {
      val plan = planOpening(AHEAD_OF_SLOT, rate = "$FILTER_RATE")
      draw.graphOf(plan) shouldNotContain RETIMED_TICKS

      assertReadsSlotTimes(plan, AHEAD_OF_SLOT_SLOTS)
    }

  @Test
  fun `a retimed read opening ahead of its slot lands each frame on its slot time`() =
    runBlocking<Unit> {
      val plan = planOpening(AHEAD_OF_SLOT, rate = "${FILTER_RATE * 2}")
      draw.graphOf(plan) shouldContain PADDED_TAIL

      assertReadsSlotTimes(plan, AHEAD_OF_SLOT_SLOTS)
    }

  @Test
  fun `the plain read's export opening ahead of its slot reads the slot times`() =
    runBlocking<Unit> {
      assertExportReadsSlotTimes(planOpening(AHEAD_OF_SLOT, rate = "$FILTER_RATE"), AHEAD_OF_SLOT_SLOTS)
    }

  @Test
  fun `the retimed read's export opening ahead of its slot reads the slot times`() =
    runBlocking<Unit> {
      assertExportReadsSlotTimes(planOpening(AHEAD_OF_SLOT, rate = "${FILTER_RATE * 2}"), AHEAD_OF_SLOT_SLOTS)
    }

  private suspend fun assertReadsSlotTimes(
    plan: Composition,
    slots: List<Int>,
  ) {
    slots.forEach { slot ->
      val read = draw.planesAt(plan, slot).luma(WIDTH / 2, HEIGHT / 2)
      withClue("slot $slot read $read, expected ${codeAt(slot)}") {
        abs(read - codeAt(slot)) shouldBeLessThanOrEqual 1
      }
    }
  }

  private suspend fun assertExportReadsSlotTimes(
    plan: Composition,
    slots: List<Int>,
  ) {
    val frames = draw.exportedFrames(plan, slots, ExportSpec())
    frames.zip(slots).forEach { (frame, slot) ->
      val expected = ((codeAt(slot) - LUMA_BLACK) * FULL_CODE / LUMA_CODES).roundToInt()
      val read = frame.channelAt(WIDTH, WIDTH / 2, HEIGHT / 2, 0)
      withClue("slot $slot read $read, expected $expected") {
        abs(read - expected) shouldBeLessThanOrEqual EXPORT_TOLERANCE
      }
    }
  }

  private fun planOpening(
    at: Duration,
    rate: String,
  ): Composition {
    val lead = draw.clipOf(WIDTH, HEIGHT, { GREY }, seconds = 2)
    val clip = draw.clipOf(WIDTH, HEIGHT, { GREY }, listOf(ClockRamp), rate = rate, container = "mov")
    return draw.composition(listOf(Clip(lead.source, trim = TimeRange(Duration.ZERO, at)), clip))
  }

  // The code the ramp paints on the slot's own time.
  private fun codeAt(slot: Int): Int {
    val seconds = outputSlotTime(slot.toLong(), FILTER_RATE).toDouble(DurationUnit.SECONDS)
    return (LUMA_BLACK + (seconds - RAMP_OPENS) * RAMP_SLOPE).roundToInt()
  }

  private fun fragment(): FilterFragment {
    val code = "round(clip($LUMA_BLACK+(T-$RAMP_OPENS)*$RAMP_SLOPE,$LUMA_BLACK,$LUMA_WHITE))"
    return FilterFragment(
      readsCompositionClock = true,
      branches =
        listOf(
          FilterBranch(
            listOf(FilterBranch.INPUT),
            listOf(FilterNode("geq", "lum" to code, "cb" to "128", "cr" to "128")),
            listOf("out"),
          ),
        ),
    )
  }

  private object ClockRamp : EffectSpec {
    override val id: String = "test.clock-ramp"
  }

  private companion object {
    const val WIDTH = 64
    const val HEIGHT = 36
    const val LUMA_BLACK = 16
    const val LUMA_WHITE = 235
    const val LUMA_CODES = 219.0
    const val FULL_CODE = 255.0
    const val RAMP_OPENS = 1.0
    const val RAMP_SLOPE = 300.0
    const val EXPORT_TOLERANCE = 2
    const val RETIMED_TICKS = "settb=expr=1/30000"
    const val PADDED_TAIL = "tpad=stop_mode=clone"
    val GREY = doubleArrayOf(0.5, 0.5, 0.5)

    // 12 ms past slot 30, so the clip's frames land 12 ms ahead of where it opens, 3.6 codes up the ramp.
    val PAST_SLOT = 1_012.milliseconds

    // 13.3 ms ahead of slot 31, so the clip's frames land 13.3 ms after where it opens, 4 codes down the ramp.
    val AHEAD_OF_SLOT = 1_020.milliseconds

    // Each opening's first slot and three in the middle of the ramp.
    val PAST_SLOT_SLOTS = listOf(30, 36, 40, 44)
    val AHEAD_OF_SLOT_SLOTS = listOf(31, 36, 40, 44)
  }
}

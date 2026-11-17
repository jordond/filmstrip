package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputLengthOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.edit.outputSlotsOf
import dev.jordond.filmstrip.edit.sourceOffsetAt
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

class NearestFrameTest {
  // 25 fps, so every half way point is a whole number of microseconds and a tie is exact.
  private val frame = 40.milliseconds
  private val times = (0..9).map { frame * it }

  @Test
  fun `a target a quarter of a frame past a frame takes that frame`() {
    nearestSourceFrame(times, frame * 4 + frame / 4) shouldBe 4
  }

  @Test
  fun `a target three quarters of a frame past a frame takes the one after it`() {
    nearestSourceFrame(times, frame * 4 + frame * 3 / 4) shouldBe 5
  }

  @Test
  fun `a target just past half way takes the later frame`() {
    nearestSourceFrame(times, frame * 4 + frame / 2 + 1.milliseconds) shouldBe 5
  }

  @Test
  fun `a target exactly half way takes the earlier frame`() {
    nearestSourceFrame(times, frame * 4 + frame / 2) shouldBe 4
  }

  // 30 fps in whole microseconds puts frames 31 and 32 at 1 033 333 and 1 066 666, so half way is 1 049 999.5 and no
  // target lands on it. A trim at half a frame read at 0.7 reaches 1 050 000.
  @Test
  fun `a target within a microsecond of half way takes the earlier frame`() {
    val thirtieths = (0..40).map { (it * 1_000_000L / 30).microseconds }
    val half = thirtieths[31] + (thirtieths[32] - thirtieths[31]) / 2

    listOf(-1, 0, 1).forEach { step ->
      nearestSourceFrame(thirtieths, half + step.microseconds) shouldBe 31
    }
    nearestSourceFrame(thirtieths, half + 2.microseconds) shouldBe 32
    nearestSourceFrame(thirtieths, half - 2.microseconds) shouldBe 31
  }

  @Test
  fun `a target two microseconds past an exact half way takes the later frame`() {
    nearestSourceFrame(times, frame * 4 + frame / 2 + 2.microseconds) shouldBe 5
    nearestSourceFrame(times, frame * 4 + frame / 2 + 1.microseconds) shouldBe 4
  }

  @Test
  fun `a target on a frame takes that frame`() {
    nearestSourceFrame(times, frame * 6) shouldBe 6
  }

  @Test
  fun `a target outside the frames takes the frame at that end`() {
    nearestSourceFrame(times, -frame) shouldBe 0
    nearestSourceFrame(times, frame * 20) shouldBe times.lastIndex
  }

  @Test
  fun `frames at uneven times are read by their own times`() {
    val uneven = listOf(Duration.ZERO, 10.milliseconds, 50.milliseconds, 60.milliseconds)

    nearestSourceFrame(uneven, 29.milliseconds) shouldBe 1
    nearestSourceFrame(uneven, 31.milliseconds) shouldBe 2
  }

  @Test
  fun `no frames at all is no frame`() {
    nearestSourceFrame(emptyList(), frame).shouldBeNull()
  }

  @Test
  fun `a slot's source time walks the trim at the clip's speed from where its span opens`() {
    val trimStart = 200.milliseconds
    val spanStart = outputSlotTime(15, 30)

    sourceTimeOfSlot(trimStart, spanStart, 1.5, 22, 30, trimStart) shouldBe
      trimStart + sourceOffsetAt(outputSlotTime(22, 30) - spanStart, 1.5)
  }

  @Test
  fun `a slot before the span of a clip that plays on reads back from the trim by the lead scaled by the speed`() {
    val spanStart = 475.milliseconds
    val lead = spanStart - outputSlotTime(14, 30)

    listOf(1.0 / 32, 0.5, 1.0, 1.5, 7.0, 32.0).forEach { speed ->
      sourceTimeOfSlot(585.milliseconds, spanStart, speed, 14, 30, runStart = 110.milliseconds) shouldBe
        585.milliseconds + sourceOffsetAt(-lead, speed)
    }
  }

  @Test
  fun `a slot before the span of a clip that plays on from nothing reads the trim's own start at every speed`() {
    listOf(1.0 / 32, 0.5, 1.0, 1.5, 7.0, 32.0).forEach { speed ->
      sourceTimeOfSlot(585.milliseconds, 475.milliseconds, speed, 14, 30, runStart = 585.milliseconds) shouldBe
        585.milliseconds
    }
  }

  @Test
  fun `a slot never reads before where the run it plays on from opens`() {
    val lead = 475.milliseconds - outputSlotTime(14, 30)

    listOf(0.5, 1.0, 4.0, 32.0).forEach { speed ->
      val unclamped = 585.milliseconds + sourceOffsetAt(-lead, speed)
      listOf(unclamped - 1.milliseconds, unclamped, unclamped + 1.milliseconds, 585.milliseconds).forEach { opens ->
        val read = sourceTimeOfSlot(585.milliseconds, 475.milliseconds, speed, 14, 30, runStart = opens)

        read shouldBe maxOf(opens, unclamped)
      }
    }
  }

  @Test
  fun `a slot on or past the span reads the same whatever run the clip plays on from`() {
    val spanStart = outputSlotTime(15, 30)

    listOf(0.5, 1.0, 2.0).forEach { speed ->
      listOf(15L, 16L, 22L).forEach { slot ->
        sourceTimeOfSlot(200.milliseconds, spanStart, speed, slot, 30, runStart = 50.milliseconds) shouldBe
          sourceTimeOfSlot(200.milliseconds, spanStart, speed, slot, 30, runStart = 200.milliseconds)
      }
    }
  }

  @Test
  fun `two clips of one source whose trims meet read every slot as the whole run does at every speed`() {
    val frameRate = 30
    listOf(0.5, 1.0, 2.0).forEach { speed ->
      listOf(0, 110, 117).forEach { startMs ->
        listOf(500, 475, 466, 483, 510).forEach { seamMs ->
          val start = startMs.milliseconds
          val seam = start + (seamMs.milliseconds - start) * speed
          val end = start + (1_000.milliseconds - start) * speed
          val whole = outputLengthOf(end - start, speed)
          val second = TimeRange.of(outputLengthOf(seam - start, speed), whole)
          outputSlotsOf(TimeRange.of(Duration.ZERO, whole), frameRate).forEach { slot ->
            val read =
              if (slot in outputSlotsOf(second, frameRate)) {
                sourceTimeOfSlot(seam, second.start, speed, slot, frameRate, runStart = start)
              } else {
                sourceTimeOfSlot(start, Duration.ZERO, speed, slot, frameRate, runStart = start)
              }
            read shouldBe start + sourceOffsetAt(outputSlotTime(slot, frameRate), speed)
          }
        }
      }
    }
  }
}

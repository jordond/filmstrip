package dev.jordond.filmstrip.player.contract

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.SetCompositionResult
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/**
 * Where a frame read back at a position lands, run by [PlayerEngineContractTest] on every backend.
 *
 * A frame read at a position belongs to the slot [outputSlotOf] names for it, at every position in the slot. A backend
 * that turns the position into its own clock must not round it into the next slot, so the last microsecond before the
 * half way point is read as well as the slot's own time, a point inside its first half and the half way point itself.
 *
 * @param createEngine Builds a fresh engine holding no composition.
 * @param fixture The composition each case reads from.
 * @param rate The output frame rate [fixture] lands on.
 */
internal class ReadbackSlotContract(
  private val createEngine: (CoroutineScope) -> EngineUnderTest,
  private val fixture: Composition,
  private val rate: Int,
) {
  /**
   * Seeks exactly to each position of three slots spread over the fixture and reads a frame back at it.
   *
   * The frame's stamp rounds to the slot the position names, so a readback that lands on the half way point while
   * asked for the microsecond before it is caught.
   */
  fun aFrameReadAtAPositionIsOfItsSlot(): TestResult =
    contractTest { scope ->
      val subject = createEngine(scope)
      withEngine(subject.engine) { recorder ->
        subject.engine.awaitComposition(fixture).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }
        val duration = recorder.lastState.duration ?: error("The engine reported no duration to read within.")

        for (point in POINTS) {
          val slot = outputSlotOf(duration * point, rate)
          for (at in positionsIn(slot)) {
            val before = recorder.seekCompletions.size
            subject.engine.seekTo(at, SeekAccuracy.Exact)
            awaitContract("the seek to $at to complete") { recorder.seekCompletions.size > before }

            val read = subject.engine.readback.awaitFrame(at)
            withClue("A frame read at $at was stamped ${read.presentationTime}") {
              outputSlotOf(read.presentationTime, rate) shouldBe outputSlotOf(at, rate)
            }
          }
        }
      }
    }

  // The slot's own time, a point inside its first half, the microsecond before the half way point and that point.
  private fun positionsIn(slot: Long): List<Duration> {
    val start = outputSlotTime(slot, rate)
    val half = firstMicrosecondOf(slot + 1)
    check(outputSlotOf(half - 1.microseconds, rate) == slot) { "The microsecond before $half is not in slot $slot." }
    return listOf(start, start + (half - start) / 2, half - 1.microseconds, half)
  }

  // The earliest whole microsecond outputSlotOf sends to [slot], which is the half way point before it.
  private fun firstMicrosecondOf(slot: Long): Duration =
    ((2 * slot - 1) * MICROS_PER_SECOND + 2 * rate - 1).div(2L * rate).microseconds

  private companion object {
    val POINTS = listOf(0.25, 0.5, 0.75)
    const val MICROS_PER_SECOND = 1_000_000L
  }
}

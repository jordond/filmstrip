package dev.jordond.filmstrip.player.contract

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.edit.outputSlotsOf
import dev.jordond.filmstrip.edit.sourceOffsetAt
import dev.jordond.filmstrip.transform.internal.nearestSourceFrame
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The short clip cells that read every slot of a clip whose span opens between two slots, past its first slot or
 * ahead of it, at speed 1 and sped up. A slot's source time counts from where the span opens.
 */
abstract class ShortClipOffGridContractTest(
  backend: ShortClipBackend,
) : ShortClipCellReader(backend) {
  @Test
  fun cellKASpanOpeningPastItsSlotAtSpeedOne() = assertSpanOpeningOffTheGrid(PAST_ITS_SLOT, 1.0)

  @Test
  fun cellKASpanOpeningPastItsSlotInTheMiddleOfTheRange() = assertSpanOpeningOffTheGrid(PAST_ITS_SLOT, SPED_UP)

  @Test
  fun cellKASpanOpeningAheadOfItsSlotAtSpeedOne() = assertSpanOpeningOffTheGrid(AHEAD_OF_ITS_SLOT, 1.0)

  @Test
  fun cellKASpanOpeningAheadOfItsSlotInTheMiddleOfTheRange() = assertSpanOpeningOffTheGrid(AHEAD_OF_ITS_SLOT, SPED_UP)

  // The opening ends between two slots, so the clip after it opens off the grid, past its first slot or ahead of it,
  // with its trim part of a frame past a frame. A slot's source time counts from where the span opens, and counted from
  // the clip's first slot instead some slots land on another frame.
  private fun assertSpanOpeningOffTheGrid(
    offGrid: OffGrid,
    speed: Double,
  ) = assertCell { source ->
    val start = 1.seconds / frameRate * offGrid.trimFrames
    val opening = Cut(TimeRange.of(Duration.ZERO, offGrid.opening), 1.0)
    val run = runOf(opening, Cut(TimeRange.of(start, start + HALF_SECOND), speed), CLOSING)
    val slots = outputSlotsOf(run[1].span, frameRate)
    withClue("the clip opens on its first slot's own time") {
      (run[1].span.start == outputSlotTime(slots.first, frameRate)).shouldBeFalse()
    }
    val reads = slots.map { readOf(run[1], it) }
    withClue("no slot reads another frame when its source time is counted from the clip's first slot") {
      reads.any { read -> countedFromFirstSlot(run[1], read.slot) != read.sourceFrame }.shouldBeTrue()
    }
    Cell(editOf(source, run), neighbourReads(run) + reads, writtenFrames = writtenFramesOf(run, reads))
  }

  private fun countedFromFirstSlot(
    placed: PlacedClip,
    slot: Long,
  ): Long {
    val first = outputSlotsOf(placed.span, frameRate).first
    val offset = sourceOffsetAt(outputSlotTime(slot, frameRate) - outputSlotTime(first, frameRate), placed.cut.speed)
    return checkNotNull(nearestSourceFrame(sourceFrameTimes(frameRate), placed.cut.trim.start + offset)).toLong()
  }

  private companion object {
    // A quarter of a slot past slot 14 at 30 fps, and a fifth of a slot ahead of it. Each trim puts some slot on
    // another frame when counted from the clip's first slot.
    val PAST_ITS_SLOT = OffGrid(475.milliseconds, 10.6)
    val AHEAD_OF_ITS_SLOT = OffGrid(460.milliseconds, 10.4)
  }

  private class OffGrid(
    val opening: Duration,
    val trimFrames: Double,
  )
}

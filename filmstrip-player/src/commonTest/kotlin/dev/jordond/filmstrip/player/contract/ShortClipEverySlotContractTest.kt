package dev.jordond.filmstrip.player.contract

import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotsOf
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The short clip cells that read every slot of a longer clip in a run, slowed or sped up, and every slot of a slower
 * source at speed 1. Each slot shows its nearest source frame, which is not always the one at or before its source
 * time.
 */
abstract class ShortClipEverySlotContractTest(
  backend: ShortClipBackend,
) : ShortClipCellReader(backend) {
  @Test
  fun cellIEverySlotOfASlowedClipInARunIsTheNearestFrame() = assertNearestFrameOnEverySlot(SLOWED)

  @Test
  fun cellIEverySlotOfASpedUpClipInARunIsTheNearestFrame() = assertNearestFrameOnEverySlot(SPED_UP)

  @Test
  fun cellLASlowerSourceAtSpeedOneShowsTheNearestFrame() = assertSlowerSourceAtSpeedOne()

  // The trim opens a quarter of a frame past a frame, so no slot's source time sits half way between two frames, and on
  // a sped up clip every other slot's nearest frame is the one after the frame at or before it.
  private fun assertNearestFrameOnEverySlot(speed: Double) =
    assertCell { source ->
      val frame = 1.seconds / frameRate
      val start = frame / 4
      val run = runOf(OPENING, Cut(TimeRange.of(start, start + 1.seconds), speed), CLOSING)
      val slots = outputSlotsOf(run[1].span, frameRate)
      val reads = slots.map { readOf(run[1], it) }
      withClue("no slot reads a frame other than the one at or before its source time") {
        reads.any { read -> (sourceTimeAt(run[1], read.slot) / frame).toLong() != read.sourceFrame }.shouldBeTrue()
      }
      Cell(editOf(source, run), neighbourReads(run) + reads, writtenFrames = writtenFramesOf(run, reads))
    }

  // The opening's source runs at the output's rate and the clip after it at a slower one, so the output keeps the
  // opening's grid and the slower source's frames fall between its slots.
  private fun assertSlowerSourceAtSpeedOne() =
    assertCell(source = { sourceAt(SLOWER_RATE) }, sourceRate = SLOWER_RATE) { slower ->
      val run = runOf(OPENING, Cut(TimeRange.of(Duration.ZERO, 1.seconds), 1.0))
      val reads = outputSlotsOf(run[1].span, frameRate).map { readOf(run[1], it, sourceRate = SLOWER_RATE) }
      val frame = 1.seconds / SLOWER_RATE
      withClue("no slot reads a frame other than the one at or before its source time") {
        reads.any { read -> (sourceTimeAt(run[1], read.slot) / frame).toLong() != read.sourceFrame }.shouldBeTrue()
      }
      val edit = EditComposition(listOf(Track(listOf(run[0].clipOf(movingSource()), run[1].clipOf(slower)))))
      Cell(edit, reads, writtenFrames = writtenFramesOf(run, reads))
    }

  private companion object {
    const val SLOWED = 0.7
    const val SLOWER_RATE = 24
  }
}

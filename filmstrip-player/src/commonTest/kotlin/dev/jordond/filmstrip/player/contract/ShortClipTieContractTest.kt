package dev.jordond.filmstrip.player.contract

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputSlotsOf
import dev.jordond.filmstrip.transform.internal.videoSlotsOf
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import kotlin.test.Test
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The short clip cells that read every slot of a trim opening half a frame in, at speed 1 and sped up. A slot whose
 * source time sits half way between two frames shows the earlier one.
 */
abstract class ShortClipTieContractTest(
  backend: ShortClipBackend,
) : ShortClipCellReader(backend) {
  @Test
  fun cellJATrimAtHalfAFrameTakesTheEarlierFrameAtSpeedOne() = assertHalfFrameTie(1.0)

  @Test
  fun cellJATrimAtHalfAFrameTakesTheEarlierFrameInTheMiddleOfTheRange() = assertHalfFrameTie(SPED_UP)

  // The trim opens on the first whole microsecond past half a frame, so a slot's source time sits within a microsecond
  // of half way between two frames, which is a tie and takes the earlier one.
  private fun assertHalfFrameTie(speed: Double) =
    assertCell { source ->
      val start = (1.seconds / frameRate / 2).inWholeMicroseconds.microseconds + 1.microseconds
      val run = runOf(OPENING, Cut(TimeRange.of(start, start + 1.seconds), speed), CLOSING)
      val slots = outputSlotsOf(run[1].span, frameRate)
      withClue("no slot reads within a microsecond of half way between two frames") {
        slots.any { slot -> sitsOnATie(sourceTimeAt(run[1], slot), frameRate) }.shouldBeTrue()
      }
      Cell(
        editOf(source, run),
        neighbourReads(run) + slots.map { readOf(run[1], it) },
        writtenFrames = videoSlotsOf(checkNotNull(run.last().span.endExclusive), frameRate),
      )
    }
}

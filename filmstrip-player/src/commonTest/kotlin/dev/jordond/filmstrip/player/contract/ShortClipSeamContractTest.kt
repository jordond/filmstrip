package dev.jordond.filmstrip.player.contract

import dev.jordond.filmstrip.edit.CLIP_SPEED_RANGE
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.edit.outputSlotsOf
import dev.jordond.filmstrip.edit.sourceOffsetAt
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The short clip cells that cut one source in two, `[a, b)` then `[b, c)`, and read the slots around where the cuts
 * meet.
 *
 * Each slot shows the frame the uncut source shows there, wherever the clip that covers it opens, so the last slot of
 * the first clip and the first of the second are consecutive frames at speed 1, none dropped and none shown twice. The
 * seam sits on a slot, between two, and with the first trim opening between two source frames, and the cells run at
 * speed 1 and at half, twice and four times it.
 *
 * Camel case test names, for the reason [PlayerEngineContractTest] gives.
 */
abstract class ShortClipSeamContractTest(
  backend: ShortClipBackend,
) : ShortClipCellReader(backend) {
  @Test
  fun cellSeamOnASlot() = assertSeam(a = 0.milliseconds, b = 500.milliseconds, c = 1_000.milliseconds)

  @Test
  fun cellSeamBetweenTwoSlots() = assertSeam(a = 0.milliseconds, b = 475.milliseconds, c = 1_000.milliseconds)

  @Test
  fun cellFirstTrimOpeningBetweenTwoSourceFrames() =
    assertSeam(a = 110.milliseconds, b = 610.milliseconds, c = 1_100.milliseconds)

  @Test
  fun cellSeamBetweenTwoSlotsAfterAFirstTrimOpeningBetweenTwoSourceFrames() =
    assertSeam(a = 110.milliseconds, b = 585.milliseconds, c = 1_100.milliseconds)

  @Test
  fun cellSeamBetweenTwoSlotsAtHalfTheSpeed() =
    assertSeam(a = 110.milliseconds, b = 400.milliseconds, c = 700.milliseconds, speed = 0.5)

  // Four source frames to a slot, so the slot before the seam sits two source frames ahead of the clip's first one.
  @Test
  fun cellSeamBetweenTwoSlotsAtFourTimesTheSpeed() =
    assertSeam(a = 110.milliseconds, b = 700.milliseconds, c = 1_400.milliseconds, speed = 4.0)

  // Twice the speed with the seam a second into the source, where the source holds a sync sample. The slot before the
  // seam reads back 23 ms to a source time of 976.7 ms, frame 29, where the trim's own nearest frame is 30, and frame
  // 29 sits before that sync sample, so a read opened on the sample at the trim has no such frame.
  @Test
  fun cellSeamBetweenTwoSlotsOnASyncSampleAtTwiceTheSpeed() =
    assertSeam(
      a = 110.milliseconds,
      b = 1_000.milliseconds,
      c = 1_400.milliseconds,
      speed = 2.0,
      syncAt = 1_000.milliseconds,
    )

  // Four times the speed with the seam on the same sync sample. The slot before the seam reads source frame 27, nine
  // frames short of the sample, and the slot after it reads from the sample on, so the second clip's read must reach
  // back across the sample to meet the first.
  @Test
  fun cellSeamBetweenTwoSlotsOnASyncSampleAtFourTimesTheSpeed() =
    assertSeam(
      a = 110.milliseconds,
      b = 1_000.milliseconds,
      c = 1_400.milliseconds,
      speed = 4.0,
      syncAt = 1_000.milliseconds,
    )

  // The source runs at twice the grid, which the first clip of the track sets from its own rate. The trim sits on
  // source frame 21 and the slot before the seam reads back 11.7 ms, so it shows frame 20, which a read opened at the
  // trim's own frame does not hold.
  @Test
  fun cellSeamBetweenTwoSlotsOnASourceFasterThanTheGrid() =
    assertCell(source = { sourceAt(FASTER_RATE) }, sourceRate = FASTER_RATE) { source ->
      val lead = Cut(TimeRange.of(Duration.ZERO, LEAD_IN), 1.0)
      val a = 105.milliseconds
      val b = 350.milliseconds
      val c = 700.milliseconds
      val run = runOf(lead, Cut(TimeRange.of(a, b), 1.0), Cut(TimeRange.of(b, c), 1.0))
      val seam = outputSlotsOf(run.last().span, frameRate).first
      val uncut = PlacedClip(Cut(TimeRange.of(a, c), 1.0), TimeRange.of(LEAD_IN, LEAD_IN + c - a))
      val reads =
        (seam - SLOTS_EACH_SIDE until seam + SLOTS_EACH_SIDE).map {
          readOf(
            uncut,
            it,
            sourceRate = FASTER_RATE,
          )
        }
      val first = reads.first { it.slot == seam - 1 }
      val second = reads.first { it.slot == seam }
      val edit =
        Composition(
          listOf(
            Track(
              listOf(
                Clip(movingSource(), lead.trim),
                Clip(source, TimeRange.of(a, b)),
                Clip(source, TimeRange.of(b, c)),
              ),
            ),
          ),
        )
      val plain =
        Composition(listOf(Track(listOf(Clip(movingSource(), lead.trim), Clip(source, TimeRange.of(a, c))))))
      Cell(edit, reads, seam = Seam(seam, plain, differs = first.sourceFrame != second.sourceFrame))
    }

  // The middle clip covers no slot and the one after it covers the slot ahead of its span, at 32 times speed. The run
  // plays on from the clip that covers none, so the slot reads no earlier than that clip's trim, where reading back by
  // its own lead would reach more than 300 ms before it.
  @Test
  fun cellARunHeadCoveringNoSlotHoldsTheSlotAfterItAtItsTrim() =
    assertCell { source ->
      val fast = CLIP_SPEED_RANGE.endInclusive
      val run =
        runOf(
          Cut(TimeRange.of(Duration.ZERO, 210.milliseconds), 1.0),
          Cut(TimeRange.of(400.milliseconds, 432.milliseconds), fast),
          Cut(TimeRange.of(432.milliseconds, 1_000.milliseconds), fast),
          Cut(TimeRange.of(1_000.milliseconds, 1_400.milliseconds), 1.0),
        )
      val covered = outputSlotsOf(run[2].span, frameRate).first
      withClue("the second clip covers no slot and the third the one before its predecessor's span") {
        outputSlotsOf(run[1].span, frameRate).isEmpty().shouldBeTrue()
        (outputSlotTime(covered, frameRate) < run[1].span.start).shouldBeTrue()
        run[2].runStart shouldBe 400.milliseconds
      }
      val reads = listOf(readOf(run[0], covered - 1), readOf(run[2], covered), readOf(run[3], covered + 1))
      Cell(editOf(source, run), reads)
    }

  // The first clip's trim ends short of where the second begins, so the second plays on from nothing and its slot
  // ahead of the span reads its own trim start, where one that played on would read back from it.
  @Test
  fun cellATrimGapBetweenTwoClipsHoldsTheSlotAtTheSecondTrim() =
    assertBoundary(
      Cut(TimeRange.of(110.milliseconds, 585.milliseconds), 1.0),
      Cut(TimeRange.of(600.milliseconds, 1_100.milliseconds), 1.0),
    )

  @Test
  fun cellASpeedChangeBetweenTwoClipsHoldsTheSlotAtTheSecondTrim() =
    assertBoundary(
      Cut(TimeRange.of(110.milliseconds, 585.milliseconds), 1.0),
      Cut(TimeRange.of(585.milliseconds, 1_100.milliseconds), 2.0),
    )

  // The two clips cut one frame sequence from two files, so the second plays on from nothing whatever its trims say.
  @Test
  fun cellTwoFilesWhoseTrimsMeetHoldTheSlotAtTheSecondTrim() =
    assertCell { source ->
      val other = sourceAt(frameRate)
      withClue("the second clip is cut from the same file") { (other == source).shouldBeFalse() }
      val cuts =
        listOf(
          Cut(TimeRange.of(110.milliseconds, 585.milliseconds), 1.0),
          Cut(TimeRange.of(585.milliseconds, 1_100.milliseconds), 1.0),
        )
      val run = runOf(*cuts.toTypedArray())
      val second = PlacedClip(run[1].cut, run[1].span, run[1].cut.trim.start)
      val seam = outputSlotsOf(second.span, frameRate).first
      val reads = listOf(readOf(run[0], seam - 1), readOf(second, seam), readOf(second, seam + 1))
      val edit = Composition(listOf(Track(listOf(Clip(source, cuts[0].trim), Clip(other, cuts[1].trim)))))
      Cell(edit, reads, writtenFrames = writtenFramesOf(run, reads))
    }

  // Two clips of one source laid by [cuts], with the slots either side of where the second opens read as the run
  // lays them.
  private fun assertBoundary(
    first: Cut,
    second: Cut,
  ) = assertCell { source ->
    val run = runOf(first, second)
    val seam = outputSlotsOf(run[1].span, frameRate).first
    withClue("the second clip does not play on from the first") { run[1].runStart shouldBe second.trim.start }
    val reads = listOf(readOf(run[0], seam - 1), readOf(run[1], seam), readOf(run[1], seam + 1))
    Cell(editOf(source, run), reads, writtenFrames = writtenFramesOf(run, reads))
  }

  private fun assertSeam(
    a: Duration,
    b: Duration,
    c: Duration,
    speed: Double = 1.0,
    syncAt: Duration? = null,
  ) = assertCell { source ->
    syncAt?.let { at ->
      syncSampleTimes(source)?.let { times ->
        withClue("the source holds no sync sample at $at, only at $times") { times.contains(at).shouldBeTrue() }
      }
    }
    val run = runOf(Cut(TimeRange.of(a, b), speed), Cut(TimeRange.of(b, c), speed))
    val seam = seamSlotOf(run)
    val whole = checkNotNull(run.last().span.endExclusive)
    val uncut = PlacedClip(Cut(TimeRange.of(a, c), speed), TimeRange.of(Duration.ZERO, whole))
    val slots = (seam - SLOTS_EACH_SIDE until seam + SLOTS_EACH_SIDE).toList()
    withClue("the slot before the seam belongs to the first clip") {
      outputSlotsOf(run.first().span, frameRate).last shouldBe seam - 1
    }
    withClue("no read sits on a tie between two frames") {
      val ties = slots.filter { sitsOnATie(a + sourceOffsetAt(outputSlotTime(it, frameRate), speed), frameRate) }
      ties.isEmpty().shouldBeTrue()
    }
    // A run at another speed shows some frames twice or skips some, so it reads every slot, which also counts the
    // frames the file holds.
    val read = if (speed == 1.0) slots else outputSlotsOf(uncut.span, frameRate).toList()
    val reads = read.map { slot -> readOf(uncut, slot) }
    val written = if (speed == 1.0) null else writtenFramesOf(run, reads)
    val seamReads = reads.filter { it.slot == seam - 1 || it.slot == seam }
    val plain = Composition(listOf(Track(listOf(Clip(source, TimeRange.of(a, c), speed = speed)))))
    Cell(
      editOf(source, run),
      reads,
      writtenFrames = written,
      seam = Seam(seam, plain, differs = seamReads.first().sourceFrame != seamReads.last().sourceFrame),
    )
  }

  // The first slot the second clip covers, which is where the cuts meet.
  private fun seamSlotOf(run: List<PlacedClip>): Long = outputSlotsOf(run.last().span, frameRate).first

  private companion object {
    const val SLOTS_EACH_SIDE = 3

    // Twice the grid, and the length of the first clip of the track that sets the grid at it.
    const val FASTER_RATE = 60
    val LEAD_IN = 500.milliseconds
  }
}

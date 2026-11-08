package dev.jordond.filmstrip.player.contract

import dev.jordond.filmstrip.edit.CLIP_SPEED_RANGE
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.outputLengthOf
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.edit.outputSlotsOf
import dev.jordond.filmstrip.edit.sourceLengthOf
import dev.jordond.filmstrip.edit.sourceOffsetAt
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.player.SetCompositionResult
import dev.jordond.filmstrip.transform.internal.nearestSourceFrame
import dev.jordond.filmstrip.transform.internal.sourceTimeOfSlot
import dev.jordond.filmstrip.transform.internal.videoSlotsOf
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A slot the planner says a clip covers holds exactly one frame, in the preview and in the file, on every backend.
 *
 * Each test is one edit, named for its cell: a primary run under a slot, one slot alone with frames in its trim or
 * none, a slot inside a run whose nearest frame is not the one at or before it, a clip of no slot or one slot inside a
 * longer run, a run of clips that cover only one slot between them, one slot under longer audio, and a slot at a faster
 * output rate, every slot of a longer clip in a run at a speed, every slot of a trim opening half a frame in, every
 * slot of a clip whose span opens between two slots, and every slot of a slower source at speed 1. A slot shows the
 * source frame [nearestSourceFrame] picks for its [sourceTimeOfSlot], so a trim that holds no frame shows one from
 * outside it.
 *
 * A test runs every side of its cell through [ShortClipCellReader], and reports each side that failed.
 *
 * Camel case test names, for the reason [PlayerEngineContractTest] gives.
 */
abstract class ShortClipContractTest(
  backend: ClipParityBackend,
) : ShortClipCellReader(backend) {
  @Test
  fun cellARunUnderHalfASlotAtSpeedIsRefused() = assertRefused(500.milliseconds, CLIP_SPEED_RANGE.endInclusive)

  @Test
  fun cellARunUnderHalfASlotAtSpeedOneIsRefused() = assertRefused(15.milliseconds, 1.0)

  @Test
  fun cellBOneSlotAtTheFastEnd() = assertOneSlotAlone(ONE_SLOT_AT_32)

  @Test
  fun cellBOneSlotInTheMiddleOfTheRange() = assertOneSlotAlone(ONE_SLOT_AT_1_5)

  @Test
  fun cellBOneSlotAtSpeedOne() = assertOneSlotAlone(ONE_SLOT_AT_1)

  @Test
  fun cellCTrimHoldingNoFrameAtSpeedOne() = assertOneSlotAlone(NO_FRAME_AT_1)

  @Test
  fun cellCTrimHoldingNoFrameAtTheSlowEnd() = assertOneSlotAlone(NO_FRAME_AT_SLOW_END)

  @Test
  fun cellCNearestFrameInARunAtSpeedOne() = assertNearestFrameInARun(1.0)

  @Test
  fun cellCNearestFrameInARunInTheMiddleOfTheRange() = assertNearestFrameInARun(1.5)

  @Test
  fun cellDNoSlotInTheMiddleOfARun() =
    assertCell { source ->
      val middle = Cut(TimeRange.of(Duration.ZERO, HALF_SECOND), CLIP_SPEED_RANGE.endInclusive)
      val run = runOf(OPENING, middle, CLOSING)
      withClue("the middle clip covers a slot") { outputSlotsOf(run[1].span, frameRate).isEmpty().shouldBeTrue() }
      Cell(editOf(source, run), neighbourReads(run), writtenAtSpeed = 1.0)
    }

  @Test
  fun cellEOneSlotInARunAtTheFastEnd() = assertOneSlotInARun(ONE_SLOT_AT_32)

  @Test
  fun cellEOneSlotInARunInTheMiddleOfTheRange() = assertOneSlotInARun(ONE_SLOT_AT_1_5)

  @Test
  fun cellEOneSlotInARunAtSpeedOne() = assertOneSlotInARun(ONE_SLOT_AT_1)

  @Test
  fun cellETrimHoldingNoFrameInARunAtSpeedOne() = assertOneSlotInARun(NO_FRAME_AT_1)

  @Test
  fun cellETrimHoldingNoFrameInARunAtTheSlowEnd() = assertOneSlotInARun(NO_FRAME_AT_SLOW_END)

  @Test
  fun cellFThreeClipsCoveringOneSlotBetweenThem() =
    assertCell { source ->
      val speed = CLIP_SPEED_RANGE.endInclusive
      val run =
        runOf(
          Cut(TimeRange.of(Duration.ZERO, HALF_SECOND), speed),
          Cut(TimeRange.of(HALF_SECOND, HALF_SECOND * 2), speed),
          Cut(TimeRange.of(HALF_SECOND * 2, HALF_SECOND * 3), speed),
        )
      val covered = run.map { outputSlotsOf(it.span, frameRate).count() }
      withClue("the three clips no longer cover 0, 1 and 0 slots") { covered shouldBe listOf(0, 1, 0) }
      Cell(editOf(source, run), listOf(readOf(run[1], outputSlotsOf(run[1].span, frameRate).first)))
    }

  @Test
  fun cellGOneSlotUnderLongerAudioHoldsFromTheNextSlot() =
    assertCell { source ->
      val run = runOf(ONE_SLOT_AT_32.at(Duration.ZERO))
      val primary = run.single()
      val bed = Track(listOf(Clip(fixtureClip, TimeRange.of(Duration.ZERO, 1.seconds))), content = TrackContent.Audio)
      val edit = EditComposition(listOf(Track(listOf(primary.clipOf(source))), bed))
      val duration = checkNotNull(edit.duration)
      val tail = outputSlotsOf(TimeRange.of(checkNotNull(primary.span.endExclusive), duration), frameRate)
      val slots = outputSlotsOf(primary.span, frameRate)
      val shown = readOf(primary, slots.single())
      if (writesHeldTail) {
        val held =
          listOf(tail.first, (tail.first + tail.last) / 2, tail.last).map { Read(it, shown.sourceFrame) }
        Cell(edit, listOf(shown) + held, writtenAtSpeed = 1.0)
      } else {
        Cell(edit, listOf(shown), writtenAtSpeed = 1.0, writtenFrames = slots.count().toLong())
      }
    }

  @Test
  fun cellHAClipThatCoversOneSlotOnlyAtAFasterRate() =
    assertCell(FAST_RATE, { sourceAt(FAST_RATE) }) { source ->
      val cut = Cut(TimeRange.of(Duration.ZERO, HALF_SECOND), CLIP_SPEED_RANGE.endInclusive)
      val run = runOf(cut)
      withClue("the clip covers a slot at the fixture's rate") {
        outputSlotsOf(run.single().span, frameRate).isEmpty().shouldBeTrue()
      }
      val slot = outputSlotsOf(run.single().span, FAST_RATE).single()
      Cell(editOf(source, run), listOf(readOf(run.single(), slot, FAST_RATE)))
    }

  @Test
  fun cellIEverySlotOfASlowedClipInARunIsTheNearestFrame() = assertNearestFrameOnEverySlot(SLOWED)

  @Test
  fun cellIEverySlotOfASpedUpClipInARunIsTheNearestFrame() = assertNearestFrameOnEverySlot(SPED_UP)

  @Test
  fun cellJATrimAtHalfAFrameTakesTheEarlierFrameAtSpeedOne() = assertHalfFrameTie(1.0)

  @Test
  fun cellJATrimAtHalfAFrameTakesTheEarlierFrameInTheMiddleOfTheRange() = assertHalfFrameTie(SPED_UP)

  @Test
  fun cellKASpanOpeningPastItsSlotAtSpeedOne() = assertSpanOpeningOffTheGrid(PAST_ITS_SLOT, 1.0)

  @Test
  fun cellKASpanOpeningPastItsSlotInTheMiddleOfTheRange() = assertSpanOpeningOffTheGrid(PAST_ITS_SLOT, SPED_UP)

  @Test
  fun cellKASpanOpeningAheadOfItsSlotAtSpeedOne() = assertSpanOpeningOffTheGrid(AHEAD_OF_ITS_SLOT, 1.0)

  @Test
  fun cellKASpanOpeningAheadOfItsSlotInTheMiddleOfTheRange() = assertSpanOpeningOffTheGrid(AHEAD_OF_ITS_SLOT, SPED_UP)

  @Test
  fun cellLASlowerSourceAtSpeedOneShowsTheNearestFrame() = assertSlowerSourceAtSpeedOne()

  private fun assertRefused(
    length: Duration,
    speed: Double,
  ) = contractTest(SHORT_CLIP_BUDGET) { scope ->
    val cut = Cut(TimeRange.of(Duration.ZERO, length), speed)
    val edit = editOf(fixtureClip, runOf(cut))
    withClue("the run covers a slot") {
      outputSlotsOf(TimeRange.of(Duration.ZERO, outputLengthOf(length, speed)), frameRate).isEmpty().shouldBeTrue()
    }
    var exported: String? = null
    runSides(
      "export" to {
        exported =
          filmstrip
            .plan(edit, ExportSpec())
            .shouldBeInstanceOf<Verdict.Incapable>()
            .reasons
            .single()
            .message
      },
      "preview" to {
        val engine = createEngine(scope)
        withEngine(engine) {
          val refused = engine.awaitComposition(edit).shouldBeInstanceOf<SetCompositionResult.Failure>()
          exported?.let { reason -> refused.error.message shouldBe reason }
        }
      },
    )
  }

  private fun assertOneSlotAlone(cut: PlacedCut) =
    assertCell { source ->
      val run = runOf(cut.at(Duration.ZERO))
      val slot = outputSlotsOf(run.single().span, frameRate).single()
      Cell(editOf(source, run), listOf(readOf(run.single(), slot)))
    }

  // The middle clip reads from further into the source than either neighbour reaches, so a frame of theirs in its slot
  // reads differently from its own.
  private fun assertOneSlotInARun(cut: PlacedCut) =
    assertCell { source ->
      val run = runOf(OPENING, cut.at(outputSlotTime(MIDDLE_CLIP_FRAME, frameRate)), CLOSING)
      val slot = outputSlotsOf(run[1].span, frameRate).single()
      Cell(editOf(source, run), neighbourReads(run) + readOf(run[1], slot))
    }

  // The opening ends three quarters of a slot ahead of a slot, so that slot sits that far into the middle clip, and the
  // trim opens where the slot reads three quarters of a frame past a frame the trim holds. The frame at or before that
  // time is the one the trim holds, and the nearest is the one after it.
  private fun assertNearestFrameInARun(speed: Double) =
    assertCell { source ->
      val frame = 1.seconds / frameRate
      val slot = outputSlotOf(HALF_SECOND, frameRate)
      val into = frame * 3 / 4
      val opening = Cut(TimeRange.of(Duration.ZERO, outputSlotTime(slot, frameRate) - into), 1.0)
      val start = outputSlotTime(MIDDLE_CLIP_FRAME, frameRate) + into - sourceOffsetAt(into, speed)
      val middle = Cut(TimeRange.of(start, start + sourceLengthOf(frame * 3 / 2, speed)), speed)
      val run = runOf(opening, middle, CLOSING)
      withClue("the middle clip no longer covers slot $slot") {
        (slot in outputSlotsOf(run[1].span, frameRate)).shouldBeTrue()
      }
      val read = readOf(run[1], slot)
      withClue("the frame at or before slot $slot's source time is the nearest one") {
        (sourceTimeAt(run[1], slot) / frame).toLong() shouldNotBe read.sourceFrame
      }
      Cell(editOf(source, run), neighbourReads(run) + read)
    }

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

  private fun countedFromFirstSlot(
    placed: PlacedClip,
    slot: Long,
  ): Long {
    val first = outputSlotsOf(placed.span, frameRate).first
    val offset = sourceOffsetAt(outputSlotTime(slot, frameRate) - outputSlotTime(first, frameRate), placed.cut.speed)
    return checkNotNull(nearestSourceFrame(sourceFrameTimes(frameRate), placed.cut.trim.start + offset)).toLong()
  }

  private companion object {
    const val MIDDLE_CLIP_FRAME = 20L
    const val FAST_RATE = 60
    val HALF_SECOND = 500.milliseconds

    val OPENING = Cut(TimeRange.of(Duration.ZERO, HALF_SECOND), 1.0)
    val CLOSING = Cut(TimeRange.of(HALF_SECOND, HALF_SECOND * 2), 1.0)

    val ONE_SLOT_AT_32 = PlacedCut(Duration.ZERO, 640.milliseconds, CLIP_SPEED_RANGE.endInclusive)
    val ONE_SLOT_AT_1_5 = PlacedCut(Duration.ZERO, 30.milliseconds, 1.5)
    val ONE_SLOT_AT_1 = PlacedCut(Duration.ZERO, 20.milliseconds, 1.0)

    // A third of a frame in to most of a frame in at 30 fps, so neither end of the trim reaches a frame.
    val NO_FRAME_AT_1 = PlacedCut(10.milliseconds, 30.milliseconds, 1.0)
    val NO_FRAME_AT_SLOW_END = PlacedCut(10.milliseconds, 10.625.milliseconds, CLIP_SPEED_RANGE.start)

    const val SLOWED = 0.7
    const val SPED_UP = 1.5

    // A quarter of a slot past slot 14 at 30 fps, and a fifth of a slot ahead of it. Each trim puts some slot on
    // another frame when counted from the clip's first slot.
    val PAST_ITS_SLOT = OffGrid(475.milliseconds, 10.6)
    val AHEAD_OF_ITS_SLOT = OffGrid(460.milliseconds, 10.4)
    const val SLOWER_RATE = 24
  }

  private class OffGrid(
    val opening: Duration,
    val trimFrames: Double,
  )
}

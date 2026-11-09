package dev.jordond.filmstrip.player.contract

import dev.jordond.filmstrip.edit.CLIP_SPEED_RANGE
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
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
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The short clip cells around a single slot.
 *
 * A primary run under a slot is refused, one slot alone shows a frame with or without one in its trim, a slot inside a
 * run shows its nearest frame when that is not the one at or before it, a clip covers no slot or one slot inside a
 * longer run, a run of clips covers one slot between them, one slot under longer audio holds into the slots after it,
 * and a clip covers a slot only at a faster output rate.
 */
abstract class ShortClipSlotContractTest(
  backend: ShortClipBackend,
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
      val edit = Composition(listOf(Track(listOf(primary.clipOf(source))), bed))
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

  private companion object {
    const val MIDDLE_CLIP_FRAME = 20L
    const val FAST_RATE = 60

    val ONE_SLOT_AT_32 = PlacedCut(Duration.ZERO, 640.milliseconds, CLIP_SPEED_RANGE.endInclusive)
    val ONE_SLOT_AT_1_5 = PlacedCut(Duration.ZERO, 30.milliseconds, 1.5)
    val ONE_SLOT_AT_1 = PlacedCut(Duration.ZERO, 20.milliseconds, 1.0)

    // A third of a frame in to most of a frame in at 30 fps, so neither end of the trim reaches a frame.
    val NO_FRAME_AT_1 = PlacedCut(10.milliseconds, 30.milliseconds, 1.0)
    val NO_FRAME_AT_SLOW_END = PlacedCut(10.milliseconds, 10.625.milliseconds, CLIP_SPEED_RANGE.start)
  }
}

package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.CLIP_SPEED_RANGE
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.outputLengthOf
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotsOf
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.Verdict
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Whether each run on the primary track covers at least one output frame.
 *
 * Covers the half-frame boundary, stills held for less than a frame, looping and late primaries, and audio-only exports
 * that need no frame at all.
 */
class FrameCoverageTest {
  private val fixture = PlannerFixture()

  // A primary whose run starts and ends on the same output slot has no frame of video to write. Each
  // fixture below is read against the shared grid first, so which side of the rule it falls on is
  // the grid's answer rather than a copy of it here.
  @Test
  fun `a primary run that starts and ends on the same output frame is refused by name`() {
    val verdict = fixture.plan(composition(fixture.clip(trim = TimeRange.of(Duration.ZERO, SHORT_RUN))), gridSpec())

    assertRefusedForCoveringNoFrame(verdict, TimeRange.of(Duration.ZERO, SHORT_RUN), GRID_RATE)
  }

  @Test
  fun `a primary run just short of half a frame is refused`() {
    val run = TimeRange.of(Duration.ZERO, JUST_SHORT_OF_HALF)

    assertRefusedForCoveringNoFrame(fixture.plan(composition(fixture.clip(trim = run)), gridSpec()), run, GRID_RATE)
  }

  @Test
  fun `a primary run of exactly half a frame covers one and plans`() {
    val run = TimeRange.of(Duration.ZERO, HALF_A_SLOT)

    assertCoversAFrameAndPlans(fixture.plan(composition(fixture.clip(trim = run)), gridSpec()), run, GRID_RATE)
  }

  // The same ten milliseconds of video, moved so it reaches the point half way between the first two
  // slots. Its length alone does not decide it.
  @Test
  fun `a short primary run that reaches half way between two frames covers one and plans`() {
    val primary = Track(listOf(fixture.clip(trim = TimeRange.of(Duration.ZERO, SHORT_RUN))), start = STRADDLING_START)

    val verdict = fixture.plan(Composition(listOf(primary)), gridSpec())

    assertCoversAFrameAndPlans(verdict, TimeRange.of(STRADDLING_START, STRADDLING_START + SHORT_RUN), GRID_RATE)
  }

  // A longer track sets the composition's length, and the primary still has no frame of its own.
  @Test
  fun `a primary run that covers no frame under a longer audio track is refused`() {
    val composition =
      Composition(
        listOf(
          Track(listOf(fixture.clip(trim = TimeRange.of(Duration.ZERO, SHORT_RUN)))),
          Track(listOf(fixture.audioOnlyClip(duration = 5_000.milliseconds)), content = TrackContent.Audio),
        ),
      )

    assertRefusedForCoveringNoFrame(
      fixture.plan(composition, gridSpec()),
      TimeRange.of(Duration.ZERO, SHORT_RUN),
      GRID_RATE,
    )
  }

  @Test
  fun `a still held for less than one frame is refused`() {
    val verdict = fixture.plan(composition(fixture.imageClip(duration = SHORT_RUN)), gridSpec())

    assertRefusedForCoveringNoFrame(verdict, TimeRange.of(Duration.ZERO, SHORT_RUN), GRID_RATE)
  }

  // Only the run the passes add up to is read. The first pass covers no slot on its own, and the
  // second reaches half way to the next.
  @Test
  fun `a looping primary whose first pass covers no frame plans on the run its passes cover`() {
    val bed = Track(listOf(fixture.audioOnlyClip(duration = 1_000.milliseconds)), content = TrackContent.Audio)
    val primary = Track(listOf(fixture.clip(trim = TimeRange.of(Duration.ZERO, SHORT_RUN))), looping = true)
    val composition = Composition(listOf(primary, bed))

    assertCoversAFrameAndPlans(
      fixture.plan(composition, gridSpec()),
      TimeRange.of(Duration.ZERO, 1_000.milliseconds),
      GRID_RATE,
    )
    val laid =
      fixture
        .resolve(composition, gridSpec())
        .tracks
        .first()
        .clips
    withClue("the first pass covers a slot on its own") {
      outputSlotsOf(laid.first().span, GRID_RATE).isEmpty().shouldBeTrue()
    }
    withClue("no pass covers a slot") { laid.any { !outputSlotsOf(it.span, GRID_RATE).isEmpty() }.shouldBeTrue() }
  }

  // A clip that covers no slot between two that do draws nothing on the backends, and the run it
  // belongs to still covers slots of its own.
  @Test
  fun `a clip that covers no frame between two that do plans`() {
    val composition =
      composition(
        fixture.clip(trim = TimeRange.of(Duration.ZERO, 1_000.milliseconds)),
        fixture.clip(trim = TimeRange.of(Duration.ZERO, SHORT_RUN)),
        fixture.clip(trim = TimeRange.of(Duration.ZERO, 1_000.milliseconds)),
      )

    val verdict = fixture.plan(composition, gridSpec())

    assertCoversAFrameAndPlans(verdict, TimeRange.of(Duration.ZERO, 2_000.milliseconds + SHORT_RUN), GRID_RATE)
    val middle =
      fixture
        .resolve(composition, gridSpec())
        .tracks
        .first()
        .clips[1]
    withClue("the middle clip covers a slot of its own") {
      outputSlotsOf(middle.span, GRID_RATE).isEmpty().shouldBeTrue()
    }
  }

  // With no rate asked for, the grid is the source's own thirty frames a second, where half a slot is
  // a little under seventeen milliseconds. Seventeen covers a slot there and would not at twenty five.
  @Test
  fun `a primary run is read against the rate the plan resolved from the source`() {
    val short = TimeRange.of(Duration.ZERO, 16.milliseconds)
    val long = TimeRange.of(Duration.ZERO, 17.milliseconds)
    withClue("the fixture's run covers a slot at $GRID_RATE fps too") {
      outputSlotsOf(long, GRID_RATE).isEmpty().shouldBeTrue()
    }

    assertRefusedForCoveringNoFrame(
      fixture.plan(composition(fixture.clip(trim = short, frameRate = 30f))),
      short,
      SOURCE_RATE,
    )
    val verdict = fixture.plan(composition(fixture.clip(trim = long, frameRate = 30f)))
    assertCoversAFrameAndPlans(verdict, long, SOURCE_RATE)
    verdict
      .shouldBeInstanceOf<Verdict.Capable>()
      .plan.output.frameRate shouldBe SOURCE_RATE
  }

  // The slots in front of a late primary are the fill, not the primary's video, so a primary whose
  // own run covers no slot is refused however many lead slots come before it.
  @Test
  fun `a late primary that covers no frame of its own is refused even though its lead covers some`() {
    val primary = Track(listOf(fixture.clip(trim = TimeRange.of(Duration.ZERO, SHORT_RUN))), start = 1_000.milliseconds)
    withClue("the fixture's start leaves no lead") { (outputSlotOf(1_000.milliseconds, GRID_RATE) > 0).shouldBeTrue() }

    val verdict = fixture.plan(Composition(listOf(primary)), gridSpec())

    assertRefusedForCoveringNoFrame(
      verdict,
      TimeRange.of(1_000.milliseconds, 1_000.milliseconds + SHORT_RUN),
      GRID_RATE,
    )
  }

  // An audio-only export writes no video, so how many frames the primary's picture would cover does
  // not decide it.
  @Test
  fun `an audio-only export of a primary that covers no frame still plans`() {
    val run = TimeRange.of(Duration.ZERO, SHORT_RUN)
    val composition = composition(fixture.clip(trim = run, audioRate = 44_100)).withAudio(AudioSpec.AudioOnly)
    withClue("the fixture's run covers a slot of its own") { outputSlotsOf(run, GRID_RATE).isEmpty().shouldBeTrue() }

    fixture.plan(composition, gridSpec()).shouldBeInstanceOf<Verdict.Capable>()
  }

  // The fast end of the speed range puts half a second of source under half a slot at the source's own rate.
  @Test
  fun `a primary run at speed that starts and ends on the same output frame is refused`() {
    val speed = CLIP_SPEED_RANGE.endInclusive
    val trim = TimeRange.of(Duration.ZERO, 500.milliseconds)
    val composition = composition(fixture.clip(trim = trim).withSpeed(speed))

    val verdict = fixture.plan(composition, ExportSpec(frameRate = SOURCE_RATE))

    val run = TimeRange.of(Duration.ZERO, outputLengthOf(500.milliseconds, speed))
    assertRefusedForCoveringNoFrame(verdict, run, SOURCE_RATE)
  }

  // Only the second clip reaches a half way point, so the run plans on the one slot it covers.
  @Test
  fun `three clips at speed covering one slot between them plan`() {
    val speed = CLIP_SPEED_RANGE.endInclusive
    val composition =
      composition(
        fixture.clip(trim = TimeRange.of(Duration.ZERO, 500.milliseconds)).withSpeed(speed),
        fixture.clip(trim = TimeRange.of(500.milliseconds, 1_000.milliseconds)).withSpeed(speed),
        fixture.clip(trim = TimeRange.of(1_000.milliseconds, 1_500.milliseconds)).withSpeed(speed),
      )

    val spec = ExportSpec(frameRate = SOURCE_RATE)
    val run = TimeRange.of(Duration.ZERO, outputLengthOf(1_500.milliseconds, speed))
    assertCoversAFrameAndPlans(fixture.plan(composition, spec), run, SOURCE_RATE)
    val covered =
      fixture
        .resolve(composition, spec)
        .tracks
        .first()
        .clips
        .map { outputSlotsOf(it.span, SOURCE_RATE).count() }
    covered shouldBe listOf(0, 1, 0)
  }

  private fun gridSpec() = ExportSpec(frameRate = GRID_RATE)

  /**
   * Checks [run] covers no output slot at [frameRate] and that [verdict] refuses the composition for it by name.
   */
  private fun assertRefusedForCoveringNoFrame(
    verdict: Verdict,
    run: TimeRange,
    frameRate: Int,
  ) {
    withClue("the fixture's run $run covers a slot at $frameRate fps") {
      outputSlotsOf(run, frameRate).isEmpty().shouldBeTrue()
    }
    val error = verdict.shouldBeInstanceOf<Verdict.Incapable>().reasons.single()
    error.shouldBeInstanceOf<FilmstripError.InvalidComposition>().message shouldBe
      "The primary track's video runs ${run.duration}, starting and ending on the same output frame at " +
      "$frameRate fps, so it covers none."
  }

  /**
   * Checks [run] covers an output slot at [frameRate] and that [verdict] plans the composition.
   */
  private fun assertCoversAFrameAndPlans(
    verdict: Verdict,
    run: TimeRange,
    frameRate: Int,
  ) {
    withClue("the fixture's run $run covers no slot at $frameRate fps") {
      (!outputSlotsOf(run, frameRate).isEmpty()).shouldBeTrue()
    }
    verdict.shouldBeInstanceOf<Verdict.Capable>()
  }

  private companion object {
    // A slot runs forty milliseconds at twenty five frames a second, so twenty is exactly half of
    // one, nineteen and ten fall short of it, and a start of fifteen puts a ten millisecond run
    // across the point half way between the first two slots.
    const val GRID_RATE = 25
    const val SOURCE_RATE = 30
    val SHORT_RUN = 10.milliseconds
    val JUST_SHORT_OF_HALF = 19.milliseconds
    val HALF_A_SLOT = 20.milliseconds
    val STRADDLING_START = 15.milliseconds
  }
}

package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.internal.outputSlotOf
import dev.jordond.filmstrip.internal.outputSlotsOf
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
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

    val verdict = fixture.plan(EditComposition(listOf(primary)), gridSpec())

    assertCoversAFrameAndPlans(verdict, TimeRange.of(STRADDLING_START, STRADDLING_START + SHORT_RUN), GRID_RATE)
  }

  // A longer track sets the composition's length, and the primary still has no frame of its own.
  @Test
  fun `a primary run that covers no frame under a longer audio track is refused`() {
    val composition =
      EditComposition(
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
    val composition = EditComposition(listOf(primary, bed))

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
    assertTrue(outputSlotsOf(laid.first().span, GRID_RATE).isEmpty(), "the first pass covers a slot on its own")
    assertTrue(laid.any { !outputSlotsOf(it.span, GRID_RATE).isEmpty() }, "no pass covers a slot")
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
    assertTrue(outputSlotsOf(middle.span, GRID_RATE).isEmpty(), "the middle clip covers a slot of its own")
  }

  // With no rate asked for, the grid is the source's own thirty frames a second, where half a slot is
  // a little under seventeen milliseconds. Seventeen covers a slot there and would not at twenty five.
  @Test
  fun `a primary run is read against the rate the plan resolved from the source`() {
    val short = TimeRange.of(Duration.ZERO, 16.milliseconds)
    val long = TimeRange.of(Duration.ZERO, 17.milliseconds)
    assertTrue(outputSlotsOf(long, GRID_RATE).isEmpty(), "the fixture's run covers a slot at $GRID_RATE fps too")

    assertRefusedForCoveringNoFrame(
      fixture.plan(composition(fixture.clip(trim = short, frameRate = 30f))),
      short,
      SOURCE_RATE,
    )
    val verdict = fixture.plan(composition(fixture.clip(trim = long, frameRate = 30f)))
    assertCoversAFrameAndPlans(verdict, long, SOURCE_RATE)
    assertIs<Verdict.Capable>(verdict).plan.output.frameRate shouldBe SOURCE_RATE
  }

  // The slots in front of a late primary are the fill, not the primary's video, so a primary whose
  // own run covers no slot is refused however many lead slots come before it.
  @Test
  fun `a late primary that covers no frame of its own is refused even though its lead covers some`() {
    val primary = Track(listOf(fixture.clip(trim = TimeRange.of(Duration.ZERO, SHORT_RUN))), start = 1_000.milliseconds)
    assertTrue(outputSlotOf(1_000.milliseconds, GRID_RATE) > 0, "the fixture's start leaves no lead")

    val verdict = fixture.plan(EditComposition(listOf(primary)), gridSpec())

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
    assertTrue(outputSlotsOf(run, GRID_RATE).isEmpty(), "the fixture's run covers a slot of its own")

    assertIs<Verdict.Capable>(fixture.plan(composition, gridSpec()))
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
    assertTrue(outputSlotsOf(run, frameRate).isEmpty(), "the fixture's run $run covers a slot at $frameRate fps")
    val error = assertIs<Verdict.Incapable>(verdict).reasons.single()
    assertIs<ExportError.InvalidComposition>(error).message shouldBe
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
    assertTrue(!outputSlotsOf(run, frameRate).isEmpty(), "the fixture's run $run covers no slot at $frameRate fps")
    assertIs<Verdict.Capable>(verdict)
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

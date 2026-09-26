package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.export.AudioCodec
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.test.LoopingCases
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * How the planner lays tracks and loops against the composition.
 *
 * Covers which extra tracks are refused, the loop pass ceiling, how fades read across a looping run and its cut last
 * pass, and tracks that start before the composition.
 */
class TrackPlanTest {
  private val fixture = PlannerFixture()

  @Test
  fun `a second video track is refused by name`() {
    val second = Track(listOf(fixture.clip()), content = TrackContent.AudioAndVideo)
    val verdict = fixture.plan(EditComposition(listOf(Track(listOf(fixture.clip())), second)))

    val error = assertIs<Verdict.Incapable>(verdict).reasons.single()
    assertIs<ExportError.InvalidComposition>(error).message shouldBe
      "This backend renders video from the primary track only. A second video track needs a " +
      "compositor, which has not landed here. A second audio-only track works."
  }

  @Test
  fun `a second audio-only track is accepted and does not bound the composition`() {
    val bed =
      Track(
        listOf(fixture.clip(duration = 60_000.milliseconds, audioRate = 44_100)),
        content = TrackContent.Audio,
        looping = true,
      )
    val verdict = fixture.plan(EditComposition(listOf(Track(listOf(fixture.clip())), bed)))

    val plan = assertIs<Verdict.Capable>(verdict).plan
    plan.output.audioCodec shouldBe AudioCodec.Aac
  }

  @Test
  fun `a composition where every track loops is refused by name`() {
    val verdict = fixture.plan(EditComposition(listOf(Track(listOf(fixture.clip()), looping = true))))

    val error = assertIs<Verdict.Incapable>(verdict).reasons.single()
    assertIs<ExportError.InvalidComposition>(error).message shouldBe
      "Every track loops, so the composition has nothing to bound it."
  }

  @Test
  fun `a looping track well under the pass ceiling is laid`() {
    val laid =
      fixture
        .resolve(loopingFor(passes = MAX_LAID_PASSES / 2))
        .tracks
        .last()
        .clips

    laid.size shouldBe MAX_LAID_PASSES / 2
  }

  // The ceiling is the last count that lays rather than the first that refuses, so a track sitting
  // exactly on it still plans.
  @Test
  fun `a looping track at the pass ceiling is laid`() {
    val laid =
      fixture
        .resolve(loopingFor(passes = MAX_LAID_PASSES))
        .tracks
        .last()
        .clips

    laid.size shouldBe MAX_LAID_PASSES
  }

  // The bed is the second track of the composition, so the message names index one rather than
  // leaving the caller to work out which of the two ran away.
  @Test
  fun `a looping track one pass over the ceiling is refused by name`() {
    val verdict = fixture.plan(loopingFor(passes = MAX_LAID_PASSES + 1))

    val error = assertIs<Verdict.Incapable>(verdict).reasons.single()
    assertIs<ExportError.InvalidComposition>(error).message shouldBe
      "The track at index 1 lays more than $MAX_LAID_PASSES passes, which is the most one track may lay. A " +
      "looping track repeats until the composition ends, so a short clip under a long composition asks for a " +
      "pass per repeat. A track laid once lays one pass per clip."
  }

  // A fifty millisecond bed under a ten minute composition, which is the shape that makes the
  // ceiling worth having.
  @Test
  fun `a looping track far over the ceiling is refused by name`() {
    val verdict = fixture.plan(loopingFor(passes = MAX_LAID_PASSES * 6))

    val error = assertIs<Verdict.Incapable>(verdict).reasons.single()
    assertIs<ExportError.InvalidComposition>(error).message shouldBe
      "The track at index 1 lays more than $MAX_LAID_PASSES passes, which is the most one track may lay. A " +
      "looping track repeats until the composition ends, so a short clip under a long composition asks for a " +
      "pass per repeat. A track laid once lays one pass per clip."
  }

  @Test
  fun `a looping track lays every pass and cuts the last where the composition ends`() {
    val laid =
      fixture
        .resolve(looping())
        .tracks
        .last()
        .clips

    laid.size shouldBe 4
    laid.map { it.span.start } shouldBe
      listOf(BED_START, 2_400.milliseconds, 4_100.milliseconds, 5_800.milliseconds)
    laid.map { it.span.endExclusive } shouldBe
      listOf(2_400.milliseconds, 4_100.milliseconds, 5_800.milliseconds, PRIMARY_LENGTH)
    laid.forEach { it.start shouldBe BED_TRIM_START }
    laid.dropLast(1).forEach { it.end shouldBe BED_TRIM_END }
    laid.last().end shouldBe BED_TRIM_START + CUT_PASS
    laid.map { it.sourceIndex } shouldBe listOf(0, 0, 0, 0)
  }

  @Test
  fun `a looping track's fade in is measured over the whole run rather than replayed per pass`() {
    // The ramp is longer than a pass, so pass two opens part way up it. Read at a quarter and three
    // quarters of the ramp as well as its middle, since its ends agree either way.
    val laid =
      fixture
        .resolve(looping(fadeIn = 3_000.milliseconds))
        .tracks
        .last()
        .clips

    laid.first().gain.gainAt(Duration.ZERO) shouldBe (0f plusOrMinus ResolvedGain.PRODUCT_TOLERANCE)
    gainAtRun(laid, 750.milliseconds) shouldBe (0.25f plusOrMinus ResolvedGain.PRODUCT_TOLERANCE)
    gainAtRun(laid, 1_500.milliseconds) shouldBe (0.5f plusOrMinus ResolvedGain.PRODUCT_TOLERANCE)
    gainAtRun(laid, 2_250.milliseconds) shouldBe (0.75f plusOrMinus ResolvedGain.PRODUCT_TOLERANCE)
    // Pass two opens where the run reached, not back at the bottom of the ramp.
    val intoRamp = (PASS / 3_000.milliseconds).toFloat()
    laid[1].gain.gainAt(Duration.ZERO) shouldBe (intoRamp plusOrMinus ResolvedGain.PRODUCT_TOLERANCE)
  }

  @Test
  fun `a looping track's fade out ramps at the composition's end`() {
    val laid =
      fixture
        .resolve(looping(fadeOut = 2_000.milliseconds))
        .tracks
        .last()
        .clips

    gainAtRun(laid, 4_600.milliseconds) shouldBe (1f plusOrMinus ResolvedGain.PRODUCT_TOLERANCE)
    gainAtRun(laid, 5_100.milliseconds) shouldBe (0.75f plusOrMinus ResolvedGain.PRODUCT_TOLERANCE)
    gainAtRun(laid, 5_600.milliseconds) shouldBe (0.5f plusOrMinus ResolvedGain.PRODUCT_TOLERANCE)
    gainAtRun(laid, 6_100.milliseconds) shouldBe (0.25f plusOrMinus ResolvedGain.PRODUCT_TOLERANCE)
    gainAtRun(laid, RUN) shouldBe (0f plusOrMinus ResolvedGain.PRODUCT_TOLERANCE)
  }

  @Test
  fun `a clip fade on a looping track replays on every pass`() {
    val laid =
      fixture
        .resolve(looping(clipFadeIn = 400.milliseconds))
        .tracks
        .last()
        .clips

    laid.forEach { pass ->
      pass.gain.gainAt(Duration.ZERO) shouldBe (0f plusOrMinus ResolvedGain.PRODUCT_TOLERANCE)
      pass.gain.gainAt(100.milliseconds) shouldBe (0.25f plusOrMinus ResolvedGain.PRODUCT_TOLERANCE)
      pass.gain.gainAt(300.milliseconds) shouldBe (0.75f plusOrMinus ResolvedGain.PRODUCT_TOLERANCE)
      pass.gain.gainAt(400.milliseconds) shouldBe (1f plusOrMinus ResolvedGain.PRODUCT_TOLERANCE)
    }
  }

  @Test
  fun `the pass a loop cuts short ends on what its clip curve reads there`() {
    // The clip's own fade out runs over the last 400ms of its trim, and the cut lands 200ms into
    // that, so the pass stops half way down rather than at silence.
    val laid =
      fixture
        .resolve(looping(clipFadeOut = 400.milliseconds))
        .tracks
        .last()
        .clips

    laid.dropLast(1).forEach { it.gain.gainAt(PASS) shouldBe (0f plusOrMinus ResolvedGain.PRODUCT_TOLERANCE) }
    laid.last().gain.end shouldBe CUT_PASS
    laid.last().gain.gainAt(CUT_PASS) shouldBe (0.5f plusOrMinus ResolvedGain.PRODUCT_TOLERANCE)
  }

  @Test
  fun `a looping track never lengthens the composition`() {
    val plan = fixture.resolve(looping())

    plan.duration shouldBe PRIMARY_LENGTH
    plan.tracks
      .last()
      .clips
      .last()
      .span.endExclusive shouldBe PRIMARY_LENGTH
  }

  // buildVideo on the ffmpeg backend would emit a concat of nothing, and every other backend would
  // write a file with no picture in it, neither of them saying why.
  @Test
  fun `a looping primary that starts at the composition's end is refused`() {
    val bed = Track(listOf(fixture.clip(duration = 4_000.milliseconds)), content = TrackContent.Audio)
    val primary = Track(listOf(fixture.clip()), start = 4_000.milliseconds, looping = true)

    assertIs<Verdict.Incapable>(fixture.plan(EditComposition(listOf(primary, bed))))
  }

  /**
   * A bed laid four times under a primary, with a track offset, a pass and a run that no two of
   * divide evenly, so the last pass is always cut.
   */
  private fun looping(
    fadeIn: Duration = Duration.ZERO,
    fadeOut: Duration = Duration.ZERO,
    clipFadeIn: Duration = Duration.ZERO,
    clipFadeOut: Duration = Duration.ZERO,
  ): EditComposition {
    val bedClip =
      fixture.clip(
        duration = 3_000.milliseconds,
        trim = LoopingCases.BED_TRIM,
        fadeIn = clipFadeIn,
        fadeOut = clipFadeOut,
      )
    return EditComposition(
      listOf(
        Track(listOf(fixture.clip(duration = PRIMARY_LENGTH))),
        Track(
          clips = listOf(bedClip),
          content = TrackContent.Audio,
          start = BED_START,
          looping = true,
          fadeIn = fadeIn,
          fadeOut = fadeOut,
        ),
      ),
    )
  }

  /**
   * A bed looping a [LOOP_PASS] long clip under a primary track long enough to ask for [passes] of
   * them, which is the shape that reaches the pass ceiling.
   */
  private fun loopingFor(passes: Int): EditComposition {
    val bedClip =
      fixture.clip(duration = 3_000.milliseconds, trim = TimeRange.of(Duration.ZERO, LOOP_PASS), audioRate = 44_100)
    return EditComposition(
      listOf(
        Track(listOf(fixture.clip(duration = LOOP_PASS * passes))),
        Track(clips = listOf(bedClip), content = TrackContent.Audio, looping = true),
      ),
    )
  }

  // The folded gain [into] the run, read off whichever pass holds that instant.
  private fun gainAtRun(
    laid: List<ResolvedClip>,
    into: Duration,
  ): Float {
    val at = BED_START + into
    val pass = laid.lastOrNull { it.span.start <= at } ?: laid.first()
    return pass.gain.gainAt(at - pass.span.start)
  }

  @Test
  fun `a primary track that starts before the composition is refused by name`() {
    val composition = EditComposition(listOf(Track(listOf(fixture.clip()), start = (-500).milliseconds)))

    val error = assertIs<Verdict.Incapable>(fixture.plan(composition)).reasons.single()
    assertIs<ExportError.InvalidComposition>(error).message shouldBe
      "A track's start is negative, so it would begin before the composition does. A start is " +
      "measured from the start of the composition and has to be zero or later."
  }

  // Every track is measured from the same start, so a bed that starts early is refused the same way
  // a primary track is.
  @Test
  fun `an audio track that starts before the composition is refused by name`() {
    val composition =
      EditComposition(
        listOf(
          Track(listOf(fixture.clip())),
          Track(listOf(fixture.clip(audioRate = 48_000)), content = TrackContent.Audio, start = (-500).milliseconds),
        ),
      )

    val error = assertIs<Verdict.Incapable>(fixture.plan(composition)).reasons.single()
    assertIs<ExportError.InvalidComposition>(error).message shouldBe
      "A track's start is negative, so it would begin before the composition does. A start is " +
      "measured from the start of the composition and has to be zero or later."
  }

  private companion object {
    // Case one of the looping suite every backend measures, so the schedule the looping tests spell
    // out is the one the four export suites build their edit from. No two of these divide evenly, so
    // a pass counted from the wrong place lands somewhere the readings can see, and the run always
    // cuts its last pass.
    val PRIMARY_LENGTH = LoopingCases.PRIMARY_RUN
    val BED_START = LoopingCases.BED_START
    val BED_TRIM_START = LoopingCases.BED_TRIM.start
    val PASS = LoopingCases.BED_LENGTHS.single()
    val BED_TRIM_END = BED_TRIM_START + PASS
    val RUN = PRIMARY_LENGTH - BED_START
    val CUT_PASS = RUN - PASS * 3

    // Short enough that a composition of ordinary length reaches the pass ceiling, and a whole
    // number of milliseconds so a count derived from it lands on an exact pass.
    val LOOP_PASS = 50.milliseconds
  }
}

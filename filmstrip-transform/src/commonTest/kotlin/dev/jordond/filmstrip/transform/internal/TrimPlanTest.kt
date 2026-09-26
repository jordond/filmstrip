package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.export.CopyBlocker
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How the planner trims a clip and snaps a copied cut back to a sync sample.
 *
 * Covers the snap tolerance, the length a copy actually writes, what a trim costs the copy, and trims that run past the
 * source or keep nothing.
 */
class TrimPlanTest {
  private val fixture = PlannerFixture()

  @Test
  fun `a trim shortens the composition and reports nothing`() {
    val trimmed = fixture.clip(trim = TimeRange.of(1_000.milliseconds, 3_000.milliseconds))
    val verdict = fixture.plan(composition(trimmed))

    assertIs<Verdict.Capable>(verdict)
    firstClipOf(fixture.resolve(composition(trimmed))).startsAtKeyFrame shouldBe false
    fixture.resolve(composition(trimmed)).duration shouldBe 2_000.milliseconds
  }

  @Test
  fun `a cut that already lands on a sync sample copies without moving`() {
    val trimmed = fixture.clip(trim = TimeRange.of(1_000.milliseconds, 3_000.milliseconds))
    val composition = composition(trimmed)
    val openings = openings(trimmed, 1_000.milliseconds)

    val verdict = fixture.plan(composition, openings = openings)
    val resolved = fixture.resolve(composition, openings = openings)

    assertIs<Verdict.Capable>(verdict)
    verdict.plan.path shouldBe ExportPath.Transmux
    verdict.plan.copyBlockedBy.shouldBeEmpty()
    firstClipOf(resolved).startsAtKeyFrame shouldBe true
    firstClipOf(resolved).start shouldBe 1_000.milliseconds
    resolved.duration shouldBe 2_000.milliseconds
  }

  @Test
  fun `a cut moves back to the sync sample when the tolerance reaches it`() {
    val trimmed =
      fixture.clip(trim = TimeRange.of(1_000.milliseconds, 3_000.milliseconds), snapWithin = 500.milliseconds)
    val composition = composition(trimmed)
    val resolved = fixture.resolve(composition, openings = openings(trimmed, 500.milliseconds))

    firstClipOf(resolved).start shouldBe 500.milliseconds
    firstClipOf(resolved).startsAtKeyFrame shouldBe true
    // The plan reports what a backend writes, so the extra half second the copy opened on counts.
    resolved.duration shouldBe 2_500.milliseconds
  }

  // The plan is the only thing a caller sizing a progress bar or an estimate has to read, and its
  // own composition is the input echoed back, so a snap that moved the cut has to reach this.
  @Test
  fun `the plan reports the length the copy writes rather than the length asked for`() {
    val trimmed =
      fixture.clip(trim = TimeRange.of(1_000.milliseconds, 3_000.milliseconds), snapWithin = 1.seconds)
    val composition = composition(trimmed)

    val verdict = fixture.plan(composition, openings = openings(trimmed, 500.milliseconds))

    assertIs<Verdict.Capable>(verdict)
    verdict.plan.duration shouldBe 2_500.milliseconds
    composition.duration shouldBe 2_000.milliseconds
  }

  // The tolerance is a distance, so a probe halfway along it has to answer the same way both ends
  // do. Read at 250ms, which only a tolerance measured against the real gap gets right.
  @Test
  fun `a tolerance is measured against how far the sync sample actually sits back`() {
    val gap = 500.milliseconds
    listOf(250.milliseconds to false, gap to true, 750.milliseconds to true).forEach { (tolerance, snaps) ->
      val trimmed = fixture.clip(trim = TimeRange.of(1_000.milliseconds, 3_000.milliseconds), snapWithin = tolerance)
      val composition = composition(trimmed)
      val resolved = fixture.resolve(composition, openings = openings(trimmed, 1_000.milliseconds - gap))

      firstClipOf(resolved).startsAtKeyFrame shouldBe snaps
      firstClipOf(resolved).start shouldBe if (snaps) 500.milliseconds else 1_000.milliseconds
    }
  }

  @Test
  fun `a cut the tolerance cannot reach stays put and says the trim cost the copy`() {
    val trimmed =
      fixture.clip(trim = TimeRange.of(1_000.milliseconds, 3_000.milliseconds), snapWithin = 100.milliseconds)
    val composition = composition(trimmed)
    val openings = openings(trimmed, 500.milliseconds)

    val verdict = fixture.plan(composition, openings = openings)

    assertIs<Verdict.Capable>(verdict)
    verdict.plan.path shouldBe ExportPath.Transcode
    verdict.plan.copyBlockedBy shouldBe listOf(CopyBlocker.TrimNotOnSyncSample)
    firstClipOf(fixture.resolve(composition, openings = openings)).start shouldBe 1_000.milliseconds
  }

  @Test
  fun `a source that names no sync sample says the trim cost the copy`() {
    val composition = composition(fixture.clip(trim = TimeRange.of(1_000.milliseconds, 3_000.milliseconds)))

    val verdict = fixture.plan(composition)

    assertIs<Verdict.Capable>(verdict)
    verdict.plan.copyBlockedBy shouldBe listOf(CopyBlocker.TrimNotOnSyncSample)
  }

  // A snap is what the clip asked for, so it is not a fallback and strict has nothing to refuse.
  @Test
  fun `strict accepts a cut that moved back to a sync sample`() {
    val trimmed =
      fixture.clip(trim = TimeRange.of(1_000.milliseconds, 3_000.milliseconds), snapWithin = 1.seconds)
    val composition = composition(trimmed)

    val verdict = fixture.plan(composition, ExportSpec(strict = true), openings = openings(trimmed, 500.milliseconds))

    assertIs<Verdict.Capable>(verdict)
    verdict.plan.path shouldBe ExportPath.Transmux
  }

  @Test
  fun `an untouched export copies and blames nothing`() {
    val verdict = fixture.plan(composition(fixture.clip()))

    assertIs<Verdict.Capable>(verdict)
    verdict.plan.path shouldBe ExportPath.Transmux
    verdict.plan.copyBlockedBy.shouldBeEmpty()
  }

  // Upload trips several terms at once, and a caller who only heard about the first would drop a
  // field and still not get the copy. The frame is not among them: 1080p asked of a 1080p source
  // resizes nothing, and it is naming the height at all that costs the copy.
  @Test
  fun `an export names every term that cost it the copy`() {
    val verdict = fixture.plan(composition(fixture.clip()), ExportSpec.Upload)

    assertIs<Verdict.Capable>(verdict)
    verdict.plan.path shouldBe ExportPath.Transcode
    verdict.plan.copyBlockedBy shouldContainExactlyInAnyOrder
      listOf(CopyBlocker.VideoCodecNamed, CopyBlocker.TargetHeightSet, CopyBlocker.BitrateSet)
  }

  /**
   * The map [ExportPlanner.negotiate] reads a copy's opening from, with [clip]'s source pointed at
   * [opening].
   */
  private fun openings(
    clip: Clip,
    opening: Duration,
  ): Map<MediaSource, Duration> = mapOf(clip.source to opening)

  @Test
  fun `a trim past the end of the source is clamped to it`() {
    fixture
      .resolve(composition(fixture.clip(trim = TimeRange.of(1_000.milliseconds, 60_000.milliseconds))))
      .duration shouldBe 5_000.milliseconds
  }

  @Test
  fun `a trim that keeps nothing is refused`() {
    assertIs<Verdict.Incapable>(
      fixture.plan(composition(fixture.clip(trim = TimeRange.of(6_000.milliseconds, 6_000.milliseconds)))),
    )
  }
}

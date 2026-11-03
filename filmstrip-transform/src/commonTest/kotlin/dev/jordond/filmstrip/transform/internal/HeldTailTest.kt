package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotsOf
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.test.LoopingCases
import dev.jordond.filmstrip.transform.internal.negotiation.Ask
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The slots every backend holds the primary's last frame over, and the frame count the video track closes on, read off
 * compositions the planner laid itself.
 */
class HeldTailTest {
  private val fixture = PlannerFixture()

  @Test
  fun `a non-looping audio track that outlasts the primary leaves a held tail`() {
    val laid = negotiate(underBed(fixture.clip(duration = 1.seconds), bed = 5.seconds))

    laid.heldTail shouldBe (outputSlotOf(1.seconds, GRID_RATE) until outputSlotOf(5.seconds, GRID_RATE))
    laid.videoSlots shouldBe outputSlotOf(5.seconds, GRID_RATE)
    laid.heldTail.last + 1 shouldBe laid.videoSlots
    withClue("the middle of the bed is not held") {
      (outputSlotOf(3.seconds, GRID_RATE) in laid.heldTail).shouldBeTrue()
    }
  }

  // At 25 fps the primary ends a quarter of a slot past slot 25 and the bed a sixth of one past slot 125, so a tail
  // measured from the clip's own length rather than the slot its pass ends on would open or close a slot out.
  @Test
  fun `a held tail opens on the slot the primary's last pass ends on`() {
    val laid = negotiate(underBed(fixture.clip(duration = 1_010.milliseconds), bed = 5_006.milliseconds))
    val lastPass = laid.primary.clips.last()

    laid.heldTail.first shouldBe outputSlotsOf(lastPass.span, GRID_RATE).last + 1
    laid.heldTail.last + 1 shouldBe outputSlotOf(5_006.milliseconds, GRID_RATE)
  }

  // Read slot by slot rather than at the two ends, so a tail that skipped or doubled a slot anywhere in its middle
  // shows up here.
  @Test
  fun `the lead the passes and the held tail fill the video track between them`() {
    val primary =
      Track(
        listOf(fixture.clip(duration = 1_010.milliseconds), fixture.clip(duration = 770.milliseconds)),
        start = 300.milliseconds,
      )
    val laid = negotiate(EditComposition(listOf(primary, bed(5_006.milliseconds))))
    val laidPrimary = laid.primary
    val lead = 0L until outputSlotOf(laidPrimary.start, GRID_RATE)
    val owners = listOf(lead) + laidPrimary.clips.map { outputSlotsOf(it.span, GRID_RATE) } + listOf(laid.heldTail)

    (0L until laid.videoSlots).forEach { slot ->
      withClue("slot $slot is not covered exactly once") { owners.count { slot in it } shouldBe 1 }
    }
    withClue("the middle of the bed is not held") {
      (outputSlotOf(3.seconds, GRID_RATE) in laid.heldTail).shouldBeTrue()
    }
  }

  @Test
  fun `a late audio track's end sets the tail`() {
    val late = Track(listOf(fixture.audioOnlyClip(1.seconds)), content = TrackContent.Audio, start = 4_500.milliseconds)
    val laid = negotiate(EditComposition(listOf(Track(listOf(fixture.clip(duration = 1.seconds))), late)))

    laid.heldTail.first shouldBe outputSlotOf(1.seconds, GRID_RATE)
    laid.heldTail.last + 1 shouldBe outputSlotOf(5_500.milliseconds, GRID_RATE)
    laid.videoSlots shouldBe outputSlotOf(5_500.milliseconds, GRID_RATE)
  }

  @Test
  fun `a looping audio track leaves no tail`() {
    val looping = Track(listOf(fixture.audioOnlyClip(3.seconds)), content = TrackContent.Audio, looping = true)
    val laid = negotiate(EditComposition(listOf(Track(listOf(fixture.clip(duration = 1.seconds))), looping)))

    withClue("a looping bed left ${laid.heldTail}") { laid.heldTail.isEmpty().shouldBeTrue() }
    laid.videoSlots shouldBe outputSlotOf(1.seconds, GRID_RATE)
  }

  // Case three of the looping suite: the primary repeats under a longer audio-only track, so its last pass is cut
  // on the composition's end.
  @Test
  fun `a looping primary leaves no tail`() {
    val primary = Track(listOf(fixture.clip(duration = 3.seconds, trim = LoopingCases.VIDEO_TRIM)), looping = true)
    val underlay = bed(LoopingCases.UNDERLAY_RUN)
    val laid = negotiate(EditComposition(listOf(primary, underlay)))

    withClue("a looping primary left ${laid.heldTail}") { laid.heldTail.isEmpty().shouldBeTrue() }
    laid.videoSlots shouldBe outputSlotOf(LoopingCases.UNDERLAY_RUN, GRID_RATE)
    laid.videoSlots shouldBe slotsCoveredBy(laid.primary)
  }

  @Test
  fun `a copy plan leaves no tail`() {
    val laid = negotiate(EditComposition(listOf(Track(listOf(fixture.clip())))), ExportSpec())
    val frameRate = laid.output.frameRate

    laid.path shouldBe ExportPath.Copy
    withClue("a copy left ${laid.heldTail}") { laid.heldTail.isEmpty().shouldBeTrue() }
    laid.videoSlots shouldBe outputSlotOf(laid.duration, frameRate)
  }

  @Test
  fun `a still primary under a bed leaves a tail`() {
    val laid = negotiate(underBed(fixture.imageClip(duration = 1.seconds), bed = 3.seconds))

    laid.heldTail shouldBe (outputSlotOf(1.seconds, GRID_RATE) until outputSlotOf(3.seconds, GRID_RATE))
    withClue("the middle of the bed is not held") {
      (outputSlotOf(2.seconds, GRID_RATE) in laid.heldTail).shouldBeTrue()
    }
  }

  @Test
  fun `a late primary keeps its lead apart from its tail`() {
    val primary = Track(listOf(fixture.clip(duration = 1.seconds)), start = 500.milliseconds)
    val laid = negotiate(EditComposition(listOf(primary, bed(5.seconds))))
    val covered = slotsCoveredBy(laid.primary)

    laid.heldTail.first shouldBe outputSlotOf(1_500.milliseconds, GRID_RATE)
    laid.videoSlots - covered - laid.heldTail.count() shouldBe outputSlotOf(500.milliseconds, GRID_RATE)
  }

  @Test
  fun `videoSlots equals the lead plus every pass's slots when nothing outlasts the primary`() {
    val primary =
      Track(
        listOf(fixture.clip(duration = 1_010.milliseconds), fixture.clip(duration = 770.milliseconds)),
        start = 300.milliseconds,
      )
    val laid = negotiate(EditComposition(listOf(primary, bed(1.seconds))))

    withClue("a shorter bed left ${laid.heldTail}") { laid.heldTail.isEmpty().shouldBeTrue() }
    laid.videoSlots shouldBe outputSlotOf(laid.primary.start, GRID_RATE) + slotsCoveredBy(laid.primary)
  }

  @Test
  fun `a negotiated composition reads its tail off the shared functions`() {
    val negotiated = negotiate(underBed(fixture.clip(duration = 1_010.milliseconds), bed = 5_006.milliseconds))

    negotiated.heldTail shouldBe heldTailOf(negotiated.primary, negotiated.duration, GRID_RATE)
    negotiated.videoSlots shouldBe videoSlotsOf(negotiated.duration, GRID_RATE)
  }

  private fun negotiate(
    composition: EditComposition,
    spec: ExportSpec = ExportSpec(frameRate = GRID_RATE),
  ) = fixture
    .planner(resolvers = emptyList())
    .negotiate(composition, Ask(spec, fixture.device(), fixture.infos(composition)))
    .composition
    .shouldNotBeNull()

  private val ResolvedComposition.primary: ResolvedTrack
    get() = tracks.first()

  private fun slotsCoveredBy(track: ResolvedTrack): Long =
    track.clips.sumOf { outputSlotsOf(it.span, GRID_RATE).count().toLong() }

  private fun underBed(
    primary: Clip,
    bed: Duration,
  ) = EditComposition(listOf(Track(listOf(primary)), bed(bed)))

  private fun bed(duration: Duration) = Track(listOf(fixture.audioOnlyClip(duration)), content = TrackContent.Audio)

  private companion object {
    const val GRID_RATE = 25
  }
}

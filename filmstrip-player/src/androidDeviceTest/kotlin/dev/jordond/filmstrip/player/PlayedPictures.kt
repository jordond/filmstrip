package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.test.TestFrame
import dev.jordond.filmstrip.test.assertFramesSimilar
import dev.jordond.filmstrip.test.compareFrames
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe

/**
 * What the surface showed while a block ran, from [HeldRig.picturesPlayingUntilPast].
 *
 * @property pictures Each picture drawn, in order, with the one it settled on last, the standing one left out.
 * @property standing The picture on the surface as the block began.
 * @property opensOnStanding Whether the first copy taken was pixel for pixel [standing], and so was left out.
 */
internal class PlayedPictures(
  val pictures: List<TestFrame>,
  val standing: TestFrame,
  val opensOnStanding: Boolean,
)

/**
 * Holds [played], what the surface showed after a play from slot [from] that was paused, to being the clip's own
 * slots, drawn whole, in the order they are played.
 *
 * Each picture is matched to the export of the slot nearest it among the slots from the first of the hold [from] is
 * in to [reported]. That window is the whole of what a play can show: the hold's sample is drawn again from its first
 * slot, and a pause lands the surface on the slot the playhead reads, so nothing past it is drawn. A picture no slot
 * in it resembles is a half-drawn one, or one of the sample before the hold. The first picture has to be a slot of
 * that hold, which is the sample being drawn again and not the play carrying on from the slot after it, unless that
 * redraw was pixel for pixel the picture already standing and so never listed. The matched
 * slots then have to run forward, never back to an earlier one, and the last has to be [reported], a slot after
 * [from]. A slot missing from the run is not held against the play, since the copies can miss a frame.
 *
 * @param seekLanding Whether a seek to [from] was asked for right before the play, so its landing may be drawn first.
 * @return the slot the surface settled on.
 */
internal suspend fun HeldRig.shouldHavePlayedOn(
  played: PlayedPictures,
  from: Int,
  reported: Int,
  seekLanding: Boolean = false,
): Int {
  val pictures = played.pictures
  val hold = holdOf(from)
  val window = hold.first..reported
  // The hold's sample drawn again is pixel for pixel the picture that stood before the play, so a copy cannot tell
  // the two apart and drops it. Only then may the first picture listed be the slot right after the hold, and the
  // order of the rest still catches a play that showed a later slot before going back to the hold.
  val redrawUnseen =
    played.opensOnStanding &&
      hold.any { slot -> compareFrames(cachedExport(slot), played.standing).psnrDb >= MIN_PSNR_DB }
  withClue("the play drew no picture") { pictures.isNotEmpty().shouldBeTrue() }
  val matched =
    pictures.map { picture ->
      window.maxBy { slot -> compareFrames(cachedExport(slot), picture).psnrDb }.also { slot ->
        assertFramesSimilar(
          cachedExport(slot),
          picture,
          MIN_PSNR_DB,
          MIN_SSIM,
          "a picture drawn after the play is no slot of $window, its nearest being slot $slot",
        )
      }
    }
  // A seek asked for right before the play can land on the paused player first and draw [from] before the play takes
  // effect. The play then replays the hold from its first slot, as from any paused later slot of it, so that landing
  // stands before the play. A play that shows a later slot and then goes back to the hold still fails the order.
  val run =
    if (seekLanding && matched.size > 1 && matched[0] == from && matched[1] < from) matched.drop(1) else matched
  withClue("the slots drawn after the play, in order, were $matched") {
    withClue("the first is no slot of the hold $hold, the hold's own redraw showing as the picture before") {
      val opening = if (redrawUnseen) hold.first..hold.last + 1 else hold
      (run.first() in opening).shouldBeTrue()
    }
    run.zipWithNext().all { (before, after) -> after >= before }.shouldBeTrue()
    run.last() shouldBe reported
    (reported > from).shouldBeTrue()
  }
  return matched.last()
}

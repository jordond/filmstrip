package dev.jordond.filmstrip.player

import android.view.SurfaceView
import dev.jordond.filmstrip.player.contract.asTestFrame
import dev.jordond.filmstrip.player.contract.awaitFrame
import dev.jordond.filmstrip.test.assertFramesDiffer
import dev.jordond.filmstrip.test.assertFramesSimilar
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * What a `SurfaceView` shows on every slot of a held sample, against the export of that slot.
 *
 * The clip pans, so each slot of a hold is a picture of its own in the export. A paused preview rests on a slot by a
 * seek, a step or a scrub, and has to draw that slot, whichever of a hold's slots it is. Playing on from a hold has to
 * draw its sample again, and every picture it draws has to be a slot of the clip, in order.
 */
class AndroidHeldSlotPreviewMediaTest {
  @Test
  fun aSeekIntoAHoldDrawsThatSlotAtHalfSpeed() = onHeldSurface(Case.Half) { rig -> rig.seekAndShow(rig.repeatSlot()) }

  @Test
  fun aSeekIntoAHoldDrawsThatSlotAtAQuarterSpeed() =
    onHeldSurface(Case.Quarter) { rig -> rig.seekAndShow(rig.repeatSlot()) }

  @Test
  fun aSeekIntoAHoldOfASlowerSourceDrawsThatSlot() =
    onHeldSurface(Case.Slower) { rig -> rig.seekAndShow(rig.repeatSlot()) }

  @Test
  fun aSeekToTheSlotAHeldSampleIsFirstWrittenOnDrawsThatSlot() =
    onHeldSurface(Case.Half) { rig -> rig.seekAndShow(rig.firstSlot()) }

  @Test
  fun aSeekIntoTheHoldAtTheStartOfTheClipDrawsThatSlot() = onHeldSurface(Case.Half) { rig -> rig.seekAndShow(1) }

  @Test
  fun aSeekIntoTheHoldAtTheEndOfTheClipDrawsThatSlot() =
    onHeldSurface(Case.Half) { rig -> rig.seekAndShow(rig.lastSlot()) }

  @Test
  fun aSeekFromOneRepeatToAnotherOfTheSameHoldDrawsEachOfThem() =
    onHeldSurface(Case.Quarter) { rig ->
      val first = rig.firstSlot()
      rig.seekAndShow(first + 1)
      rig.seekAndShow(first + 2)
      rig.seekAndShow(first + 1)
    }

  @Test
  fun steppingForwardAcrossAHoldDrawsEverySlotOfIt() =
    onHeldSurface(Case.Half) { rig ->
      val first = rig.firstSlot()
      rig.seekAndShow(first)
      (1..STEPS).forEach { rig.stepAndShow(first + it) }
    }

  @Test
  fun steppingBackAcrossAHoldDrawsEverySlotOfIt() =
    onHeldSurface(Case.Half) { rig ->
      val last = rig.firstSlot() + STEPS
      rig.seekAndShow(last)
      (1..STEPS).forEach { rig.stepAndShow(last - it, forward = false) }
    }

  @Test
  fun aScrubOntoARepeatDrawsThatSlot() =
    onHeldSurface(Case.Half) { rig ->
      rig.ready()
      rig.player.beginScrub()
      rig.seekAndSettle(rig.repeatSlot(), SeekAccuracy.Nearest)
      rig.player.endScrub()
      rig.settle(rig.repeatSlot())

      rig.shouldShow(rig.repeatSlot())
    }

  // The play is asked for right behind the seek, so the seek is handed to media3 once the player is playing and has
  // no slot of the hold to be pinned to.
  @Test
  fun aSeekIntoAHoldFollowedByAPlayDrawsTheSlotsAfterIt() =
    onHeldSurface(Case.Half) { rig ->
      rig.ready()
      val slot = rig.repeatSlot()
      rig.player.seekTo(rig.slotTime(slot), SeekAccuracy.Exact)
      val pictures = rig.picturesPlayingUntilPast(slot + HOLD_PAST)

      rig.shouldHavePlayedOn(pictures, from = slot, reported = rig.reportedSlot())
    }

  // A pause that rested on a repeat left only that slot drawn, so playing on from it has to decode the sample again.
  @Test
  fun aPlayFromAPausedRepeatDrawsTheSlotsAfterIt() =
    onHeldSurface(Case.Half) { rig ->
      val slot = rig.repeatSlot()
      rig.seekAndShow(slot)
      val pictures = rig.picturesPlayingUntilPast(slot + HOLD_PAST)

      rig.shouldHavePlayedOn(pictures, from = slot, reported = rig.reportedSlot())
    }

  // Every step of a scrub is one seek. A step onto a later slot of the hold the surface rests on is onto the
  // millisecond media3 already rests on, which still has to draw that slot. Each step is waited on until its seek has
  // completed, and the surface is watched all the way through for a picture of the sample before the hold.
  @Test
  fun aScrubAcrossAHoldDrawsEachSlotWithoutShowingTheSampleBefore() =
    onHeldSurface(Case.Half) { rig ->
      rig.ready()
      val first = rig.firstSlot()
      val before = rig.exportAt(first - 1)
      val offsets = mutableListOf<Int>()
      val pictures =
        rig.picturesDuring {
          rig.player.beginScrub()
          (0..STEPS).forEach {
            rig.seekAndSettle(first + it)
            offsets += rig.nearestOffset(first + it)
          }
          rig.player.endScrub()
        }
      withClue("the surface was nearest these offsets from each step's slot, from slot $first") {
        offsets shouldBe List(STEPS + 1) { 0 }
      }
      rig.settle(first + STEPS)
      rig.shouldShow(first + STEPS)

      pictures.forEachIndexed { index, picture ->
        assertFramesDiffer(
          before,
          picture,
          MIN_PSNR_DB,
          MIN_SSIM,
          "picture $index of ${pictures.size} drawn across the scrub is the sample before the hold, slot ${first - 1}",
        )
      }
    }

  @Test
  fun aFrameReadAtARepeatAgreesWithTheSurface() =
    onHeldSurface(Case.Half) { rig ->
      val slot = rig.repeatSlot()
      rig.seekAndShow(slot)

      val read =
        rig.engine.readback
          .awaitFrame(rig.slotTime(slot))
          .asTestFrame()

      assertFramesSimilar(rig.exportAt(slot), read, MIN_PSNR_DB, MIN_SSIM, "the frame read is not the slot's export")
      assertFramesSimilar(read, rig.view.copyPixels(FIXTURE_FRAME), MIN_PSNR_DB, MIN_SSIM, "the surface differs")
    }

  @Test
  fun aSeekToAnyFrameOfAClipThatHoldsNothingDrawsThatSlot() =
    onHeldSurface(Case.Full) { rig ->
      rig.seekAndShow(FIRST_SLOT)
      rig.seekAndShow(FIRST_SLOT + 1)
      rig.stepAndShow(FIRST_SLOT + 2)
    }

  @Test
  fun aSlowedClipPlaysAsManyPicturesASecondAsAFullSpeedOne() =
    runTest(timeout = TIMEOUT) {
      var full = 0
      var slowed = 0
      onHeldSurface(Case.Full) { rig -> full = rig.picturesWhilePlaying() }
      onHeldSurface(Case.Half) { rig -> slowed = rig.picturesWhilePlaying() }

      withClue("the full speed clip drew $full pictures and the slowed one $slowed") {
        slowed.toDouble() shouldBeGreaterThan full * MIN_SHARE
      }
    }

  @Test
  fun aParameterChangedWhilePausedOnTheFirstSlotOfAHoldRedrawsThatSlot() =
    onHeldSurface(Case.Half) { rig ->
      rig.seekAndShow(rig.firstSlot())
      val brighter = rig.brighter()

      rig.redrawnShows(rig.firstSlot(), brighter)
    }

  // The redraw after a slider move replays the sample through the chain, which has to draw the slot the player rests
  // on and not the first one the sample is written on.
  @Test
  fun aParameterChangedWhilePausedOnARepeatRedrawsThatRepeat() =
    onHeldSurface(Case.Half) { rig ->
      val slot = rig.repeatSlot()
      rig.seekAndShow(slot)
      val brighter = rig.brighter()

      rig.redrawnShows(slot, brighter)
    }
}

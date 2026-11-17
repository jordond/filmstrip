package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.player.contract.asTestFrame
import dev.jordond.filmstrip.player.contract.awaitFrame
import dev.jordond.filmstrip.test.assertFramesSimilar
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.time.Duration

/**
 * After a player is paused and has settled, the slot it reports is the slot on its surface.
 *
 * An app that stops on a picture and stamps a trim point at the position it reads has to get the frame it was looking
 * at. Each case plays on from the start, pauses at a point and compares the surface with the export of the slot the
 * playhead names, then steps a frame each way from there.
 */
class AndroidPausePositionMediaTest {
  @Test
  fun aFullSpeedClipPausedAtSeveralPointsShowsTheSlotItReports() =
    onHeldSurface(Case.Full) { rig -> PAUSE_POINTS.forEach { rig.pausedAt(it) } }

  @Test
  fun aSlowedClipPausedAtSeveralPointsShowsTheSlotItReports() =
    onHeldSurface(Case.Half) { rig -> PAUSE_POINTS.forEach { rig.pausedAt(it) } }

  @Test
  fun aSlowerSourcePausedAtSeveralPointsShowsTheSlotItReports() =
    onHeldSurface(Case.Slower) { rig -> PAUSE_POINTS.forEach { rig.pausedAt(it) } }

  // The opening ends on slot 15, where the slower source starts.
  @Test
  fun aPausePastTheBoundaryBetweenTwoClipsShowsTheSlotItReports() =
    onHeldSurface(Case.Slower) { rig -> rig.pausedAt(BOUNDARY_SLOT) }

  @Test
  fun aPauseThenAStepEachWayLandsOnTheNeighboursOfTheSlotShown() =
    onHeldSurface(Case.Half) { rig ->
      val shown = rig.pausedAt(PAUSE_POINTS.first())

      rig.stepAndShow(shown + 1)
      rig.stepAndShow(shown, forward = false)
      rig.stepAndShow(shown - 1, forward = false)
    }

  // media3 draws the frame it had read furthest ahead for a seek in scrubbing mode that finds a player which has played
  // since its last seek, so the first seek of such a scrub is handed over outside the mode.
  @Test
  fun aScrubRightAfterAPauseDrawsTheSlotAskedForAndReportsIt() =
    onHeldSurface(Case.Half) { rig ->
      SCRUB_PAUSE_POINTS.forEach { past ->
        val shown = rig.playThenPause(past)

        rig.player.beginScrub()
        rig.scrubTwiceAhead(shown)
      }
    }

  // Scrubbing mode stops a playing player where it is, so each seek of the scrub rests on the slot it asked for.
  @Test
  fun aScrubBegunWhilePlayingDrawsTheSlotAskedForAndReportsIt() =
    onHeldSurface(Case.Half) { rig ->
      SCRUB_PAUSE_POINTS.forEach { past ->
        rig.playPast(past)

        rig.player.beginScrub()
        rig.scrubTwiceAhead(rig.reportedSlot())
        rig.player.pause()
      }
    }

  @Test
  fun aPlayAskedForInAScrubRightAfterAPauseDrawsTheSlotAskedForAndReportsIt() =
    onHeldSurface(Case.Half) { rig ->
      SCRUB_PAUSE_POINTS.forEach { past ->
        val shown = rig.playThenPause(past)

        rig.player.beginScrub()
        rig.player.play()
        rig.scrubTwiceAhead(shown)
        rig.player.pause()
      }
    }

  // media3's limit: its scrubbing mode stops a player that is playing on the frame each seek lands on, and nothing
  // tells the engine, so the transport reads as playing for as long as the scrub holds the picture still.
  @Test
  fun aScrubOfAPlayingPlayerPlaysOnUntilItsFirstSeekThenHoldsEachSlotUntilItEnds() =
    onHeldSurface(Case.Full) { rig ->
      rig.playPast(SCRUB_PAUSE_POINTS.first())

      rig.player.beginScrub()
      val begun = rig.reportedSlot()
      awaitOrFail("a scrub with no seek yet to play on past slot ${begun + LATER}") {
        rig.player.currentPosition() >= rig.slotTime(begun + LATER)
      }

      val asked = rig.reportedSlot() + SCRUB_AHEAD
      rig.seekAndSettle(asked)
      rig.shouldShow(asked)
      withClue("the playhead read ${rig.player.currentPosition()} a while after a scrub seek to slot $asked") {
        rig.reportedSlot() shouldBe asked
      }
      rig.player.state.value.playWhenReady shouldBe true
      rig.player.state.value.isPlaying shouldBe true

      rig.player.endScrub()
      awaitOrFail("playback to carry on past slot ${asked + LATER} once the scrub ended") {
        rig.player.currentPosition() >= rig.slotTime(asked + LATER)
      }
      rig.player.state.value.playWhenReady shouldBe true
      rig.player.pause()
    }

  // Seeks ahead of slot [from] twice in the scrub under way, holds the surface to each slot asked for, and ends it. The
  // two are an odd number of slots apart, so one of them is the later slot of a sample the slowed clip shows twice.
  private suspend fun HeldRig.scrubTwiceAhead(from: Int) {
    seekAndSettle(from + SCRUB_AHEAD)
    shouldShow(from + SCRUB_AHEAD)
    seekAndSettle(from + 2 * SCRUB_AHEAD + 1)
    shouldShow(from + 2 * SCRUB_AHEAD + 1)
    player.endScrub()
  }

  @Test
  fun aFrameReadAtTheReportedPositionIsTheOneOnTheSurface() =
    onHeldSurface(Case.Half) { rig ->
      val shown = rig.pausedAt(PAUSE_POINTS.first())

      val read =
        rig.engine.readback
          .awaitFrame(rig.slotTime(shown))
          .asTestFrame()

      assertFramesSimilar(read, rig.view.copyPixels(FIXTURE_FRAME), MIN_PSNR_DB, MIN_SSIM, "the frame read differs")
    }

  @Test
  fun aPlayRightAfterAPauseNeverReadsBehindTheSlotShown() =
    onHeldSurface(Case.Full) { rig -> playedFrom(rig, rig.pausedAt(PAUSE_POINTS.first())) }

  // The play replays the sample the pause rested a later slot of, through a seek of its own, so the playhead has to
  // hold the slot the pause rested on across it.
  @Test
  fun aPlayFromAPausedRepeatOfASlowerSourceNeverReadsBehindTheSlotShown() =
    onHeldSurface(Case.Slower) { rig ->
      val shown = rig.repeatSlot()
      rig.seekAndShow(shown)

      playedFrom(rig, shown)
    }

  @Test
  fun aPlayFromAPausedRepeatOfASlowedClipNeverReadsBehindTheSlotShown() =
    onHeldSurface(Case.Half) { rig ->
      val shown = rig.repeatSlot()
      rig.seekAndShow(shown)

      playedFrom(rig, shown)
    }

  // The frame last drawn is the playhead only while a surface still shows it. Once the surface is gone the clock is,
  // so a play and a pause on a player with none does not take the playhead back to the frame that was drawn before.
  @Test
  fun aPlayAndPauseWithNoSurfaceLeavesThePlayheadPastTheFrameLastDrawn() =
    onHeldSurface(Case.Full) { rig ->
      val shown = rig.pausedAt(PAUSE_POINTS.first())
      rig.attachment.shouldNotBeNull().cancel()

      rig.player.play()
      awaitOrFail("the playhead to pass slot ${shown + LATER}") {
        rig.player.currentPosition() >= rig.slotTime(shown + LATER)
      }
      rig.player.pause()
      delay(SETTLE)

      withClue("the playhead read ${rig.player.currentPosition()} after a surfaceless play and pause from $shown") {
        rig.player.currentPosition() shouldBeGreaterThanOrEqualTo rig.slotTime(shown + LATER)
      }
    }

  // A surface goes and comes back as a device turns or an app leaves the screen. media3's clock stopped a slot or two
  // behind the frame the pause rests on, and the frame drawn for the surface handed back has to be that one.
  @Test
  fun aSurfaceDetachedAndAttachedAfterAPauseShowsTheSameSlotAndReportsIt() =
    onHeldSurface(Case.Half) { rig ->
      PAUSE_POINTS.forEach { past ->
        val shown = rig.pausedAt(past)
        val reported = rig.player.currentPosition()

        rig.reattach {
          withClue("the playhead with no surface, after a pause that read $reported") {
            rig.player.currentPosition() shouldBe reported
          }
        }

        rig.shouldShow(shown)
        withClue("the playhead once the surface was handed back, after a pause that read $reported") {
          rig.player.currentPosition() shouldBe reported
        }
      }
    }

  // Plays on from a pause on slot [shown] until the playhead is two slots past it, and holds every reading to the slot.
  private suspend fun CoroutineScope.playedFrom(
    rig: HeldRig,
    shown: Int,
  ) {
    val readings = CopyOnWriteArrayList<Duration>()
    val reader = launch { rig.player.positionFlow(Duration.ZERO).collect { readings += it } }

    rig.player.play()
    awaitOrFail("the playhead to pass slot ${shown + 2}") { rig.player.currentPosition() >= rig.slotTime(shown + 2) }
    rig.player.pause()
    reader.cancel()

    withClue("the playhead read ${readings.toList()} after a play from slot $shown") {
      readings.forEach { it shouldBeGreaterThanOrEqualTo rig.slotTime(shown) }
    }
  }

  // A slow decoder draws fewer frames than the clock passes, so the slot reported can trail the point passed. What is
  // held is that it is the slot on the surface.
  private suspend fun HeldRig.pausedAt(past: Int): Int {
    val reported = playThenPause(past)
    shouldShow(reported)
    return reported
  }

  private companion object {
    val PAUSE_POINTS = listOf(12, 25, 41)
    const val BOUNDARY_SLOT = 16
    val SCRUB_PAUSE_POINTS = listOf(2, 4, 6)
    const val SCRUB_AHEAD = 4
    const val LATER = 3
  }
}

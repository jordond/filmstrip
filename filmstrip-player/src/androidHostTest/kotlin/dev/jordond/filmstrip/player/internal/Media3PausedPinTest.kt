package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.internal.HeldPinRig.Companion.CLOCK_BEHIND_MS
import dev.jordond.filmstrip.player.internal.HeldPinRig.Companion.FIRST_AT
import dev.jordond.filmstrip.player.internal.HeldPinRig.Companion.HELD_AT
import dev.jordond.filmstrip.player.internal.HeldPinRig.Companion.LATER_AT
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Where the playhead and the pin of a player that stopped rest, and what lets go of them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Media3PausedPinTest {
  @Test
  fun `the slot a pause rested on stays the floor of the playhead through the replay of a play from it`() =
    runTest {
      val rig = HeldPinRig(this, playing = false)
      rig.resting = HELD_AT
      rig.seeks.position() shouldBe HELD_AT

      // The player plays on, so nothing rests any more, and media3's clock reads behind the slot the pause rested on
      // until the replay has drawn the sample again.
      rig.resting = null
      rig.platformMs = FIRST_AT.inWholeMilliseconds - CLOCK_BEHIND_MS
      rig.seeks.replay(HELD_AT)
      runCurrent()
      rig.seeks.frameDrawn()
      runCurrent()

      rig.seeks.position() shouldBe HELD_AT
    }

  @Test
  fun `a replay holds the slot it was asked for as the floor of the playhead though no frame named it`() =
    runTest {
      val rig = HeldPinRig(this, playing = false)
      rig.platformMs = FIRST_AT.inWholeMilliseconds - CLOCK_BEHIND_MS

      rig.seeks.replay(HELD_AT)
      runCurrent()
      rig.seeks.frameDrawn()
      runCurrent()

      rig.seeks.position() shouldBe HELD_AT
    }

  @Test
  fun `the floor stays through the clock reading the slot a replay seeks back to and goes once the clock passes it`() =
    runTest {
      val rig = HeldPinRig(this, playing = false)
      // The play that asks for the replay.
      rig.playing = true
      rig.seeks.replay(HELD_AT)
      runCurrent()
      rig.seeks.frameDrawn()
      runCurrent()

      rig.platformMs = FIRST_AT.inWholeMilliseconds
      rig.seeks.position() shouldBe HELD_AT
      rig.platformMs = FIRST_AT.inWholeMilliseconds + CLOCK_BEHIND_MS / 2
      rig.seeks.position() shouldBe HELD_AT
      rig.platformMs = HELD_AT.inWholeMilliseconds + CLOCK_BEHIND_MS
      rig.seeks.position() shouldBe (HELD_AT.inWholeMilliseconds + CLOCK_BEHIND_MS).milliseconds
    }

  // Slot 22 of 30 a second is 733.333 ms, which media3's clock reads as 733 before it has reached the slot.
  @Test
  fun `the floor of a slot between two milliseconds stays while the clock reads the millisecond under it`() =
    runTest {
      val rig = HeldPinRig(this, playing = false)
      rig.resting = LATER_AT
      rig.seeks.position() shouldBe LATER_AT

      rig.playing = true
      rig.resting = null
      rig.platformMs = LATER_AT.inWholeMilliseconds
      rig.seeks.position() shouldBe LATER_AT
      rig.seeks.position() shouldBe LATER_AT

      rig.platformMs = LATER_AT.inWholeMilliseconds + 1
      rig.seeks.position() shouldBe (LATER_AT.inWholeMilliseconds + 1).milliseconds
      rig.platformMs = LATER_AT.inWholeMilliseconds
      rig.seeks.position() shouldBe LATER_AT.inWholeMilliseconds.milliseconds
    }

  // A paused player's clock can read past the frame it showed, as it does where the decoder is slow.
  @Test
  fun `the floor stays under a paused player whose clock reads past it and goes once it plays`() =
    runTest {
      val rig = HeldPinRig(this, playing = false)
      rig.resting = HELD_AT
      rig.seeks.position() shouldBe HELD_AT

      rig.resting = null
      rig.platformMs = HELD_AT.inWholeMilliseconds + CLOCK_BEHIND_MS
      rig.seeks.position() shouldBe HELD_AT
      rig.seeks.position() shouldBe HELD_AT

      rig.playing = true
      rig.seeks.position() shouldBe (HELD_AT.inWholeMilliseconds + CLOCK_BEHIND_MS).milliseconds
    }

  // The player had released the frame for the next slot before it stopped, and draws it once the pause has pinned the
  // slot read as it was asked for.
  @Test
  fun `a pause moves the pin to the frame that settles on the surface`() =
    runTest {
      val rig = HeldPinRig(this, playing = true, deferred = true)
      rig.drawn = HELD_AT

      rig.seeks.pause(HELD_AT)
      rig.pins.last() shouldBe HELD_AT
      rig.drawn = LATER_AT
      rig.seeks.frameDrawn()
      rig.runQueued()

      rig.pins.last() shouldBe LATER_AT
      rig.playing shouldBe false
    }

  @Test
  fun `a pause with no frame drawn to name a slot keeps the slot it was asked on`() =
    runTest {
      val rig = HeldPinRig(this, playing = true, deferred = true)

      rig.seeks.pause(HELD_AT)
      rig.runQueued()

      rig.pins shouldBe listOf<Duration?>(HELD_AT)
    }

  @Test
  fun `a frame drawn while paused pins the slot it shows`() =
    runTest {
      val rig = HeldPinRig(this, playing = false)
      rig.drawn = LATER_AT

      rig.seeks.frameDrawn()

      rig.pins.last() shouldBe LATER_AT
    }

  @Test
  fun `a frame drawn while playing pins nothing`() =
    runTest {
      val rig = HeldPinRig(this, playing = true)
      rig.drawn = LATER_AT

      rig.seeks.frameDrawn()

      rig.pins.shouldBeEmpty()
    }

  @Test
  fun `a play asked for before a pause has settled leaves no pin`() =
    runTest {
      val rig = HeldPinRig(this, playing = true, deferred = true)
      rig.drawn = LATER_AT

      rig.seeks.pause(HELD_AT)
      rig.seeks.resume(at = FIRST_AT)
      rig.runQueued()

      rig.pins.last() shouldBe null
      rig.playing shouldBe true
    }

  @Test
  fun `a frame drawn before a seek handed over since leaves the pin to the seek`() =
    runTest {
      val rig = HeldPinRig(this, playing = false, playbackDeferred = true)
      rig.drawn = LATER_AT

      rig.seeks.seek(HELD_AT, SeekAccuracy.Exact, wrap = false) { }
      // The seek is handed over, and the playback thread has yet to get to it.
      rig.runPlayback(turns = 1)
      rig.seeks.frameDrawn()
      rig.pins.last() shouldBe HELD_AT

      rig.runPlayback()
      rig.drawn = HELD_AT
      rig.seeks.frameDrawn()
      rig.pins.last() shouldBe HELD_AT
      rig.pins.count { it == LATER_AT } shouldBe 0
    }

  // The frame was drawn after the seek was issued and before the playback thread got to it, so it is from before the
  // seek, and the pause that follows reads it on the surface.
  @Test
  fun `a pause behind a seek handed over while playing leaves the pin off a frame drawn before the seek`() =
    runTest {
      val rig = HeldPinRig(this, playing = true, playbackDeferred = true)

      rig.seeks.seek(HELD_AT, SeekAccuracy.Exact, wrap = false) { }
      rig.runPlayback(turns = 1)
      rig.drawn = LATER_AT
      rig.seeks.frameDrawn()

      rig.seeks.pause(HELD_AT)
      rig.runPlayback()

      rig.pins.last() shouldBe HELD_AT
      rig.pins.count { it == LATER_AT } shouldBe 0
    }

  // The replay finds the scrub under way, which stops the player on the frame it lands on, so it pins the slot.
  @Test
  fun `the pin a play inside a scrub put on the hold it replays is let go of as the scrub ends`() =
    runTest {
      val rig = HeldPinRig(this, playing = false, scrubbing = true)
      rig.seeks.pause(HELD_AT)
      rig.seeks.resume(at = HELD_AT)
      runCurrent()
      rig.pins.last() shouldBe HELD_AT

      rig.scrubbing = false
      rig.pin.scrubEnded()

      rig.pins.last() shouldBe null
      rig.pin.slot shouldBe null
    }

  @Test
  fun `a scrub ending on a paused player leaves its pin`() =
    runTest {
      val rig = HeldPinRig(this, playing = false, scrubbing = true)
      rig.seeks.seek(HELD_AT, SeekAccuracy.Exact, wrap = false) { }
      runCurrent()

      rig.scrubbing = false
      rig.pin.scrubEnded()

      rig.pins.last() shouldBe HELD_AT
    }

  @Test
  fun `a player media3 stopped itself is pinned to the frame that settles on the surface`() =
    runTest {
      val rig = HeldPinRig(this, playing = false, deferred = true)
      rig.drawn = LATER_AT

      rig.seeks.stopped()
      rig.pins.shouldBeEmpty()
      rig.runQueued()

      rig.pins.last() shouldBe LATER_AT
    }
}

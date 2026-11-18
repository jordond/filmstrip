package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.internal.HeldPinRig.Companion.AFTER_AT
import dev.jordond.filmstrip.player.internal.HeldPinRig.Companion.FIRST_AT
import dev.jordond.filmstrip.player.internal.HeldPinRig.Companion.HELD_AT
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * When a play from a later slot of a held sample starts the player, with the player's thread run only when asked.
 *
 * The replay seek reaches the player some hops after the play, and a player started before it draws the next sample
 * from where it rested and then jumps back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Media3ReplayStartTest {
  @Test
  fun `a play from a later slot of a hold starts the player right behind the seek back to its first slot`() =
    runTest {
      val rig = HeldPinRig(this, playing = false, deferred = true)

      rig.seeks.resume(at = HELD_AT)

      rig.playing shouldBe false
      runCurrent()
      rig.runQueued()
      verifyOrder {
        rig.player.seekTo(FIRST_AT.inWholeMilliseconds)
        rig.player.playWhenReady = true
      }
      rig.playing shouldBe true
    }

  @Test
  fun `a pause before the replay is handed over leaves the player paused`() =
    runTest {
      val rig = HeldPinRig(this, playing = false, deferred = true)

      rig.seeks.resume(at = HELD_AT)
      rig.seeks.pause(HELD_AT)
      runCurrent()
      rig.runQueued()

      verify(exactly = 0) { rig.player.playWhenReady = true }
      rig.playing shouldBe false
    }

  @Test
  fun `a second play before the replay is handed over replays the sample once`() =
    runTest {
      val rig = HeldPinRig(this, playing = false, deferred = true)

      rig.seeks.resume(at = HELD_AT)
      rig.seeks.resume(at = HELD_AT)
      runCurrent()
      rig.runQueued()

      verify(exactly = 1) { rig.player.seekTo(FIRST_AT.inWholeMilliseconds) }
      verify(exactly = 1) { rig.player.playWhenReady = true }
    }

  @Test
  fun `a play after a pause before the first replay is handed over starts the player behind the seek back`() =
    runTest {
      val rig = HeldPinRig(this, playing = false, deferred = true)
      var playingAtSeekBack: Boolean? = null
      every { rig.player.seekTo(FIRST_AT.inWholeMilliseconds) } answers { playingAtSeekBack = rig.playing }

      rig.seeks.resume(at = HELD_AT)
      runCurrent()
      rig.seeks.pause(HELD_AT)
      rig.seeks.resume(at = HELD_AT)
      runCurrent()
      rig.runQueued()

      playingAtSeekBack shouldBe false
      rig.playing shouldBe true
    }

  @Test
  fun `a play during a seek back to a held slot starts the player with the pin let go of`() =
    runTest {
      val rig = HeldPinRig(this, playing = false, deferred = true, restingMs = HELD_AT.inWholeMilliseconds)
      rig.seeks.represent(surfaceLost = false)
      rig.runQueued()
      runCurrent()

      rig.seeks.resume(at = HELD_AT)
      runCurrent()
      rig.runQueued()

      rig.playing shouldBe true
      rig.pins.last() shouldBe null
      rig.seeks.playedSinceSeek shouldBe true
    }

  @Test
  fun `a play during a scrub keeps the pin the scrub's seek set`() =
    runTest {
      val rig = HeldPinRig(this, playing = false, scrubbing = true, deferred = true, playbackDeferred = true)
      rig.seeks.seek(HELD_AT, SeekAccuracy.Nearest, wrap = false) { }
      rig.runQueued()
      rig.runPlayback()

      // The scrub seek is handed over between the play and the start it posted.
      rig.seeks.resume(at = HELD_AT)
      runCurrent()
      rig.runQueued()
      rig.runPlayback()
      rig.runQueued()

      rig.playing shouldBe true
      rig.pins.last() shouldBe HELD_AT
    }

  @Test
  fun `a play that finds a seek in flight starts the player without a replay`() =
    runTest {
      val rig = HeldPinRig(this, playing = false, deferred = true)

      rig.seeks.seek(AFTER_AT, SeekAccuracy.Exact, wrap = false) { }
      rig.seeks.resume(at = HELD_AT)
      runCurrent()
      rig.runQueued()

      verify(exactly = 0) { rig.player.seekTo(FIRST_AT.inWholeMilliseconds) }
      rig.playing shouldBe true
    }

  @Test
  fun `a play taken by a new player is not started again by the replay`() =
    runTest {
      val rig = HeldPinRig(this, playing = false, deferred = true)

      rig.seeks.resume(at = HELD_AT)
      rig.seeks.takePendingPlay() shouldBe true
      runCurrent()
      rig.runQueued()

      verify(exactly = 0) { rig.player.playWhenReady = true }
      rig.seeks.takePendingPlay() shouldBe false
    }
}

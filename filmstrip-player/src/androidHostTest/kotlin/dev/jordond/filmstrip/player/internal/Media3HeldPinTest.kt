package dev.jordond.filmstrip.player.internal

import android.os.Handler
import androidx.media3.common.Player
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.internal.HeldPinRig.Companion.AFTER_AT
import dev.jordond.filmstrip.player.internal.HeldPinRig.Companion.ELSEWHERE_MS
import dev.jordond.filmstrip.player.internal.HeldPinRig.Companion.FIRST_AT
import dev.jordond.filmstrip.player.internal.HeldPinRig.Companion.HELD_AT
import dev.jordond.filmstrip.player.internal.HeldPinRig.Companion.INTO_SLOT
import dev.jordond.filmstrip.player.internal.HeldPinRig.Companion.LATER_AT
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Which slot of a held sample a seek pins the preview to, with the player's thread run inline.
 *
 * Slot [HELD_AT] shows the sample written on slot [FIRST_AT], so a seek to it is handed to media3 at [FIRST_AT].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Media3HeldPinTest {
  @Test
  fun `a paused seek into a hold pins the slot it is for`() =
    runTest {
      val rig = HeldPinRig(this, playing = false)

      rig.seeks.seek(HELD_AT, SeekAccuracy.Exact, wrap = false) { }
      runCurrent()

      rig.pins.last() shouldBe HELD_AT
    }

  @Test
  fun `a playing seek into a hold pins nothing`() =
    runTest {
      val rig = HeldPinRig(this, playing = true)

      rig.seeks.seek(HELD_AT, SeekAccuracy.Exact, wrap = false) { }
      runCurrent()

      rig.pins.last() shouldBe null
    }

  // Scrubbing mode stops the player on the frame the seek lands on, so nothing draws the later slots of the sample.
  @Test
  fun `a playing seek into a hold in a scrub pins the slot it is for`() =
    runTest {
      val rig = HeldPinRig(this, playing = true, scrubbing = true, restingMs = ELSEWHERE_MS)

      rig.seeks.seek(HELD_AT, SeekAccuracy.Exact, wrap = false) { }
      runCurrent()

      rig.pins.last() shouldBe HELD_AT
    }

  @Test
  fun `a playing seek into a hold handed over once its scrub has ended pins nothing`() =
    runTest {
      val rig = HeldPinRig(this, playing = true, scrubbing = true, deferred = true, restingMs = ELSEWHERE_MS)

      rig.seeks.seek(HELD_AT, SeekAccuracy.Exact, wrap = false) { }
      rig.scrubbing = false
      rig.runQueued()
      runCurrent()

      rig.pins.last() shouldBe null
    }

  @Test
  fun `only a player that rests on the frame it is sent to is pinned to a later slot of a hold`() {
    heldSlotOf(HELD_AT, FIRST_AT, playWanted = false, scrubbing = false) shouldBe HELD_AT
    heldSlotOf(HELD_AT, FIRST_AT, playWanted = true, scrubbing = true) shouldBe HELD_AT
    heldSlotOf(HELD_AT, FIRST_AT, playWanted = true, scrubbing = false) shouldBe null
    heldSlotOf(FIRST_AT, FIRST_AT, playWanted = false, scrubbing = true) shouldBe null
  }

  // A rebuild starts the fresh graph on the slot the playhead holds and pins it by the rule a seek is pinned by. The
  // fresh player says it was told to play as it is, which lets go of whatever pin stands by then.
  @Test
  fun `a player told to play that is rebuilt in a scrub onto a later slot of a hold is pinned to it`() =
    runTest {
      val rig = HeldPinRig(this, playing = false, scrubbing = true)
      every { rig.player.playWhenReady = true } answers {
        rig.playing = true
        rig.seeks.unpin()
      }

      rig.pin.start(rig.player, wanted = true, HELD_AT, FIRST_AT, rig.scrubbing)

      rig.playing shouldBe true
      rig.pins.last() shouldBe HELD_AT
      rig.pin.slot shouldBe HELD_AT
    }

  @Test
  fun `a paused player that is rebuilt onto a later slot of a hold is pinned to it and one playing on is not`() =
    runTest {
      val paused = HeldPinRig(this, playing = false)
      paused.pin.start(paused.player, wanted = false, HELD_AT, FIRST_AT, scrubbing = false)
      paused.pin.slot shouldBe HELD_AT

      val playing = HeldPinRig(this, playing = false)
      playing.pin.start(playing.player, wanted = true, HELD_AT, FIRST_AT, scrubbing = false)
      playing.playing shouldBe true
      playing.pin.slot shouldBe null
    }

  @Test
  fun `a seek into a hold followed by a play leaves no pin`() =
    runTest {
      val rig = HeldPinRig(this, playing = false)

      rig.seeks.seek(HELD_AT, SeekAccuracy.Exact, wrap = false) { }
      // What the engine posts behind it, as the player starts to play.
      rig.seeks.unpin()
      runCurrent()

      rig.pins.last() shouldBe null
    }

  @Test
  fun `a scrub onto a hold is one seek to the first slot of the sample`() =
    runTest {
      val rig = HeldPinRig(this, playing = false, scrubbing = true, restingMs = ELSEWHERE_MS)

      rig.seeks.seek(HELD_AT, SeekAccuracy.Nearest, wrap = false) { }
      runCurrent()

      rig.pins.last() shouldBe HELD_AT
      verify(exactly = 1) { rig.player.seekTo(any<Long>()) }
      verify(exactly = 1) { rig.player.seekTo(FIRST_AT.inWholeMilliseconds) }
    }

  // Scrubbing mode decodes nothing for a seek to the millisecond the player rests on, and the sample's first slot is
  // that millisecond for every slot of the hold.
  @Test
  fun `a scrub onto a later slot of the hold the player rests on goes through the millisecond beside it`() =
    runTest {
      val rig = HeldPinRig(this, playing = false, scrubbing = true)

      rig.seeks.seek(HELD_AT, SeekAccuracy.Exact, wrap = false) { }
      runCurrent()

      rig.pins.last() shouldBe HELD_AT
      verify(exactly = 1) { rig.player.seekTo(seekNudgeMs(FIRST_AT.inWholeMilliseconds)) }
      verify(exactly = 1) { rig.player.seekTo(FIRST_AT.inWholeMilliseconds) }
    }

  @Test
  fun `a seek issued while scrubbing goes unnudged though the scrub has ended by the time it is handed over`() =
    runTest {
      val rig = HeldPinRig(this, playing = false, scrubbing = true, deferred = true, restingMs = ELSEWHERE_MS)

      rig.seeks.seek(HELD_AT, SeekAccuracy.Nearest, wrap = false) { }
      rig.scrubbing = false
      rig.runQueued()
      runCurrent()

      verify(exactly = 1) { rig.player.seekTo(any<Long>()) }
    }

  @Test
  fun `a paused seek into a hold the player rests on is nudged when the pin moves`() =
    runTest {
      val rig = HeldPinRig(this, playing = false)

      rig.seeks.seek(HELD_AT, SeekAccuracy.Exact, wrap = false) { }
      runCurrent()

      verify(exactly = 1) { rig.player.seekTo(seekNudgeMs(FIRST_AT.inWholeMilliseconds)) }
    }

  @Test
  fun `replaying a pause on a hold seeks fresh to the first slot with no pin and answers nothing`() =
    runTest {
      val rig = HeldPinRig(this, playing = true)

      rig.seeks.replay(HELD_AT)
      runCurrent()

      rig.pins.last() shouldBe null
      verify(exactly = 1) { rig.player.seekTo(seekNudgeMs(FIRST_AT.inWholeMilliseconds)) }
      verify(exactly = 1) { rig.player.seekTo(FIRST_AT.inWholeMilliseconds) }
      rig.seeks.position() shouldBe HELD_AT
    }

  @Test
  fun `a pause pins the slot it rests on and no play queued before it lets go of it`() =
    runTest {
      val rig = HeldPinRig(this, playing = false)

      rig.seeks.resume(at = FIRST_AT)
      rig.seeks.pause(HELD_AT)
      runCurrent()

      rig.pins.last() shouldBe HELD_AT
      rig.playing shouldBe false
    }

  @Test
  fun `a seek to the slot a seek already landed on is skipped, since that seek drew its frame`() =
    runTest {
      val rig = HeldPinRig(this, playing = false)

      rig.seeks.seek(HELD_AT, SeekAccuracy.Exact, wrap = false) { }
      runCurrent()
      rig.seeks.seek(HELD_AT, SeekAccuracy.Exact, wrap = false) { }
      runCurrent()

      verify(exactly = 1) { rig.player.seekTo(seekNudgeMs(FIRST_AT.inWholeMilliseconds)) }
    }

  // The player stopped on whichever frame it had drawn, which the millisecond it rests on does not name.
  @Test
  fun `a seek to the millisecond a pause rests on is carried out, since the player played its way there`() =
    runTest {
      val rig = HeldPinRig(this, playing = true)

      rig.seeks.pause(HELD_AT)
      rig.seeks.seek(HELD_AT, SeekAccuracy.Exact, wrap = false) { }
      runCurrent()

      verify(exactly = 1) { rig.player.seekTo(seekNudgeMs(FIRST_AT.inWholeMilliseconds)) }
    }

  @Test
  fun `a seek to the slot a seek landed on is carried out once the player has played since`() =
    runTest {
      val rig = HeldPinRig(this, playing = false)

      rig.seeks.seek(FIRST_AT, SeekAccuracy.Exact, wrap = false) { }
      runCurrent()
      rig.seeks.resume(at = FIRST_AT)
      rig.seeks.pause(FIRST_AT)
      rig.seeks.seek(FIRST_AT, SeekAccuracy.Exact, wrap = false) { }
      runCurrent()

      verify(exactly = 2) { rig.player.seekTo(seekNudgeMs(FIRST_AT.inWholeMilliseconds)) }
    }

  @Test
  fun `a scrub onto the millisecond a pause rests on goes through the millisecond beside it`() =
    runTest {
      val rig = HeldPinRig(this, playing = true, scrubbing = true)

      rig.seeks.pause(FIRST_AT)
      rig.seeks.seek(FIRST_AT, SeekAccuracy.Exact, wrap = false) { }
      runCurrent()

      verify(exactly = 1) { rig.player.seekTo(seekNudgeMs(FIRST_AT.inWholeMilliseconds)) }
      verify(exactly = 1) { rig.player.seekTo(FIRST_AT.inWholeMilliseconds) }
    }

  @Test
  fun `a play is let go of the pin and replays the held sample of a paused player`() =
    runTest {
      val rig = HeldPinRig(this, playing = false)

      rig.seeks.pause(HELD_AT)
      rig.seeks.resume(at = HELD_AT)
      runCurrent()

      rig.pins.last() shouldBe null
      rig.playing shouldBe true
      verify(exactly = 1) { rig.player.seekTo(FIRST_AT.inWholeMilliseconds) }
    }

  // A player that played its way to a pause reads a time inside the slot, which is no slot's own time.
  @Test
  fun `a play from inside the first slot of a sample seeks nothing`() =
    runTest {
      val rig = HeldPinRig(this, playing = true)

      rig.seeks.pause(FIRST_AT + INTO_SLOT)
      rig.seeks.resume(at = FIRST_AT + INTO_SLOT)
      runCurrent()

      rig.playing shouldBe true
      verify(exactly = 0) { rig.player.seekTo(any<Long>()) }
    }

  @Test
  fun `a play from inside a later slot of a held sample replays it from the slot the time lands on`() =
    runTest {
      val rig = HeldPinRig(this, playing = true)

      rig.seeks.pause(HELD_AT + INTO_SLOT)
      rig.seeks.resume(at = HELD_AT + INTO_SLOT)
      runCurrent()

      verify(exactly = 1) { rig.player.seekTo(FIRST_AT.inWholeMilliseconds) }
      rig.seeks.position() shouldBe HELD_AT
    }

  // The last slot is the nearest one to the end, and on an edit that ends in a hold it is a later slot of a sample.
  @Test
  fun `a play from the end of an edit that ends in a hold replays nothing`() =
    runTest {
      val rig = HeldPinRig(this, playing = false)
      rig.length = AFTER_AT

      rig.seeks.resume(at = AFTER_AT)
      runCurrent()

      rig.playing shouldBe true
      verify(exactly = 0) { rig.player.seekTo(any<Long>()) }
    }

  @Test
  fun `a play from the last slot of an edit that ends in a hold replays it`() =
    runTest {
      val rig = HeldPinRig(this, playing = false)
      rig.length = AFTER_AT

      rig.seeks.resume(at = LATER_AT)
      runCurrent()

      verify(exactly = 1) { rig.player.seekTo(FIRST_AT.inWholeMilliseconds) }
    }

  @Test
  fun `a play asked for while the player is already playing seeks nothing`() =
    runTest {
      val rig = HeldPinRig(this, playing = true)

      rig.seeks.resume(at = HELD_AT)
      runCurrent()

      verify(exactly = 0) { rig.player.seekTo(any<Long>()) }
    }
}

package dev.jordond.filmstrip.player.internal

import android.os.Handler
import androidx.media3.common.Player
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
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
 * Slot [HELD] shows the sample written on slot [FIRST], so a seek to it is handed to media3 at [FIRST].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Media3HeldPinTest {
  @Test
  fun `a paused seek into a hold pins the slot it is for`() =
    runTest {
      val rig = Rig(this, playing = false)

      rig.seeks.seek(HELD_AT, SeekAccuracy.Exact, wrap = false) { }
      runCurrent()

      rig.pins.last() shouldBe HELD_AT
    }

  @Test
  fun `a playing seek into a hold pins nothing`() =
    runTest {
      val rig = Rig(this, playing = true)

      rig.seeks.seek(HELD_AT, SeekAccuracy.Exact, wrap = false) { }
      runCurrent()

      rig.pins.last() shouldBe null
    }

  @Test
  fun `a seek into a hold followed by a play leaves no pin`() =
    runTest {
      val rig = Rig(this, playing = false)

      rig.seeks.seek(HELD_AT, SeekAccuracy.Exact, wrap = false) { }
      // What the engine posts behind it, as the player starts to play.
      rig.seeks.unpin()
      runCurrent()

      rig.pins.last() shouldBe null
    }

  @Test
  fun `a scrub onto a hold is one seek to the first slot of the sample`() =
    runTest {
      val rig = Rig(this, playing = false, scrubbing = true, restingMs = ELSEWHERE_MS)

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
      val rig = Rig(this, playing = false, scrubbing = true)

      rig.seeks.seek(HELD_AT, SeekAccuracy.Exact, wrap = false) { }
      runCurrent()

      rig.pins.last() shouldBe HELD_AT
      verify(exactly = 1) { rig.player.seekTo(seekNudgeMs(FIRST_AT.inWholeMilliseconds)) }
      verify(exactly = 1) { rig.player.seekTo(FIRST_AT.inWholeMilliseconds) }
    }

  @Test
  fun `a seek issued while scrubbing goes unnudged though the scrub has ended by the time it is handed over`() =
    runTest {
      val rig = Rig(this, playing = false, scrubbing = true, deferred = true, restingMs = ELSEWHERE_MS)

      rig.seeks.seek(HELD_AT, SeekAccuracy.Nearest, wrap = false) { }
      rig.scrubbing = false
      rig.runQueued()
      runCurrent()

      verify(exactly = 1) { rig.player.seekTo(any<Long>()) }
    }

  @Test
  fun `a paused seek into a hold the player rests on is nudged when the pin moves`() =
    runTest {
      val rig = Rig(this, playing = false)

      rig.seeks.seek(HELD_AT, SeekAccuracy.Exact, wrap = false) { }
      runCurrent()

      verify(exactly = 1) { rig.player.seekTo(seekNudgeMs(FIRST_AT.inWholeMilliseconds)) }
    }

  @Test
  fun `replaying a pause on a hold seeks fresh to the first slot with no pin and answers nothing`() =
    runTest {
      val rig = Rig(this, playing = true)

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
      val rig = Rig(this, playing = false)

      rig.seeks.resume(at = FIRST_AT)
      rig.seeks.pause(HELD_AT)
      runCurrent()

      rig.pins.last() shouldBe HELD_AT
      rig.playing shouldBe false
    }

  @Test
  fun `a seek to the slot a seek already landed on is skipped, since that seek drew its frame`() =
    runTest {
      val rig = Rig(this, playing = false)

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
      val rig = Rig(this, playing = true)

      rig.seeks.pause(HELD_AT)
      rig.seeks.seek(HELD_AT, SeekAccuracy.Exact, wrap = false) { }
      runCurrent()

      verify(exactly = 1) { rig.player.seekTo(seekNudgeMs(FIRST_AT.inWholeMilliseconds)) }
    }

  @Test
  fun `a seek to the slot a seek landed on is carried out once the player has played since`() =
    runTest {
      val rig = Rig(this, playing = false)

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
      val rig = Rig(this, playing = true, scrubbing = true)

      rig.seeks.pause(FIRST_AT)
      rig.seeks.seek(FIRST_AT, SeekAccuracy.Exact, wrap = false) { }
      runCurrent()

      verify(exactly = 1) { rig.player.seekTo(seekNudgeMs(FIRST_AT.inWholeMilliseconds)) }
      verify(exactly = 1) { rig.player.seekTo(FIRST_AT.inWholeMilliseconds) }
    }

  @Test
  fun `a play is let go of the pin and replays the held sample of a paused player`() =
    runTest {
      val rig = Rig(this, playing = false)

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
      val rig = Rig(this, playing = true)

      rig.seeks.pause(FIRST_AT + INTO_SLOT)
      rig.seeks.resume(at = FIRST_AT + INTO_SLOT)
      runCurrent()

      rig.playing shouldBe true
      verify(exactly = 0) { rig.player.seekTo(any<Long>()) }
    }

  @Test
  fun `a play from inside a later slot of a held sample replays it from the slot the time lands on`() =
    runTest {
      val rig = Rig(this, playing = true)

      rig.seeks.pause(HELD_AT + INTO_SLOT)
      rig.seeks.resume(at = HELD_AT + INTO_SLOT)
      runCurrent()

      verify(exactly = 1) { rig.player.seekTo(FIRST_AT.inWholeMilliseconds) }
      rig.seeks.position() shouldBe HELD_AT
    }

  @Test
  fun `a play asked for while the player is already playing seeks nothing`() =
    runTest {
      val rig = Rig(this, playing = true)

      rig.seeks.resume(at = HELD_AT)
      runCurrent()

      verify(exactly = 0) { rig.player.seekTo(any<Long>()) }
    }

  private class Rig(
    scope: TestScope,
    var playing: Boolean,
    var scrubbing: Boolean = false,
    private val deferred: Boolean = false,
    restingMs: Long = FIRST_AT.inWholeMilliseconds,
  ) {
    val pins = mutableListOf<Duration?>()
    private val queued = ArrayDeque<Runnable>()

    /**
     * Runs what was posted to the player's thread, and what that posts, as the thread gets to it.
     */
    fun runQueued() {
      while (queued.isNotEmpty()) queued.removeFirst().run()
    }

    private val handler =
      mockk<Handler>().also {
        every { it.post(any()) } answers {
          if (deferred) queued += firstArg<Runnable>() else firstArg<Runnable>().run()
          true
        }
      }

    val player =
      mockk<Player>().also {
        every { it.currentPosition } returns restingMs
        every { it.playbackState } returns Player.STATE_READY
        every { it.isPlaying } returns false
        every { it.playWhenReady } answers { playing }
        every { it.playWhenReady = any() } answers { playing = firstArg() }
        every { it.seekTo(any<Long>()) } returns Unit
      }

    private val composition =
      mockk<ResolvedComposition>().also {
        every { it.duration } returns LENGTH
        every { it.frameRate } returns RATE
      }

    val seeks =
      Media3SeekCoordinator(
        scope = scope,
        handler = handler,
        player = { player },
        playbackLooper = { null },
        resolved = { composition },
        guard = WrapGuard(),
        platformPositionMs = { FIRST_AT.inWholeMilliseconds },
        loopWrap = { null },
        scrubbing = { this.scrubbing },
        showsFrames = { true },
        onIssued = { },
        onRedirected = { },
        firstSlotOf = { if (it in FIRST_AT..<AFTER_AT) FIRST_AT else it },
        pinSlot = { pins += it },
        onRepresented = { },
      )
  }

  private companion object {
    const val RATE = 30
    val LENGTH = 3.seconds
    val FIRST_AT = outputSlotTime(20, RATE)
    val HELD_AT = outputSlotTime(21, RATE)
    val AFTER_AT = outputSlotTime(22, RATE)
    val INTO_SLOT = 10.milliseconds
    const val ELSEWHERE_MS = 100L
  }
}

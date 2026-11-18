package dev.jordond.filmstrip.player.internal

import android.os.Handler
import androidx.media3.common.Player
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The seek back to a held slot, with the player's thread run inline and media3's answers raised by hand.
 *
 * The player rests paused on [RESTING_MS], the millisecond the held slot floors to, which is the one seek media3
 * would skip.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Media3RepresentTest {
  @Test
  fun `the held slot is seeked back to through the millisecond beside it`() =
    runTest {
      val rig = Rig(this)

      rig.seeks.represent(surfaceLost = false)
      runCurrent()

      runCurrent()

      verifyOrder {
        rig.player.seekTo(seekNudgeMs(RESTING_MS))
        rig.player.seekTo(RESTING_MS)
      }
    }

  @Test
  fun `the landing on the millisecond beside the held slot is not answered and the one on the slot is`() =
    runTest {
      val rig = Rig(this)

      rig.seeks.represent(surfaceLost = false)
      runCurrent()

      runCurrent()
      rig.seeks.landsOn(seekNudgeMs(RESTING_MS)) shouldBe false
      rig.seeks.landsOn(RESTING_MS) shouldBe true
      rig.seeks.landsOn(seekNudgeMs(RESTING_MS)) shouldBe true
    }

  @Test
  fun `the playhead stays on the held slot while the seek back is in flight and after it lands`() =
    runTest {
      val rig = Rig(this)
      val held = outputSlotOf(rig.seeks.position(), RATE)

      rig.seeks.represent(surfaceLost = false)
      runCurrent()

      runCurrent()
      outputSlotOf(rig.seeks.position(), RATE) shouldBe held

      rig.seeks.frameDrawn()
      runCurrent()
      outputSlotOf(rig.seeks.position(), RATE) shouldBe held
      rig.seeks.target shouldBe null
    }

  @Test
  fun `a seek back for a handed output waits for its frame`() =
    runTest {
      val rig = Rig(this)

      rig.seeks.represent(surfaceLost = false)
      runCurrent()

      runCurrent()
      rig.seeks.accepted(rig.seeks.playerSerial)
      rig.represented shouldBe 0

      rig.seeks.frameDrawn()
      runCurrent()
      rig.represented shouldBe 1
    }

  @Test
  fun `a seek back made as the surface went lands when media3 accepts it`() =
    runTest {
      val rig = Rig(this)

      rig.seeks.represent(surfaceLost = true)
      runCurrent()

      runCurrent()
      rig.represented shouldBe 0

      rig.seeks.accepted(rig.seeks.playerSerial)
      rig.represented shouldBe 1
      rig.seeks.target shouldBe null
    }

  // media3 keeps playWhenReady on once it has ended, so a seek back would play the last slot out again. A player that
  // dropped its late frames at the end rests on an earlier slot, and is still sent to the last one.
  @Test
  fun `a player that ran to its end is stopped before the seek back draws its last slot`() =
    runTest {
      val rig = Rig(this)

      rig.seeks.represent(surfaceLost = true, ended = true)
      runCurrent()

      verifyOrder {
        rig.player.playWhenReady = false
        rig.player.seekTo(LAST_SLOT_MS)
      }
      verify(exactly = 0) { rig.player.seekTo(RESTING_MS) }
    }

  @Test
  fun `a player that has not ended is left as it was by the seek back`() =
    runTest {
      val rig = Rig(this)

      rig.seeks.represent(surfaceLost = true)
      runCurrent()

      verify(exactly = 0) { rig.player.playWhenReady = any() }
    }

  @Test
  fun `a second request adds nothing to a seek back in flight`() =
    runTest {
      val rig = Rig(this)

      rig.seeks.represent(surfaceLost = false)
      runCurrent()

      runCurrent()
      rig.seeks.represent(surfaceLost = false)
      runCurrent()

      verify(exactly = 1) { rig.player.seekTo(RESTING_MS) }
    }

  @Test
  fun `a seek in flight is handed over again and answers the chase once`() =
    runTest {
      val rig = Rig(this)
      var completions = 0
      rig.seeks.seek(ASKED, SeekAccuracy.Exact, wrap = false) { completions++ }

      rig.seeks.represent(surfaceLost = false)
      runCurrent()

      runCurrent()
      rig.seeks.frameDrawn()
      runCurrent()

      verify(exactly = 2) { rig.player.seekTo(seekNudgeMs(ASKED.inWholeMilliseconds)) }
      verify(exactly = 2) { rig.player.seekTo(ASKED.inWholeMilliseconds) }
      completions shouldBe 1
      rig.represented shouldBe 0
    }

  @Test
  fun `a seek asked for takes the place of a seek back in flight`() =
    runTest {
      val rig = Rig(this)
      var completions = 0
      rig.seeks.represent(surfaceLost = false)
      runCurrent()

      rig.seeks.seek(ASKED, SeekAccuracy.Exact, wrap = false) { completions++ }
      rig.seeks.frameDrawn()
      runCurrent()

      completions shouldBe 1
      rig.represented shouldBe 0
    }

  @Test
  fun `nothing is seeked with no composition loaded`() =
    runTest {
      val rig = Rig(this, loaded = false)

      rig.seeks.represent(surfaceLost = true)
      runCurrent()

      runCurrent()

      verify(exactly = 0) { rig.player.seekTo(any<Long>()) }
      rig.seeks.target shouldBe null
    }

  // media3's clock stops a slot or two behind the last frame it drew, and that frame is the one the playhead reports.
  @Test
  fun `a paused player is seeked back to the slot of the frame it shows and not to where its clock stopped`() =
    runTest {
      val rig = Rig(this)
      rig.resting = SHOWN
      rig.seeks.position() shouldBe SHOWN

      rig.seeks.represent(surfaceLost = true)
      runCurrent()

      rig.seeks.position() shouldBe SHOWN
      verifyOrder {
        rig.player.seekTo(seekNudgeMs(SHOWN.inWholeMilliseconds))
        rig.player.seekTo(SHOWN.inWholeMilliseconds)
      }
      verify(exactly = 0) { rig.player.seekTo(RESTING_MS) }
    }

  // The surface took its frame with it, and the clock the engine mirrors still reads from before the seek back.
  @Test
  fun `the playhead stays on the slot a paused player showed once the seek back for a lost surface has landed`() =
    runTest {
      val rig = Rig(this)
      rig.resting = SHOWN
      rig.seeks.position() shouldBe SHOWN

      rig.seeks.represent(surfaceLost = true)
      runCurrent()
      rig.resting = null
      rig.seeks.accepted(rig.seeks.playerSerial)

      rig.represented shouldBe 1
      rig.seeks.position() shouldBe SHOWN
    }

  // A slow decoder leaves the frame on screen behind the clock, and the surface took what named it.
  @Test
  fun `a paused player whose clock reads past the frame it showed is seeked back to that frame`() =
    runTest {
      val rig = Rig(this, restingMs = AHEAD_MS)
      rig.resting = SHOWN
      rig.seeks.position() shouldBe SHOWN
      rig.resting = null

      rig.seeks.represent(surfaceLost = true)
      runCurrent()
      rig.seeks.accepted(rig.seeks.playerSerial)

      verify(exactly = 1) { rig.player.seekTo(SHOWN.inWholeMilliseconds) }
      verify(exactly = 0) { rig.player.seekTo(AHEAD_MS) }
      rig.seeks.position() shouldBe SHOWN
    }

  /**
   * A coordinator over a paused player, with every post run inline and frames reaching a surface.
   */
  private class Rig(
    scope: TestScope,
    loaded: Boolean = true,
    restingMs: Long = RESTING_MS,
  ) {
    var represented = 0
    var resting: Duration? = null

    private val handler =
      mockk<Handler>().also {
        every { it.post(any()) } answers {
          firstArg<Runnable>().run()
          true
        }
      }

    val player =
      mockk<Player>().also {
        every { it.currentPosition } returns restingMs
        every { it.playbackState } returns Player.STATE_READY
        every { it.isPlaying } returns false
        every { it.playWhenReady } returns false
        every { it.playWhenReady = any() } returns Unit
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
        playback = { null },
        resolved = { composition.takeIf { loaded } },
        guard = WrapGuard(),
        platformPositionMs = { restingMs },
        loopWrap = { null },
        scrubbing = { false },
        showsFrames = { true },
        onIssued = { },
        onRedirected = { },
        resting = { resting },
        playWanted = { false },
        onRepresented = { represented++ },
      )
  }

  private companion object {
    const val RATE = 30
    val LENGTH = 3.seconds

    // Slot 21 of a 30 fps edit starts on a whole millisecond, so the player rests on the slot's own time.
    const val RESTING_MS = 700L

    // Slot 45, a second and a half in, which is also a whole millisecond.
    val ASKED = 1_500.milliseconds

    // Slot 24, three slots on from where the clock stopped.
    val SHOWN = 800.milliseconds

    // Slot 27, three slots past the frame shown.
    const val AHEAD_MS = 900L

    // Slot 89, the last of the three seconds, which media3 is handed floored to its millisecond.
    const val LAST_SLOT_MS = 2_966L
  }
}

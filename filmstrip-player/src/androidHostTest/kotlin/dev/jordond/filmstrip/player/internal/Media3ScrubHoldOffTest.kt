package dev.jordond.filmstrip.player.internal

import android.os.Handler
import androidx.media3.common.Player
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Which players a scrub leaves out of media3's scrubbing mode and what puts them in it, with the seeks and the mode
 * wired as the engine wires them and the player's thread run inline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Media3ScrubHoldOffTest {
  @Test
  fun `a scrub begun on a playing player leaves it out of the mode until its first seek was handed over`() =
    runTest {
      val rig = Rig(this, playing = true)

      rig.mode.scrubbingChanged(true)
      rig.calls.shouldBeEmpty()

      rig.seeks.seek(TARGET, SeekAccuracy.Exact, wrap = false) { }
      runCurrent()

      rig.calls.last() shouldBe MODE_ON
      rig.seeksTo.last() shouldBe TARGET.inWholeMilliseconds
    }

  // media3 skips a seek to the millisecond it holds, which a playing player reports only an estimate of. A seek it
  // skipped resets nothing the player had read, and the mode entered behind it draws the frame read furthest ahead.
  @Test
  fun `the first seek of a scrub on a playing player goes through the millisecond beside it wherever it is for`() =
    runTest {
      val rig = Rig(this, playing = true)
      rig.mode.scrubbingChanged(true)

      rig.seeks.seek(PASSING, SeekAccuracy.Exact, wrap = false) { }
      runCurrent()

      rig.seeksTo shouldBe listOf(seekNudgeMs(RESTING_MS), RESTING_MS)
      rig.calls shouldBe listOf(SEEK, SEEK, MODE_ON)
    }

  @Test
  fun `the first seek of a scrub on a playing player goes through the millisecond beside one it is not passing`() =
    runTest {
      val rig = Rig(this, playing = true)
      rig.mode.scrubbingChanged(true)

      rig.seeks.seek(TARGET, SeekAccuracy.Exact, wrap = false) { }
      runCurrent()

      rig.seeksTo shouldBe listOf(seekNudgeMs(TARGET.inWholeMilliseconds), TARGET.inWholeMilliseconds)
    }

  @Test
  fun `a scrub begun on a player paused after a play leaves it out of the mode until its first seek was handed over`() =
    runTest {
      val rig = Rig(this, playing = false)
      rig.seeks.seek(START, SeekAccuracy.Exact, wrap = false) { }
      rig.seeks.resume(at = START, asked = rig.seeks.seeksAsked)
      rig.seeks.pause(START)
      runCurrent()
      rig.calls.clear()

      rig.mode.scrubbingChanged(true)
      rig.calls.shouldBeEmpty()

      rig.seeks.seek(TARGET, SeekAccuracy.Exact, wrap = false) { }
      runCurrent()

      rig.calls.first() shouldBe SEEK
      rig.calls.last() shouldBe MODE_ON
    }

  @Test
  fun `a play asked for in a scrub the player is left out of does not put it in the mode`() =
    runTest {
      val rig = Rig(this, playing = false)
      rig.mode.scrubbingChanged(true)

      rig.seeks.resume(at = START, asked = rig.seeks.seeksAsked)
      runCurrent()

      rig.calls.shouldBeEmpty()
      rig.playing shouldBe true
    }

  @Test
  fun `a scrub begun on a player resting on a seek puts it in the mode at once`() =
    runTest {
      val rig = Rig(this, playing = false)
      rig.seeks.seek(START, SeekAccuracy.Exact, wrap = false) { }
      runCurrent()
      rig.calls.clear()

      rig.mode.scrubbingChanged(true)

      rig.calls shouldBe listOf(MODE_ON)
    }

  @Test
  fun `the second seek of a scrub on a playing player goes over in the mode and is not put in it again`() =
    runTest {
      val rig = Rig(this, playing = true)
      rig.mode.scrubbingChanged(true)
      rig.seeks.seek(TARGET, SeekAccuracy.Exact, wrap = false) { }
      runCurrent()
      rig.calls.clear()

      rig.seeks.seek(START, SeekAccuracy.Exact, wrap = false) { }
      runCurrent()

      rig.calls shouldBe listOf(SEEK)
    }

  private class Rig(
    scope: TestScope,
    var playing: Boolean,
  ) {
    val calls = mutableListOf<String>()
    val seeksTo = mutableListOf<Long>()

    private val handler =
      mockk<Handler>().also {
        every { it.post(any()) } answers {
          firstArg<Runnable>().run()
          true
        }
      }

    private val player =
      mockk<Player>().also {
        every { it.currentPosition } returns RESTING_MS
        every { it.playbackState } returns Player.STATE_READY
        every { it.isPlaying } answers { playing }
        every { it.playWhenReady } answers { playing }
        every { it.playWhenReady = any() } answers { playing = firstArg() }
        every { it.seekTo(any<Long>()) } answers {
          calls += SEEK
          seeksTo += firstArg<Long>()
        }
      }

    private val composition =
      mockk<ResolvedComposition>().also {
        every { it.duration } returns LENGTH
        every { it.frameRate } returns RATE
      }

    val mode: Media3ScrubMode =
      Media3ScrubMode(handler, holdsOff = { seeks.playedSinceSeek }) { calls += if (it) MODE_ON else MODE_OFF }

    val seeks: Media3SeekCoordinator =
      Media3SeekCoordinator(
        scope = scope,
        handler = handler,
        player = { player },
        playback = { null },
        resolved = { composition },
        guard = WrapGuard(),
        platformPositionMs = { RESTING_MS },
        loopWrap = { null },
        showsFrames = { true },
        scrubbing = { mode.active },
        inScrubbingMode = { mode.entered },
        enterScrubbingMode = mode::enter,
        onIssued = { },
        onRedirected = { },
        onRepresented = { },
      )
  }

  private companion object {
    const val RATE = 30
    const val RESTING_MS = 100L
    const val SEEK = "seek"
    const val MODE_ON = "mode on"
    const val MODE_OFF = "mode off"
    val LENGTH = 3.seconds
    val START = outputSlotTime(6, RATE)
    val TARGET = outputSlotTime(20, RATE)

    // Slot 3, which starts on the millisecond the player reads.
    val PASSING = outputSlotTime(3, RATE)
  }
}

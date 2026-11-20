package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What the playhead reads and pushes once the player has paused or ended.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Media3PausedFrameTest {
  @Test
  fun `a paused player rests on the slot of the frame on the surface`() =
    runTest {
      val rig = Rig(this)
      rig.renderedUs = DRAWN_AT.inWholeMicroseconds + 1

      rig.frame.resting() shouldBe DRAWN_AT
      rig.frame.drawn() shouldBe DRAWN_AT
    }

  @Test
  fun `a playing player rests on no frame though one was drawn`() =
    runTest {
      val rig = Rig(this)
      rig.renderedUs = DRAWN_AT.inWholeMicroseconds
      rig.playWanted = true

      rig.frame.resting().shouldBeNull()
      rig.frame.drawn() shouldBe DRAWN_AT
    }

  @Test
  fun `a player with no frame drawn rests on none`() =
    runTest {
      val rig = Rig(this)

      rig.frame.resting().shouldBeNull()
      rig.frame.drawn().shouldBeNull()
    }

  @Test
  fun `a frame drawn while paused pushes the playhead and one drawn while playing pushes nothing`() =
    runTest {
      val rig = Rig(this)

      rig.frame.onFrame()
      runCurrent()
      rig.pushed shouldBe listOf(CLOCK_AT)

      rig.playWanted = true
      rig.frame.onFrame()
      runCurrent()
      rig.pushed shouldBe listOf(CLOCK_AT)
    }

  // media3's playback thread stops a little after the pause read the clock, and the mirror's last reading is that later
  // clock.
  @Test
  fun `a clock read after a pause pushes the playhead and one read while playing pushes nothing`() =
    runTest {
      val rig = Rig(this)

      rig.frame.onClockRead()
      rig.pushed shouldBe listOf(CLOCK_AT)

      rig.playWanted = true
      rig.frame.onClockRead()
      rig.pushed shouldBe listOf(CLOCK_AT)
    }

  // The last frame is a slot short of the duration, and the end is the duration.
  @Test
  fun `a frame drawn after the end pushes the duration and not the slot of the frame`() =
    runTest {
      val rig = Rig(this)
      rig.ended = true

      rig.frame.onFrame()
      runCurrent()

      rig.frame.position() shouldBe LENGTH
      rig.pushed shouldBe listOf(LENGTH)
    }

  @Test
  fun `the end pushes the duration though the clock last read past it`() =
    runTest {
      val rig = Rig(this)
      rig.clock = LENGTH + OVERRUN
      rig.ended = true

      rig.frame.onEnded()

      rig.pushed shouldBe listOf(LENGTH)
    }

  @Test
  fun `a clock that runs past the duration reads the duration`() =
    runTest {
      val rig = Rig(this)
      rig.clock = LENGTH + OVERRUN

      rig.frame.position() shouldBe LENGTH
    }

  @Test
  fun `a pause after the end pushes the duration`() =
    runTest {
      val rig = Rig(this)
      rig.ended = true

      rig.frame.onPaused()
      runCurrent()

      rig.pushed shouldBe listOf(LENGTH)
    }

  @Test
  fun `a pause pushes the playhead once the engine gets to it`() =
    runTest {
      val rig = Rig(this)

      rig.frame.onPaused()
      rig.pushed.shouldBeEmpty()
      runCurrent()

      rig.pushed shouldBe listOf(CLOCK_AT)
    }

  // A sample a third-speed clip holds on three slots is stamped on the first of them. A play from the middle slot went
  // back to that first slot, and with nothing later drawn the frame is still the one the play started from.
  @Test
  fun `the sample a replay went back for reads the slot the replay was for`() =
    runTest {
      val rig = Rig(this)
      rig.replayed = slot(HELD_FIRST + 1)
      rig.renderedUs = slot(HELD_FIRST).inWholeMicroseconds

      rig.frame.resting() shouldBe slot(HELD_FIRST + 1)
      rig.frame.drawn() shouldBe slot(HELD_FIRST + 1)
    }

  @Test
  fun `a sample drawn past the one a replay went back for reads its own slot`() =
    runTest {
      val rig = Rig(this)
      rig.replayed = slot(HELD_FIRST + 1)
      rig.renderedUs = slot(HELD_FIRST + HOLD).inWholeMicroseconds

      rig.frame.resting() shouldBe slot(HELD_FIRST + HOLD)
    }

  @Test
  fun `a frame of another sample than the one replayed reads its own slot`() =
    runTest {
      val rig = Rig(this)
      rig.replayed = slot(HELD_FIRST + HOLD + 1)
      rig.renderedUs = slot(HELD_FIRST).inWholeMicroseconds

      rig.frame.resting() shouldBe slot(HELD_FIRST)
    }

  private class Rig(
    scope: TestScope,
  ) {
    var renderedUs = NO_FRAME
    var ended = false
    var playWanted = false
    var clock = CLOCK_AT
    var replayed: Duration? = null
    val pushed = mutableListOf<Duration>()

    val frame =
      Media3PausedFrame(
        scope = scope,
        renderedUs = { renderedUs },
        isEnded = { ended },
        isPlayWanted = { playWanted },
        landingOf = { outputSlotTime(outputSlotOf(it, RATE), RATE) },
        firstSlotOf = { slot(outputSlotOf(it, RATE).toInt().let { index -> index - index % HOLD }) },
        replayed = { replayed },
        duration = { LENGTH },
        playhead = { clock },
        emitPosition = { pushed += it },
      )
  }

  private companion object {
    const val RATE = 30
    val LENGTH = 3.seconds
    val DRAWN_AT = outputSlotTime(22, RATE)
    val CLOCK_AT = outputSlotTime(40, RATE)
    val OVERRUN = 9.milliseconds
    const val HOLD = 3
    const val HELD_FIRST = 21

    fun slot(index: Int): Duration = outputSlotTime(index.toLong(), RATE)
  }
}

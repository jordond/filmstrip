package dev.jordond.filmstrip.player.internal

import android.os.Handler
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * When a redraw is asked of media3 and when the frame drawn for it is reported, with the player's thread run inline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Media3RedrawsTest {
  @Test
  fun `a change is reported once the frame is drawn and not when the redraw is asked for`() =
    runTest {
      val rig = Rig(this)

      rig.redraws.requestChanged()
      runCurrent()
      rig.drawn shouldBe 1
      rig.reported shouldBe 0

      rig.redraws.frameDrawn()
      runCurrent()
      rig.reported shouldBe 1

      rig.redraws.frameDrawn()
      runCurrent()
      rig.reported shouldBe 1
    }

  @Test
  fun `a change with no surface is reported at once and draws nothing`() =
    runTest {
      val rig = Rig(this, showsFrames = false)

      rig.redraws.requestChanged()

      rig.reported shouldBe 1
      rig.drawn shouldBe 0
    }

  @Test
  fun `a change is asked for again while an earlier redraw is on its way`() =
    runTest {
      val rig = Rig(this)

      rig.redraws.requestHeld()
      rig.redraws.requestChanged()

      rig.drawn shouldBe 2
    }

  @Test
  fun `a held redraw is skipped until the last one has drawn its frame`() =
    runTest {
      val rig = Rig(this)

      rig.redraws.requestHeld()
      rig.redraws.requestHeld()
      rig.drawn shouldBe 1

      rig.redraws.frameDrawn()
      rig.redraws.requestHeld()
      rig.drawn shouldBe 2
    }

  @Test
  fun `a change still owed a frame is answered as the surface goes`() =
    runTest {
      val rig = Rig(this)

      rig.redraws.requestChanged()
      rig.redraws.surfaceDetached()

      rig.reported shouldBe 1
    }

  @Test
  fun `a frame already on its way when a change is asked for does not answer it`() =
    runTest {
      val rig = Rig(this, playbackRunsAtOnce = false)

      // A held redraw is in flight with a frame to come, and the change's redraw is queued behind it on media3's
      // thread.
      rig.redraws.requestHeld()
      rig.redraws.requestChanged()
      runCurrent()
      rig.drawn shouldBe 2

      rig.redraws.frameDrawn()
      runCurrent()
      rig.reported shouldBe 0

      rig.runPlayback()
      rig.redraws.frameDrawn()
      runCurrent()
      rig.reported shouldBe 1
    }

  @Test
  fun `a change asked for again is answered by a frame after the latest redraw only`() =
    runTest {
      val rig = Rig(this, playbackRunsAtOnce = false)

      rig.redraws.requestChanged()
      rig.runPlayback()
      rig.redraws.requestChanged()
      rig.redraws.frameDrawn()
      runCurrent()
      rig.reported shouldBe 0

      rig.runPlayback()
      rig.redraws.frameDrawn()
      runCurrent()
      rig.reported shouldBe 1
    }

  @Test
  fun `a paused redraw whose frame is drawn before anything queued behind it runs answers the change`() =
    runTest {
      val rig = Rig(this, playbackRunsAtOnce = false, paused = true, drawsAtOnce = true)

      rig.redraws.requestChanged()
      runCurrent()

      rig.drawn shouldBe 1
      rig.reported shouldBe 1
    }

  @Test
  fun `a playing redraw is not answered by a frame drawn before the playback thread has handled it`() =
    runTest {
      val rig = Rig(this, playbackRunsAtOnce = false, drawsAtOnce = true)

      rig.redraws.requestChanged()
      runCurrent()
      rig.reported shouldBe 0

      rig.runPlayback()
      rig.redraws.frameDrawn()
      runCurrent()
      rig.reported shouldBe 1
    }

  private class Rig(
    scope: TestScope,
    showsFrames: Boolean = true,
    private val playbackRunsAtOnce: Boolean = true,
    paused: Boolean = false,
    private val drawsAtOnce: Boolean = false,
  ) {
    var drawn = 0
    var reported = 0
    private val queued = mutableListOf<Runnable>()

    /**
     * Runs what has been posted to media3's playback thread, as that thread gets to it.
     */
    fun runPlayback() {
      queued.toList().also { queued.clear() }.forEach { it.run() }
    }

    private val playback =
      mockk<Handler>().also {
        every { it.post(any()) } answers {
          if (playbackRunsAtOnce) firstArg<Runnable>().run() else queued += firstArg<Runnable>()
          true
        }
      }

    private val handler =
      mockk<Handler>().also {
        every { it.post(any()) } answers {
          firstArg<Runnable>().run()
          true
        }
      }

    private fun frameDrawn() = redraws.frameDrawn()

    val redraws: Media3Redraws =
      Media3Redraws(
        scope = scope,
        handler = handler,
        playback = { playback },
        redraw = {
          drawn++
          // media3 draws the frame once its playback thread has run what was queued ahead of the redraw, and before
          // it gets to anything queued behind.
          if (drawsAtOnce) {
            runPlayback()
            frameDrawn()
          }
        },
        paused = { paused },
        showsFrames = { showsFrames },
        report = { reported++ },
      )
  }
}

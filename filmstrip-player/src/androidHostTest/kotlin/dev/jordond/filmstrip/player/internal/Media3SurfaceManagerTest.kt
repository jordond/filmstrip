package dev.jordond.filmstrip.player.internal

import android.os.Handler
import android.view.SurfaceHolder
import android.view.SurfaceView
import dev.jordond.filmstrip.geometry.Size
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Which view the player draws into and what its buffer is fixed to, with the player's thread run inline.
 *
 * No player stands behind the manager here, since media3's player cannot even be loaded on a host JVM. What it does to
 * the player is the device suite's to check.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Media3SurfaceManagerTest {
  private val holder = mockk<SurfaceHolder>(relaxed = true)
  private val resizes = slot<SurfaceHolder.Callback>()
  private val view =
    mockk<SurfaceView>().also {
      every { it.holder } returns holder
      every { holder.addCallback(capture(resizes)) } returns Unit
    }
  private val target = SurfaceViewTarget(view)

  @Test
  fun `attaching takes the view up and fixes its buffer to the rendered frame`() =
    runTest {
      val rig = Rig(this, rendered = FRAME)

      rig.manager.attach(target)
      runCurrent()

      rig.manager.attached shouldBe target
      rig.manager.previewTarget shouldBe target
      rig.manager.applications shouldBe 1
      verify(exactly = 1) { holder.setFixedSize(FRAME.width, FRAME.height) }
    }

  @Test
  fun `a view attached before anything is rendered keeps the buffer it has`() =
    runTest {
      val rig = Rig(this, rendered = null)

      rig.manager.attach(target)
      runCurrent()

      verify(exactly = 0) { holder.setFixedSize(any(), any()) }
    }

  @Test
  fun `the buffer only moves when the rendered frame does`() =
    runTest {
      val rig = Rig(this, rendered = null)

      rig.manager.fixBuffer(target, FRAME)
      rig.manager.fixBuffer(target, FRAME)
      rig.manager.fixBuffer(target, PORTRAIT)
      rig.manager.fixBuffer(null, FRAME)

      verify(exactly = 1) { holder.setFixedSize(FRAME.width, FRAME.height) }
      verify(exactly = 1) { holder.setFixedSize(PORTRAIT.width, PORTRAIT.height) }
    }

  @Test
  fun `detaching lets the view go and says so on the engine's dispatcher`() =
    runTest {
      val rig = Rig(this, rendered = FRAME)
      rig.manager.attach(target)
      runCurrent()

      rig.manager.detach(view)
      rig.detaches shouldBe 0
      runCurrent()

      rig.detaches shouldBe 1
      rig.manager.attached shouldBe null
      rig.manager.previewTarget shouldBe null
      verify(exactly = 1) { holder.removeCallback(resizes.captured) }
    }

  @Test
  fun `detaching a view the player is not drawing into changes nothing`() =
    runTest {
      val rig = Rig(this, rendered = FRAME)
      val other = mockk<SurfaceView>().also { every { it.holder } returns mockk(relaxed = true) }
      rig.manager.attach(target)
      runCurrent()

      rig.manager.detach(other)
      runCurrent()

      rig.detaches shouldBe 0
      rig.manager.attached shouldBe target
      rig.manager.previewTarget shouldBe target
    }

  @Test
  fun `a view taken up again has its buffer fixed again`() =
    runTest {
      val rig = Rig(this, rendered = FRAME)
      rig.manager.attach(target)
      runCurrent()
      rig.manager.detach(view)
      runCurrent()

      rig.manager.attach(target)
      runCurrent()

      verify(exactly = 2) { holder.setFixedSize(FRAME.width, FRAME.height) }
    }

  @Test
  fun `a resize with no player standing touches nothing`() =
    runTest {
      val rig = Rig(this, rendered = FRAME, graphUp = true)
      rig.manager.attach(target)
      runCurrent()

      resizes.captured.surfaceChanged(holder, 0, PORTRAIT.width, PORTRAIT.height)

      rig.manager.applications shouldBe 1
      rig.manager.redraws shouldBe 0
    }

  @Test
  fun `releasing forgets the view the player drew into`() =
    runTest {
      val rig = Rig(this, rendered = FRAME)
      rig.manager.attach(target)
      runCurrent()

      rig.manager.release()

      rig.manager.attached shouldBe null
    }

  /**
   * A manager whose player's thread runs every post inline.
   */
  private inner class Rig(
    scope: TestScope,
    rendered: Size?,
    graphUp: Boolean = false,
  ) {
    var detaches = 0

    private val handler =
      mockk<Handler>().also {
        every { it.post(any()) } answers {
          firstArg<Runnable>().run()
          true
        }
      }

    val manager =
      Media3SurfaceManager(
        scope = scope,
        handler = handler,
        player = { null },
        rendered = { rendered },
        graphUp = { graphUp },
        onDetached = { detaches++ },
      )
  }

  private companion object {
    val FRAME = Size(1920, 1080)
    val PORTRAIT = Size(1080, 1920)
  }
}

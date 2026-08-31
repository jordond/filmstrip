package dev.jordond.filmstrip.playback.internal

import android.graphics.Bitmap
import android.view.SurfaceView
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The shutter's lifecycle, taken apart from the surface it freezes.
 *
 * A capture needs a compositor and a rebuild needs a decoder, neither of which a host has, so what
 * is checked here is the ordering the two hang off: a swap holds the frame it was given, the graph
 * drawing again drops it, and nothing holds it longer than the timeout.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PreviewShutterTest {
  private val view = mockk<SurfaceView>()
  private val frame = mockk<Bitmap>()
  private val later = mockk<Bitmap>()

  @Test
  fun `a swap holds the captured frame until the graph draws again`() =
    runTest {
      val capture = FakeCapture()
      val shutter = shutter(capture)
      val seen = mutableListOf<Bitmap?>()
      shutter.addListener { seen += it }

      shutter.close(view)
      capture.deliver(frame)
      runCurrent()
      shutter.still shouldBe frame

      shutter.reveal()

      shutter.still shouldBe null
      capture.requests shouldBe 1
      seen shouldBe listOf(null, frame, null)
    }

  @Test
  fun `a still nothing dropped is let go of once the timeout elapses`() =
    runTest {
      val capture = FakeCapture()
      val shutter = shutter(capture)

      shutter.close(view)
      capture.deliver(frame)
      runCurrent()
      shutter.still shouldBe frame

      advanceTimeBy(TIMEOUT / 2)
      shutter.still shouldBe frame

      advanceTimeBy(TIMEOUT)

      shutter.still shouldBe null
    }

  @Test
  fun `a frame that arrives after its own swap was revealed is dropped`() =
    runTest {
      val capture = FakeCapture()
      val shutter = shutter(capture)

      shutter.close(view)
      shutter.reveal()
      capture.deliver(frame)
      runCurrent()

      shutter.still shouldBe null
    }

  @Test
  fun `the next swap replaces the still the last one was holding`() =
    runTest {
      val capture = FakeCapture()
      val shutter = shutter(capture)
      val seen = mutableListOf<Bitmap?>()
      shutter.addListener { seen += it }

      shutter.close(view)
      capture.deliver(frame)
      runCurrent()

      shutter.close(view)
      capture.deliver(later)
      runCurrent()

      shutter.still shouldBe later
      seen shouldBe listOf(null, frame, null, later)
    }

  @Test
  fun `whatever disturbs the surface waits for the capture to have read it`() =
    runTest {
      val capture = FakeCapture()
      val shutter = shutter(capture)
      val order = mutableListOf<String>()

      shutter.close(view) { order += "disturbed" }
      order.shouldBeEmpty()

      capture.deliver(frame)
      runCurrent()

      order shouldBe listOf("disturbed")
      shutter.still shouldBe frame
    }

  /**
   * The swap that started last is the one the surface belongs to.
   *
   * Its own capture and its own disturbance are what the surface is measured against, so an older
   * swap still being read publishes nothing and disturbs nothing when it finally lands. A stale
   * disturbance would be applied to a surface that has already moved on.
   */
  @Test
  fun `a swap superseded mid capture neither publishes nor disturbs the surface`() =
    runTest {
      val capture = FakeCapture()
      val shutter = shutter(capture)
      val disturbed = mutableListOf<String>()

      shutter.close(view) { disturbed += "first" }
      shutter.close(view) { disturbed += "second" }

      capture.deliverNewest(later)
      runCurrent()
      capture.deliver(frame)
      runCurrent()

      capture.requests shouldBe 2
      disturbed shouldBe listOf("second")
      shutter.still shouldBe later
    }

  @Test
  fun `a swap with no surface to read disturbs it at once, since nothing is waiting`() =
    runTest {
      val shutter = shutter(FakeCapture())
      var disturbed = false

      shutter.close(null) { disturbed = true }

      disturbed shouldBe true
    }

  @Test
  fun `a swap with no surface attached holds nothing and needs no timeout`() =
    runTest {
      val capture = FakeCapture()
      val shutter = shutter(capture)

      shutter.close(null)
      advanceTimeBy(TIMEOUT * 2)

      shutter.still shouldBe null
      capture.requests shouldBe 0
    }

  @Test
  fun `a capture that failed leaves the live surface showing`() =
    runTest {
      val capture = FakeCapture()
      val shutter = shutter(capture)

      shutter.close(view)
      capture.deliver(null)
      runCurrent()

      shutter.still shouldBe null
    }

  @Test
  fun `a listener attaching mid swap is given the standing still`() =
    runTest {
      val capture = FakeCapture()
      val shutter = shutter(capture)

      shutter.close(view)
      capture.deliver(frame)
      runCurrent()

      val seen = mutableListOf<Bitmap?>()
      val handle = shutter.addListener { seen += it }
      shutter.reveal()
      handle.cancel()
      shutter.close(view)
      capture.deliver(later)
      runCurrent()

      seen shouldBe listOf(frame, null)
    }

  private fun TestScope.shutter(capture: FakeCapture) = PreviewShutter(backgroundScope, TIMEOUT, capture)

  /**
   * A capture that answers when the test says so, the way `PixelCopy` answers a frame or two later.
   *
   * Requests queue rather than replace each other, so a test can hold two open at once and answer
   * them in whichever order it wants.
   */
  private class FakeCapture : SurfaceCapture {
    private val pending = ArrayDeque<(Bitmap?) -> Unit>()

    var requests: Int = 0
      private set

    override fun capture(
      view: SurfaceView,
      onFrame: (Bitmap?) -> Unit,
    ) {
      requests++
      pending += onFrame
    }

    /**
     * Answers the oldest capture still waiting.
     */
    fun deliver(frame: Bitmap?) {
      val waiting = checkNotNull(pending.removeFirstOrNull()) { "Nothing asked for a capture." }
      waiting(frame)
    }

    /**
     * Answers the newest capture still waiting, leaving the older ones open.
     */
    fun deliverNewest(frame: Bitmap?) {
      val waiting = checkNotNull(pending.removeLastOrNull()) { "Nothing asked for a capture." }
      waiting(frame)
    }
  }

  private companion object {
    val TIMEOUT: Duration = 500.milliseconds
  }
}

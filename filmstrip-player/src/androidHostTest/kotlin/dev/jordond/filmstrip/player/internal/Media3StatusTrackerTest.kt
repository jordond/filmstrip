package dev.jordond.filmstrip.player.internal

import android.graphics.Bitmap
import android.view.SurfaceView
import androidx.media3.common.C
import androidx.media3.common.Player
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackStatus
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * What the media3 engine publishes for each state media3 reports, taken apart from the player that reports it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Media3StatusTrackerTest {
  private val view = mockk<SurfaceView>()
  private val frame = mockk<Bitmap>()

  @Test
  fun `each platform state publishes the status it means`() =
    runTest {
      val rig = Rig(this)

      rig.state(Player.STATE_IDLE)
      rig.statuses.last() shouldBe PlaybackStatus.Preparing
      rig.state(Player.STATE_BUFFERING)
      rig.statuses.last() shouldBe PlaybackStatus.Preparing
      rig.stalled.last() shouldBe true
      rig.state(Player.STATE_READY)
      rig.statuses.last() shouldBe PlaybackStatus.Ready
      rig.stalled.last() shouldBe false
      rig.tracker.graphUp shouldBe true
      rig.state(Player.STATE_ENDED)
      rig.statuses.last() shouldBe PlaybackStatus.Ended
    }

  @Test
  fun `nothing loaded reads as idle`() =
    runTest {
      val rig = Rig(this)
      rig.hasPreview = false

      rig.state(Player.STATE_IDLE)

      rig.statuses.last() shouldBe PlaybackStatus.Idle
    }

  @Test
  fun `the platform's own state position and duration are kept as reported`() =
    runTest {
      val rig = Rig(this)

      rig.tracker.onPlatformState(Player.STATE_BUFFERING, positionMs = 640, durationMs = 1_500, running = true)

      rig.tracker.platformState shouldBe Player.STATE_BUFFERING
      rig.tracker.platformPositionMs shouldBe 640
      rig.tracker.platformDurationMs shouldBe 1_500
    }

  @Test
  fun `an end is reported once with where playback stopped and stops the transport being asked for`() =
    runTest {
      val rig = Rig(this)
      rig.state(Player.STATE_READY)

      rig.state(Player.STATE_ENDED)
      rig.state(Player.STATE_ENDED)

      rig.events shouldBe listOf(PlaybackEvent.Ended(POSITION, DURATION))
      rig.playWhenReady shouldBe listOf(false)
    }

  @Test
  fun `an end after the ending was cleared is reported again`() =
    runTest {
      val rig = Rig(this)
      rig.state(Player.STATE_ENDED)

      rig.tracker.clearEnd()
      rig.state(Player.STATE_READY)
      rig.statuses.last() shouldBe PlaybackStatus.Ready
      rig.state(Player.STATE_ENDED)

      rig.events.filterIsInstance<PlaybackEvent.Ended>().size shouldBe 2
    }

  @Test
  fun `an end inside the loop range publishes nothing and asks whether the transport ran`() =
    runTest {
      val rig = Rig(this)
      rig.state(Player.STATE_READY)
      val published = rig.statuses.size
      rig.wraps = true

      rig.tracker.onPlatformState(Player.STATE_ENDED, positionMs = 0, durationMs = 1_500, running = true)

      rig.wrapAsks shouldBe listOf(true)
      rig.statuses.size shouldBe published
      rig.events.shouldBeEmpty()
      rig.tracker.platformState shouldBe Player.STATE_ENDED
    }

  @Test
  fun `the first frame waits for both a drawn surface and a ready graph in either order`() =
    runTest {
      val surfaceFirst = Rig(this)
      surfaceFirst.tracker.onRenderedFirstFrame()
      surfaceFirst.events.shouldBeEmpty()
      surfaceFirst.state(Player.STATE_READY)
      surfaceFirst.events shouldBe listOf(PlaybackEvent.FirstFrameRendered)

      val graphFirst = Rig(this)
      graphFirst.state(Player.STATE_READY)
      graphFirst.events.shouldBeEmpty()
      graphFirst.tracker.onRenderedFirstFrame()
      graphFirst.events shouldBe listOf(PlaybackEvent.FirstFrameRendered)
    }

  @Test
  fun `the first frame is reported once per composition`() =
    runTest {
      val rig = Rig(this)
      rig.tracker.onRenderedFirstFrame()
      rig.state(Player.STATE_READY)
      rig.state(Player.STATE_BUFFERING)
      rig.state(Player.STATE_READY)
      rig.events.count { it == PlaybackEvent.FirstFrameRendered } shouldBe 1

      rig.tracker.reset()
      rig.state(Player.STATE_READY)

      rig.events.count { it == PlaybackEvent.FirstFrameRendered } shouldBe 2
    }

  @Test
  fun `a surface let go of earns its own first frame`() =
    runTest {
      val rig = Rig(this)
      rig.tracker.onRenderedFirstFrame()
      rig.state(Player.STATE_READY)

      rig.tracker.onSurfaceDetached()
      rig.state(Player.STATE_READY)
      rig.events.count { it == PlaybackEvent.FirstFrameRendered } shouldBe 1
      rig.tracker.onRenderedFirstFrame()

      rig.events.count { it == PlaybackEvent.FirstFrameRendered } shouldBe 2
    }

  @Test
  fun `the first frame lifts the shutter held over a rebuild`() =
    runTest {
      val rig = Rig(this)
      rig.shutter.close(view)
      runCurrent()
      rig.shutter.still shouldBe frame

      rig.tracker.onRenderedFirstFrame()
      rig.state(Player.STATE_READY)

      rig.shutter.still shouldBe null
    }

  @Test
  fun `a refusal holds the error status over later states until it is lifted`() =
    runTest {
      val rig = Rig(this)
      rig.state(Player.STATE_READY)
      rig.shutter.close(view)
      runCurrent()

      rig.tracker.fail(ERROR)
      rig.statuses.last() shouldBe PlaybackStatus.Error(ERROR)
      rig.events.last() shouldBe PlaybackEvent.Failed(ERROR)
      rig.tracker.graphUp shouldBe true
      rig.shutter.still shouldBe null

      rig.state(Player.STATE_READY)
      rig.statuses.last() shouldBe PlaybackStatus.Error(ERROR)

      rig.tracker.liftRefusal()
      rig.statuses.last() shouldBe PlaybackStatus.Ready
      rig.tracker.playerErrored shouldBe false
    }

  @Test
  fun `a refused plan leaves the running graph up so a resize only redraws`() =
    runTest {
      val rig = Rig(this)
      rig.state(Player.STATE_READY)

      rig.tracker.fail(ERROR)

      rig.tracker.graphUp shouldBe true
      surfaceResizeAction(rig.tracker.graphUp) shouldBe SurfaceResize.Redraw
    }

  @Test
  fun `a refusal before any graph was up leaves it down`() =
    runTest {
      val rig = Rig(this)

      rig.tracker.fail(ERROR)

      rig.tracker.graphUp shouldBe false
      surfaceResizeAction(rig.tracker.graphUp) shouldBe SurfaceResize.Reapply
    }

  @Test
  fun `a player error takes the graph down`() =
    runTest {
      val rig = Rig(this)
      rig.state(Player.STATE_READY)

      rig.tracker.onPlayerError(ERROR)

      rig.tracker.graphUp shouldBe false
      surfaceResizeAction(rig.tracker.graphUp) shouldBe SurfaceResize.Reapply
    }

  @Test
  fun `a player error stands until a reset and fails like a refusal`() =
    runTest {
      val rig = Rig(this)
      rig.state(Player.STATE_READY)

      rig.tracker.onPlayerError(ERROR)
      rig.tracker.playerErrored shouldBe true
      rig.statuses.last() shouldBe PlaybackStatus.Error(ERROR)
      rig.events.last() shouldBe PlaybackEvent.Failed(ERROR)

      rig.tracker.reset()
      rig.tracker.playerErrored shouldBe false
    }

  @Test
  fun `a reset forgets the graph and its readiness`() =
    runTest {
      val rig = Rig(this)
      rig.state(Player.STATE_READY)

      rig.tracker.reset()
      rig.tracker.graphUp shouldBe false
      rig.state(Player.STATE_BUFFERING)

      rig.statuses.last() shouldBe PlaybackStatus.Preparing
    }

  @Test
  fun `a transport change filmstrip asked for is not echoed`() =
    runTest {
      val rig = Rig(this)

      rig.tracker.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)

      rig.playWhenReady.shouldBeEmpty()
      rig.external.shouldBeEmpty()
    }

  @Test
  fun `an interruption is reported as a change made outside filmstrip`() =
    runTest {
      val rig = Rig(this)

      rig.tracker.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
      rig.tracker.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE)

      rig.external shouldBe listOf(false, true)
      rig.playWhenReady.shouldBeEmpty()
    }

  @Test
  fun `the end of the media and any other reason publish the transport media3 settled on`() =
    runTest {
      val rig = Rig(this)

      rig.tracker.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)
      rig.tracker.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG)

      rig.playWhenReady shouldBe listOf(false, false)
      rig.external.shouldBeEmpty()
    }

  @Test
  fun `an interruption stops the player before it reports the pause`() =
    runTest {
      val rig = Rig(this)

      rig.tracker.onInterrupted()

      rig.log shouldBe listOf("stop", "external false")
    }

  @Test
  fun `nothing reported yet reads as an idle player with no duration`() =
    runTest {
      val rig = Rig(this)

      rig.tracker.platformState shouldBe Player.STATE_IDLE
      rig.tracker.platformDurationMs shouldBe C.TIME_UNSET
      rig.tracker.graphUp shouldBe false
    }

  /**
   * A tracker whose engine side is recorded rather than acted on.
   */
  private inner class Rig(
    scope: TestScope,
  ) {
    var hasPreview = true
    var wraps = false
    val wrapAsks = mutableListOf<Boolean>()
    val statuses = mutableListOf<PlaybackStatus>()
    val stalled = mutableListOf<Boolean>()
    val playWhenReady = mutableListOf<Boolean>()
    val external = mutableListOf<Boolean>()
    val events = mutableListOf<PlaybackEvent>()
    val log = mutableListOf<String>()

    val shutter = PreviewShutter(scope, capture = { _, onFrame -> onFrame(frame) })

    val tracker =
      Media3StatusTracker(
        shutter = shutter,
        hasPreview = { hasPreview },
        wrapsAtEnd = { running ->
          wrapAsks += running
          wraps
        },
        position = { POSITION },
        duration = { DURATION },
        stopPlayer = { log += "stop" },
        setStatus = { statuses += it },
        setStalled = { stalled += it },
        setPlayWhenReady = { playWhenReady += it },
        reportExternalPlayWhenReady = {
          external += it
          log += "external $it"
        },
        emitEvent = { events += it },
      )

    fun state(state: Int) {
      tracker.onPlatformState(state, positionMs = 0, durationMs = 1_500, running = false)
    }
  }

  private companion object {
    val POSITION = 700.milliseconds
    val DURATION = 1_500.milliseconds
    val ERROR = PlaybackError.UnsupportedFormat("The decoder refused the clip.")
  }
}

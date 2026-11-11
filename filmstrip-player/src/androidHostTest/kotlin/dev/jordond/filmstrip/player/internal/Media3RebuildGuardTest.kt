package dev.jordond.filmstrip.player.internal

import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What the old graph left queued when a rebuild runs, with the player's thread driven by hand so each report is raised
 * exactly where a case puts it.
 *
 * The engine's dispatcher only runs at `runCurrent`, which is what holds a report queued across the rebuild. The clock
 * is stubbed since a [PlaybackException] stamps itself with one the host's android.jar does not implement.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Media3RebuildGuardTest {
  @BeforeTest
  fun stubClock() {
    mockkStatic(SystemClock::class)
    every { SystemClock.elapsedRealtime() } returns 0L
  }

  @AfterTest
  fun releaseClock() {
    unmockkAll()
  }

  @Test
  fun `a state queued before the rebuild is dropped after it`() =
    runTest {
      val rig = Rig(this)
      rig.listener.onPlaybackStateChanged(Player.STATE_READY)

      rig.loads.issue()
      runCurrent()

      rig.statuses.shouldBeEmpty()
      rig.status.platformPositionMs shouldBe 0
    }

  @Test
  fun `a state the old graph raises before the hand over is dropped`() =
    runTest {
      val rig = Rig(this)

      rig.loads.issue()
      rig.listener.onPlaybackStateChanged(Player.STATE_READY)
      runCurrent()

      rig.statuses.shouldBeEmpty()
    }

  @Test
  fun `a state the new graph raises arrives`() =
    runTest {
      val rig = Rig(this)

      rig.loads.handOver(rig.loads.issue())
      rig.listener.onPlaybackStateChanged(Player.STATE_READY)
      runCurrent()

      rig.statuses shouldBe listOf<PlaybackStatus>(PlaybackStatus.Ready)
      rig.status.platformPositionMs shouldBe READING_MS
    }

  @Test
  fun `a graph a second rebuild replaced before it was ready is dropped too`() =
    runTest {
      val rig = Rig(this)

      rig.loads.handOver(rig.loads.issue())
      rig.listener.onPlaybackStateChanged(Player.STATE_READY)
      rig.loads.issue()
      runCurrent()

      rig.statuses.shouldBeEmpty()
    }

  @Test
  fun `a failure queued before the rebuild is dropped after it`() =
    runTest {
      val rig = Rig(this)
      rig.listener.onPlayerError(PlaybackException("gone", null, PlaybackException.ERROR_CODE_UNSPECIFIED))

      rig.loads.issue()
      runCurrent()

      rig.statuses.shouldBeEmpty()
      rig.events.shouldBeEmpty()
    }

  @Test
  fun `a transport change queued before the rebuild still arrives`() =
    runTest {
      val rig = Rig(this)
      rig.listener.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)

      rig.loads.issue()
      runCurrent()

      rig.external shouldContain false
    }

  @Test
  fun `a first frame queued before the rebuild still arms the surface`() =
    runTest {
      val rig = Rig(this)
      rig.listener.onRenderedFirstFrame()

      rig.loads.handOver(rig.loads.issue())
      runCurrent()
      rig.listener.onPlaybackStateChanged(Player.STATE_READY)
      runCurrent()

      rig.events shouldContain PlaybackEvent.FirstFrameRendered
    }

  @Test
  fun `a seek landing queued before the rebuild answers the seek and leaves the position alone`() =
    runTest {
      val rig = Rig(this)
      val landed = mutableListOf<Duration>()
      rig.seeks.seek(READING_MS.milliseconds, SeekAccuracy.Exact, wrap = false) { landed += it }
      rig.runPosted()
      rig.listener.onPositionDiscontinuity(position(0), position(READING_MS), Player.DISCONTINUITY_REASON_SEEK)

      rig.loads.issue()
      rig.status.platformPositionMs = HELD_MS
      runCurrent()

      rig.status.platformPositionMs shouldBe HELD_MS
      landed.size shouldBe 1
    }

  @Test
  fun `a mirror reading queued before the rebuild is dropped after it`() =
    runTest {
      val rig = Rig(this)
      rig.loops.startMirror()
      rig.loops.stopMirror()
      rig.runPosted()

      rig.loads.issue()
      runCurrent()

      rig.recorded.shouldBeEmpty()
    }

  @Test
  fun `a mirror reading the old graph takes before the hand over is dropped`() =
    runTest {
      val rig = Rig(this)
      rig.loops.startMirror()
      rig.loops.stopMirror()

      rig.loads.issue()
      rig.runPosted()
      runCurrent()

      rig.recorded.shouldBeEmpty()
    }

  @Test
  fun `a mirror reading off the new graph arrives`() =
    runTest {
      val rig = Rig(this)
      rig.loops.startMirror()
      rig.loops.stopMirror()

      rig.loads.handOver(rig.loads.issue())
      rig.runPosted()
      runCurrent()

      rig.recorded shouldBe listOf(READING_MS)
    }

  private fun position(positionMs: Long): Player.PositionInfo =
    Player.PositionInfo(null, 0, null, null, 0, positionMs, positionMs, C.INDEX_UNSET, C.INDEX_UNSET)

  /**
   * One listener and one mirror over a player reading [READING_MS], whose thread only runs what [runPosted] lets it.
   */
  private class Rig(
    scope: TestScope,
  ) {
    val statuses = mutableListOf<PlaybackStatus>()
    val events = mutableListOf<PlaybackEvent>()
    val external = mutableListOf<Boolean>()
    val recorded = mutableListOf<Long>()
    val loads = Media3GraphLoads(scope)

    private val posted = ArrayDeque<Runnable>()
    private val handler =
      mockk<Handler>().also {
        every { it.post(any()) } answers {
          posted += firstArg<Runnable>()
          true
        }
      }
    private val player =
      mockk<Player>().also {
        every { it.currentPosition } returns READING_MS
        every { it.duration } returns C.TIME_UNSET
        every { it.playWhenReady } returns true
        every { it.playbackState } returns Player.STATE_READY
        every { it.isPlaying } returns true
        every { it.seekTo(any<Long>()) } returns Unit
      }
    private val loaded =
      mockk<ResolvedComposition>().also {
        every { it.duration } returns LENGTH
        every { it.frameRate } returns RATE
      }
    private val guard = WrapGuard()

    val status =
      Media3StatusTracker(
        shutter = PreviewShutter(scope, capture = { _, _ -> }),
        hasPreview = { true },
        wrapsAtEnd = { false },
        position = { Duration.ZERO },
        duration = { Duration.ZERO },
        stopPlayer = { },
        setStatus = { statuses += it },
        setStalled = { },
        setPlayWhenReady = { },
        reportExternalPlayWhenReady = { external += it },
        emitEvent = { events += it },
      )

    val seeks =
      Media3SeekCoordinator(
        scope = scope,
        handler = handler,
        player = { player },
        playbackLooper = { null },
        resolved = { loaded },
        guard = guard,
        platformPositionMs = { status.platformPositionMs },
        loopWrap = { null },
        showsFrames = { false },
        onIssued = { },
        onRedirected = { },
      )

    val loops =
      Media3LoopRangeController(
        loads = loads,
        handler = handler,
        player = { player },
        resolved = { null },
        guard = guard,
        playhead = { Duration.ZERO },
        recordPosition = { recorded += it },
        seekTo = { },
        seekToWrap = { },
        pause = { },
        onLooped = { },
      )

    val listener =
      Media3PlayerListener(
        generation = 0,
        standingGeneration = { 0 },
        player = { player },
        scope = scope,
        loads = loads,
        status = status,
        seeks = seeks,
        loops = loops,
      )

    fun runPosted() {
      while (posted.isNotEmpty()) posted.removeFirst().run()
    }
  }

  private companion object {
    const val READING_MS = 640L
    const val HELD_MS = 800L
    const val RATE = 30
    val LENGTH = 3.seconds
  }
}

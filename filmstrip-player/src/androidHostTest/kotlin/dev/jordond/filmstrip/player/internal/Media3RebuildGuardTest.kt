package dev.jordond.filmstrip.player.internal

import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputSlotTime
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
  fun `a player started on the standing generation lets go of the slot a pause pinned`() =
    runTest {
      val rig = Rig(this)
      rig.pin.pin(HELD_MS.milliseconds)

      rig.listener.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)

      rig.pins shouldBe listOf(HELD_MS.milliseconds, null)
    }

  @Test
  fun `a player of a generation since replaced starting leaves the pin alone`() =
    runTest {
      val rig = Rig(this)
      rig.pin.pin(HELD_MS.milliseconds)

      rig.listenerOf(generation = STALE).onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)

      rig.pins shouldBe listOf<Duration?>(HELD_MS.milliseconds)
    }

  @Test
  fun `a player stopping lets go of no pin`() =
    runTest {
      val rig = Rig(this)
      rig.pin.pin(HELD_MS.milliseconds)
      rig.playWhenReady = false

      rig.listener.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
      rig.runPosted()

      rig.pins shouldBe listOf<Duration?>(HELD_MS.milliseconds)
    }

  // No pause was asked for, so nothing else pins the slot the player stopped on.
  @Test
  fun `a player media3 stops itself is pinned to the frame it stopped on`() =
    runTest {
      val rig = Rig(this)
      rig.playWhenReady = false
      rig.drawn = HELD_MS.milliseconds

      rig.listener.onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
      rig.pins.shouldBeEmpty()
      rig.runPosted()

      rig.pins shouldBe listOf<Duration?>(HELD_MS.milliseconds)
    }

  @Test
  fun `a player of a generation since replaced stopping pins nothing`() =
    runTest {
      val rig = Rig(this)
      rig.playWhenReady = false
      rig.drawn = HELD_MS.milliseconds

      rig
        .listenerOf(
          generation = STALE,
        ).onPlayWhenReadyChanged(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
      rig.runPosted()

      rig.pins.shouldBeEmpty()
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

  // media3 can report ready with its clock already past the wrap, after running it while it reported buffering.
  @Test
  fun `a state reported past where the loop range wraps wraps before it is published`() =
    runTest {
      val rig = Rig(this)
      val range = TimeRange.of(outputSlotTime(LOOP_FIRST_SLOT, RATE), outputSlotTime(LOOP_END_SLOT, RATE))
      rig.loops.setLoopRange(range)
      rig.reading = outputSlotTime(LOOP_END_SLOT, RATE).inWholeMilliseconds + 7

      rig.loads.handOver(rig.loads.issue())
      rig.listener.onPlaybackStateChanged(Player.STATE_READY)
      runCurrent()

      rig.wraps shouldBe listOf(outputSlotTime(LOOP_FIRST_SLOT, RATE))
    }

  // An end inside the loop range is read as a position past the wrap and then heard as an end, and laps once.
  @Test
  fun `an end inside the loop range wraps once and is not reported as an ending`() =
    runTest {
      val rig = Rig(this)
      rig.loops.setLoopRange(TimeRange.of(outputSlotTime(LOOP_FIRST_SLOT, RATE), LENGTH))
      rig.reading = LENGTH.inWholeMilliseconds

      rig.loads.handOver(rig.loads.issue())
      rig.playbackState = Player.STATE_ENDED
      rig.listener.onPlaybackStateChanged(Player.STATE_ENDED)
      runCurrent()

      rig.wraps shouldBe listOf(outputSlotTime(LOOP_FIRST_SLOT, RATE))
      rig.events.filterIsInstance<PlaybackEvent.Ended>().shouldBeEmpty()
    }

  // The audio sequence can run the clock while the video sequence still buffers, so the mirror reads from the moment
  // the transport is asked to run.
  @Test
  fun `a player asked to play while it buffers mirrors the playhead`() =
    runTest {
      val rig = Rig(this)
      rig.playbackState = Player.STATE_BUFFERING

      rig.listener.onPlayWhenReadyChanged(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
      rig.loads.handOver(rig.loads.issue())
      rig.runPosted()
      runCurrent()

      rig.recorded shouldBe listOf(READING_MS)
    }

  @Test
  fun `a player that buffers with the transport stopped reads nothing`() =
    runTest {
      val rig = Rig(this)
      rig.playWhenReady = false

      rig.listener.onPlaybackStateChanged(Player.STATE_BUFFERING)
      rig.loads.handOver(rig.loads.issue())
      rig.runPosted()
      runCurrent()

      rig.recorded.shouldBeEmpty()
    }

  @Test
  fun `a player that ends while asked to play stops the mirror`() =
    runTest {
      val rig = Rig(this)
      rig.listener.onPlaybackStateChanged(Player.STATE_READY)
      rig.runPosted()
      rig.delayed.size shouldBe 1

      rig.playbackState = Player.STATE_ENDED
      rig.listener.onPlaybackStateChanged(Player.STATE_ENDED)
      rig.runDelayed()

      rig.delayed.shouldBeEmpty()
    }

  private fun position(positionMs: Long): Player.PositionInfo =
    Player.PositionInfo(null, 0, null, null, 0, positionMs, positionMs, C.INDEX_UNSET, C.INDEX_UNSET)

  /**
   * One listener and one mirror over a player reading [READING_MS], whose thread only runs what [runPosted] lets it.
   */
  private class Rig(
    private val scope: TestScope,
  ) {
    val statuses = mutableListOf<PlaybackStatus>()
    val events = mutableListOf<PlaybackEvent>()
    val external = mutableListOf<Boolean>()
    val recorded = mutableListOf<Long>()
    val wraps = mutableListOf<Duration>()
    var reading = READING_MS
    val pins = mutableListOf<Duration?>()
    var playWhenReady = true
    var playbackState = Player.STATE_READY
    var drawn: Duration? = null
    val loads = Media3GraphLoads(scope)

    private val posted = ArrayDeque<Runnable>()
    val delayed = mutableListOf<Runnable>()
    private val handler =
      mockk<Handler>().also {
        every { it.post(any()) } answers {
          posted += firstArg<Runnable>()
          true
        }
        every { it.postDelayed(any(), any()) } answers {
          delayed += firstArg<Runnable>()
          true
        }
      }
    private val player =
      mockk<Player>().also {
        every { it.currentPosition } answers { reading }
        every { it.duration } returns C.TIME_UNSET
        every { it.playWhenReady } answers { playWhenReady }
        every { it.playbackState } answers { playbackState }
        every { it.isPlaying } returns true
        every { it.seekTo(any<Long>()) } returns Unit
      }
    private val loaded =
      mockk<ResolvedComposition>().also {
        every { it.duration } returns LENGTH
        every { it.frameRate } returns RATE
      }
    private val guard = WrapGuard()

    val status: Media3StatusTracker =
      Media3StatusTracker(
        shutter = PreviewShutter(scope, capture = { _, _ -> }),
        hasPreview = { true },
        wrapsAtEnd = { running -> loops.wrapsAtEnd(running) },
        position = { Duration.ZERO },
        duration = { Duration.ZERO },
        stopPlayer = { },
        setStatus = { statuses += it },
        setStalled = { },
        setPlayWhenReady = { },
        reportExternalPlayWhenReady = { external += it },
        emitEvent = { events += it },
      )

    val pin = Media3HeldPin(player = { player }, frameRate = { RATE }, pinSlot = { pins += it }, drawn = { drawn })

    val seeks =
      Media3SeekCoordinator(
        scope = scope,
        handler = handler,
        player = { player },
        playback = { null },
        resolved = { loaded },
        guard = guard,
        platformPositionMs = { status.platformPositionMs },
        loopWrap = { null },
        scrubbing = { false },
        showsFrames = { false },
        onIssued = { },
        onRedirected = { },
        pin = pin,
        onRepresented = { },
      )

    val loops: Media3LoopRangeController =
      Media3LoopRangeController(
        loads = loads,
        handler = handler,
        player = { player },
        resolved = { loaded },
        guard = guard,
        playhead = { Duration.ZERO },
        recordPosition = {
          recorded += it
          status.platformPositionMs = it
        },
        seekTo = { },
        seekToWrap = {
          wraps += it
          guard.seekIssued(wrap = true)
        },
        pause = { },
        onLooped = { },
      )

    val listener = listenerOf(generation = STANDING)

    fun listenerOf(generation: Int): Media3PlayerListener =
      Media3PlayerListener(
        generation = generation,
        standingGeneration = { STANDING },
        player = { player },
        scope = scope,
        loads = loads,
        status = status,
        seeks = seeks,
        loops = loops,
        components = ComponentRegistry.Builder().build(),
      )

    fun runPosted() {
      while (posted.isNotEmpty()) posted.removeFirst().run()
    }

    fun runDelayed() {
      val due = delayed.toList()
      delayed.clear()
      due.forEach { it.run() }
    }
  }

  private companion object {
    const val READING_MS = 640L
    const val LOOP_FIRST_SLOT = 10L
    const val LOOP_END_SLOT = 20L
    const val HELD_MS = 800L
    const val RATE = 30
    const val STANDING = 1
    const val STALE = 0
    val LENGTH = 3.seconds
  }
}

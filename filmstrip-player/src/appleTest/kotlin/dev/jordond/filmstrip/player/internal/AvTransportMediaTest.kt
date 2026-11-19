package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.appleExportLowering
import dev.jordond.filmstrip.player.appleFixtureComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.pumpMainRunLoopDuringContracts
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import platform.AVFoundation.AVMutableComposition
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.rate
import platform.darwin.dispatch_get_main_queue
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * How the Apple transport moves the playhead, reports where playback stopped and takes a loop range's wrap, against a
 * player with no item so nothing but the transport moves it.
 */
@OptIn(ExperimentalForeignApi::class)
class AvTransportMediaTest {
  init {
    pumpMainRunLoopDuringContracts()
  }

  @Test
  fun `a jump moves the playhead and leaves reporting it to the engine`() =
    contractTest { scope ->
      val rig = Rig(scope, appleExportLowering(appleFixtureComposition()))

      rig.transport.jumpTo(400.milliseconds)

      rig.transport.playhead shouldBe 400.milliseconds
      rig.positions.shouldBeEmpty()
      rig.transport.release()
    }

  // The clock runs on for a moment after a pause, so the reading taken at the stop is not where playback stopped.
  @Test
  fun `a stop reports nothing until the clock rests and then where it rests`() =
    contractTest { scope ->
      val rig = Rig(scope, appleExportLowering(appleFixtureComposition()))
      rig.transport.play()
      rig.clock = 400.milliseconds

      rig.transport.stop()
      rig.positions.shouldBeEmpty()
      rig.rest.waiting shouldBe true

      rig.rest.come(400.milliseconds + 190.microseconds)
      rig.positions shouldBe listOf(400.milliseconds + 190.microseconds)
      rig.transport.playhead shouldBe 400.milliseconds + 190.microseconds
      rig.transport.release()
    }

  @Test
  fun `waiting for the stop to report returns once the clock rests`() =
    contractTest { scope ->
      val rig = Rig(scope, appleExportLowering(appleFixtureComposition()))
      rig.transport.awaitStopReported()
      rig.transport.play()
      rig.clock = 400.milliseconds
      rig.transport.stop()

      val reported = scope.async(start = CoroutineStart.UNDISPATCHED) { rig.transport.awaitStopReported() }
      reported.isActive shouldBe true

      rig.rest.come(400.milliseconds)
      reported.await()
      rig.positions shouldBe listOf(400.milliseconds)
      rig.transport.release()
    }

  // The clock reads before the middle of slot 12 when the stop is asked for and rests past it, so the rest decides.
  @Test
  fun `a pause the clock carries past the middle of a slot moves the picture on to the next slot`() =
    contractTest { scope ->
      val resolved = appleExportLowering(appleFixtureComposition())
      val rate = resolved.frameRate
      val rig = Rig(scope, resolved)
      rig.transport.play()
      rig.clock = outputSlotTime(12, rate) + outputSlotTime(1, rate) * 2 / 5

      rig.transport.stop(settle = true)
      rig.rest.come(outputSlotTime(12, rate) + outputSlotTime(1, rate) * 3 / 5)

      rig.settles shouldBe listOf(outputSlotTime(13, rate))
      rig.positions shouldBe listOf(outputSlotTime(13, rate))
      rig.transport.release()
    }

  // Past the middle of slot 12 the clock rounds to slot 13, while the picture is still slot 12's frame.
  @Test
  fun `a pause past the middle of a slot reports the next slot and moves the picture on to it`() =
    contractTest { scope ->
      val resolved = appleExportLowering(appleFixtureComposition())
      val rate = resolved.frameRate
      val rig = Rig(scope, resolved)
      rig.transport.play()
      rig.clock = outputSlotTime(12, rate) + outputSlotTime(1, rate) * 3 / 4

      rig.transport.stop(settle = true)
      rig.rest.come(rig.clock)

      rig.settles shouldBe listOf(outputSlotTime(13, rate))
      rig.positions shouldBe listOf(outputSlotTime(13, rate))
      rig.transport.playhead shouldBe outputSlotTime(13, rate)
      rig.transport.release()
    }

  @Test
  fun `a pause before the middle of a slot reports the clock and leaves the picture`() =
    contractTest { scope ->
      val resolved = appleExportLowering(appleFixtureComposition())
      val rate = resolved.frameRate
      val rig = Rig(scope, resolved)
      rig.transport.play()
      val clock = outputSlotTime(12, rate) + outputSlotTime(1, rate) / 4
      rig.clock = clock

      rig.transport.stop(settle = true)
      rig.rest.come(clock)

      rig.settles.shouldBeEmpty()
      rig.positions shouldBe listOf(clock)
      rig.transport.release()
    }

  // An end, a pause made on the native player, and a slot the engine will not move to all keep the clock's reading.
  @Test
  fun `a stop that may not settle or a settle refused reports the clock`() =
    contractTest { scope ->
      val resolved = appleExportLowering(appleFixtureComposition())
      val rate = resolved.frameRate
      val rig = Rig(scope, resolved)
      val clock = outputSlotTime(12, rate) + outputSlotTime(1, rate) * 3 / 4
      rig.clock = clock
      rig.transport.play()
      rig.transport.stop()
      rig.rest.come(clock)

      rig.settleAllowed = false
      rig.transport.play()
      rig.transport.stop(settle = true)
      rig.rest.come(clock)

      rig.settles shouldBe listOf(outputSlotTime(13, rate))
      rig.positions shouldBe listOf(clock, clock)
      rig.transport.release()
    }

  @Test
  fun `a stop still waiting on the clock is dropped once a jump or a play makes it stale`() =
    contractTest { scope ->
      val rig = Rig(scope, appleExportLowering(appleFixtureComposition()))
      rig.transport.play()
      rig.clock = 400.milliseconds
      rig.transport.stop()

      rig.transport.jumpTo(100.milliseconds)
      rig.rest.waiting shouldBe false
      rig.transport.play()
      rig.transport.stop()
      rig.rest.waiting shouldBe true
      rig.transport.play()
      rig.rest.waiting shouldBe false
      rig.transport.awaitStopReported()

      rig.positions.shouldBeEmpty()
      rig.transport.release()
    }

  @Test
  fun `a stop with the transport already stopped reports nothing`() =
    contractTest { scope ->
      val rig = Rig(scope, appleExportLowering(appleFixtureComposition()))
      rig.transport.play()
      rig.transport.halt()

      rig.transport.stop()

      rig.rest.waiting shouldBe false
      rig.positions.shouldBeEmpty()
      rig.transport.release()
    }

  @Test
  fun `a reading inside the loop range becomes the playhead`() =
    contractTest { scope ->
      val rig = Rig(scope, appleExportLowering(appleFixtureComposition()))
      rig.loop.set(RANGE)
      rig.transport.play()

      rig.transport.onPlayhead(200.milliseconds)

      rig.transport.playhead shouldBe 200.milliseconds
      rig.wraps.shouldBeEmpty()
      rig.events.shouldBeEmpty()
      rig.transport.release()
    }

  @Test
  fun `a reading at the wrap takes it back to the range's first slot and reports the lap`() =
    contractTest { scope ->
      val resolved = appleExportLowering(appleFixtureComposition())
      val rig = Rig(scope, resolved)
      rig.loop.set(RANGE)
      val wrap = rig.loop.wrap().shouldNotBeNull()
      rig.transport.play()

      rig.transport.onPlayhead(wrap.at)

      val landing = outputSlotTime(wrap.firstSlot, resolved.frameRate)
      rig.wraps shouldBe listOf(landing)
      rig.events shouldBe listOf(PlaybackEvent.RangeLooped(RANGE))
      // The engine reports the landing as it takes the wrap, so the transport reports nothing of its own.
      rig.positions.shouldBeEmpty()
      rig.transport.release()
    }

  // AVFoundation's limit: the item's clock reads back through a Double of seconds, so a reading on the wrap can come
  // back a hair short of it.
  @Test
  fun `a reading a microsecond short of the wrap still takes it`() =
    contractTest { scope ->
      val rig = Rig(scope, appleExportLowering(appleFixtureComposition()))
      rig.loop.set(RANGE)
      val wrap = rig.loop.wrap().shouldNotBeNull()
      rig.transport.play()

      rig.transport.onPlayhead(wrap.at - 1.microseconds)
      rig.wraps.size shouldBe 1

      rig.transport.onPlayhead(wrap.at - 2.microseconds)
      rig.wraps.size shouldBe 1
      rig.transport.release()
    }

  @Test
  fun `a paused transport or an item that cannot seek never wraps`() =
    contractTest { scope ->
      val rig = Rig(scope, appleExportLowering(appleFixtureComposition()))
      rig.loop.set(RANGE)
      val wrap = rig.loop.wrap().shouldNotBeNull()

      rig.transport.onPlayhead(wrap.at)
      rig.transport.playhead shouldBe wrap.at

      rig.seekReady = false
      rig.transport.play()
      rig.transport.onPlayhead(wrap.at)

      rig.wraps.shouldBeEmpty()
      rig.transport.release()
    }

  @Test
  fun `the item's end is a wrap only while a loop range has something to loop`() =
    contractTest { scope ->
      val rig = Rig(scope, appleExportLowering(appleFixtureComposition()))
      rig.transport.wrapsAtEnd() shouldBe false

      rig.loop.set(TimeRange.from(RANGE.start))
      rig.transport.wrapsAtEnd() shouldBe true
      rig.player.rate shouldBe 0f
      rig.transport.release()
    }

  @Test
  fun `playback the engine did not start reports the playhead the engine holds`() =
    contractTest { scope ->
      val rig = Rig(scope, appleExportLowering(appleFixtureComposition()))
      rig.clock = 400.milliseconds
      rig.reported = 100.milliseconds
      rig.player.play()

      rig.transport.follow(AVPlayerItem(asset = AVMutableComposition()))
      awaitContract("the wrap tick to read") { rig.positions.isNotEmpty() }

      rig.transport.playhead shouldBe 400.milliseconds
      rig.positions shouldBe listOf(100.milliseconds)
      rig.player.pause()
      rig.transport.release()
    }

  @Test
  fun `an end the engine's own playback reached plays the player on`() =
    contractTest { scope ->
      val rig = Rig(scope, appleExportLowering(appleFixtureComposition()))
      rig.loop.set(TimeRange.from(RANGE.start))
      rig.transport.play()
      rig.player.pause()

      rig.transport.wrapsAtEnd() shouldBe true

      rig.player.rate shouldBe 1f
      rig.player.pause()
      rig.transport.release()
    }

  /**
   * A transport over an itemless player, with the clock and the engine side in the test's hands.
   */
  private class Rig(
    scope: CoroutineScope,
    resolved: ResolvedComposition,
  ) {
    var clock: Duration = Duration.ZERO
    var seekReady = true
    val wraps = mutableListOf<Duration>()
    val events = mutableListOf<PlaybackEvent>()
    val positions = mutableListOf<Duration>()
    var reported: Duration = Duration.ZERO
    val settles = mutableListOf<Duration>()
    var settleAllowed = true
    val rest = HeldRest()
    val player = AVPlayer()
    private val guard = WrapGuard()

    private val seeks =
      AvSeekCoordinator(
        scope = scope,
        item = { null },
        resolved = { resolved },
        guard = guard,
        loopWrap = { null },
        playhead = { Duration.ZERO },
        onIssued = { },
        onLanded = { },
      )

    val loop =
      AvLoopRangeController(
        scope = scope,
        player = player,
        callbackQueue = dispatch_get_main_queue(),
        resolved = { resolved },
        guard = guard,
        onBoundary = { },
      )

    val transport =
      AvTransport(
        scope = scope,
        player = player,
        interval = TICK,
        resolved = { resolved },
        seeks = seeks,
        loop = loop,
        guard = guard,
        itemTime = { clock },
        itemEnd = { resolved.duration },
        isSeekReady = { seekReady },
        onSeekReady = { },
        seekToWrap = { wraps += it },
        emitEvent = { events += it },
        emitPosition = { positions += it },
        reportedPlayhead = { reported },
        settle = { slot ->
          settles += slot
          settleAllowed
        },
        rest = rest,
      )
  }

  /**
   * A clock that comes to rest when the test says so.
   */
  private class HeldRest : ClockRest {
    private var onRest: ((Duration) -> Unit)? = null

    val waiting: Boolean get() = onRest != null

    override fun await(onRest: (Duration) -> Unit) {
      this.onRest = onRest
    }

    override fun cancel() {
      onRest = null
    }

    fun come(at: Duration) {
      val rest = onRest ?: return
      onRest = null
      rest(at)
    }
  }

  private companion object {
    val RANGE = TimeRange.of(100.milliseconds, 300.milliseconds)

    // Long enough that the wrap tick reads once on play and never again inside a test.
    val TICK = 10.minutes
  }
}

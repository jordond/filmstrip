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

  @Test
  fun `a stop reports where the item stopped and later readings only move it forward`() =
    contractTest { scope ->
      val rig = Rig(scope, appleExportLowering(appleFixtureComposition()))
      rig.transport.play()
      rig.clock = 400.milliseconds

      rig.transport.stop()
      rig.transport.onStopReading(420.milliseconds)
      rig.transport.onStopReading(410.milliseconds)

      rig.positions shouldBe listOf(400.milliseconds, 420.milliseconds)
      rig.transport.playhead shouldBe 420.milliseconds
      rig.transport.release()
    }

  @Test
  fun `a stop reading is dropped once a jump or a play makes it stale`() =
    contractTest { scope ->
      val rig = Rig(scope, appleExportLowering(appleFixtureComposition()))
      rig.transport.play()
      rig.clock = 400.milliseconds
      rig.transport.stop()

      rig.transport.jumpTo(100.milliseconds)
      rig.transport.onStopReading(450.milliseconds)
      rig.transport.play()
      rig.transport.onStopReading(500.milliseconds)

      rig.positions shouldBe listOf(400.milliseconds)
      rig.transport.release()
    }

  @Test
  fun `a stop with the transport already stopped reports nothing`() =
    contractTest { scope ->
      val rig = Rig(scope, appleExportLowering(appleFixtureComposition()))
      rig.transport.play()
      rig.transport.halt()

      rig.transport.stop()
      rig.transport.onStopReading(500.milliseconds)

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
        callbackQueue = dispatch_get_main_queue(),
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
      )
  }

  private companion object {
    val RANGE = TimeRange.of(100.milliseconds, 300.milliseconds)

    // Long enough that the wrap tick reads once on play and never again inside a test.
    val TICK = 10.minutes
  }
}

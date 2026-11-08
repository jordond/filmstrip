package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.player.appleExportLowering
import dev.jordond.filmstrip.player.appleFixtureComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.pumpMainRunLoopDuringContracts
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.currentTime
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.darwin.dispatch_get_main_queue
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalForeignApi::class)
class AvLoopRangeControllerMediaTest {
  init {
    pumpMainRunLoopDuringContracts()
  }

  @Test
  fun `no wrap stands without a range or without a composition`() =
    contractTest { scope ->
      val resolved = appleExportLowering(appleFixtureComposition())
      val bare = Rig(scope, AVPlayer(), resolved)
      bare.controller.wrap().shouldBeNull()

      val unloaded = Rig(scope, AVPlayer(), resolved = null)
      unloaded.controller.set(RANGE)
      unloaded.controller.wrap().shouldBeNull()
      unloaded.controller.dispose()
    }

  @Test
  fun `the wrap is where the range wraps in the composition loaded now`() =
    contractTest { scope ->
      val resolved = appleExportLowering(appleFixtureComposition())
      val rig = Rig(scope, AVPlayer(), resolved)

      rig.controller.set(RANGE)

      val wrap = rig.controller.wrap().shouldNotBeNull()
      val expected = loopWrapOf(RANGE, resolved.duration, resolved.frameRate).shouldNotBeNull()
      wrap.at shouldBe expected.at
      wrap.firstSlot shouldBe expected.firstSlot
      wrap.lastSlot shouldBe expected.lastSlot
      wrap.reachesEnd shouldBe false
      rig.controller.range shouldBe RANGE
      rig.controller.dispose()
    }

  @Test
  fun `a range that moves is put to the guard and the range already standing is not`() =
    contractTest { scope ->
      val resolved = appleExportLowering(appleFixtureComposition())
      val rig = Rig(scope, AVPlayer(), resolved)

      rig.guard.seekIssued(wrap = true)
      rig.controller.set(RANGE)
      rig.guard.seekLanded(rig.controller.wrap()) shouldBe rig.controller.wrap()?.firstSlot

      rig.guard.seekIssued(wrap = true)
      rig.controller.set(RANGE)
      rig.guard.seekLanded(rig.controller.wrap()).shouldBeNull()
      rig.controller.dispose()
    }

  @Test
  fun `playback crossing the wrap calls back on the engine's dispatcher`() =
    contractTest { scope ->
      val fixture = loadedFixture()
      val rig = Rig(scope, fixture.player, fixture.resolved)

      rig.controller.set(RANGE)
      fixture.player.play()
      awaitContract("playback to cross the wrap") { rig.boundaries > 0 }
      fixture.player.pause()
      rig.controller.dispose()
    }

  @Test
  fun `a disposed boundary is never crossed`() =
    contractTest { scope ->
      val fixture = loadedFixture()
      val rig = Rig(scope, fixture.player, fixture.resolved)
      val wrap = loopWrapOf(RANGE, fixture.resolved.duration, fixture.resolved.frameRate).shouldNotBeNull()

      rig.controller.set(RANGE)
      rig.controller.dispose()
      rig.controller.dispose()
      fixture.player.play()
      awaitContract("playback to pass the wrap") {
        fixture.player.currentTime().toDuration() > wrap.at + PAST_THE_WRAP
      }
      fixture.player.pause()

      rig.boundaries shouldBe 0
    }

  private class Rig(
    scope: CoroutineScope,
    player: AVPlayer,
    resolved: ResolvedComposition?,
  ) {
    val guard = WrapGuard()
    var boundaries = 0

    val controller =
      AvLoopRangeController(
        scope = scope,
        player = player,
        callbackQueue = dispatch_get_main_queue(),
        resolved = { resolved },
        guard = guard,
        onBoundary = { boundaries++ },
      )
  }

  private companion object {
    // Inside the fixture, so the composition runs on past it and the boundary is what wraps it.
    val RANGE = TimeRange.of(100.milliseconds, 300.milliseconds)

    // Long enough past the wrap that a boundary still armed would have fired.
    val PAST_THE_WRAP = 200.milliseconds
  }
}

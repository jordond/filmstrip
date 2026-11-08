package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.player.MID_GOP_POSITION
import dev.jordond.filmstrip.player.PROBE_POSITIONS
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.awaitStep
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.pumpMainRunLoopDuringContracts
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import platform.AVFoundation.AVPlayerItem
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * How the Apple engine hands seeks to an item and answers them, against a real item rather than the engine around it.
 */
class AvSeekCoordinatorMediaTest {
  init {
    pumpMainRunLoopDuringContracts()
  }

  @Test
  fun `a seek with nothing loaded lands where it was asked straight away`() =
    contractTest { scope ->
      val rig = Rig(scope, item = null, resolved = null)
      val answers = mutableListOf<Duration>()

      rig.seeks.seek(MID_GOP_POSITION, SeekAccuracy.Exact, wrap = false) { answers += it }

      answers shouldBe listOf(MID_GOP_POSITION)
      rig.landings shouldBe listOf(MID_GOP_POSITION)
      rig.issued shouldBe 0
      rig.seeks.inFlight shouldBe false
      rig.seeks.countedSeeks shouldBe 0
    }

  @Test
  fun `an exact seek is counted until it lands on its slot`() =
    contractTest { scope ->
      val fixture = loadedFixture()
      val rig = Rig(scope, fixture.item, fixture.resolved)
      val answers = mutableListOf<Duration>()

      rig.seeks.seek(MID_GOP_POSITION, SeekAccuracy.Exact, wrap = false) { answers += it }
      rig.issued shouldBe 1
      rig.seeks.inFlight shouldBe true
      rig.seeks.unansweredSeeks shouldBe 1

      awaitStep("the seek to be answered") { rig.seeks.awaitAnswered() }
      val landing = fixture.resolved.seekLandingOf(MID_GOP_POSITION)
      answers shouldBe listOf(landing)
      rig.landings shouldBe listOf(landing)
      rig.seeks.inFlight shouldBe false
      rig.seeks.countedSeeks shouldBe 1
    }

  @Test
  fun `a seek a newer one replaced answers with the playhead and leaves the landing to the newer one`() =
    contractTest { scope ->
      val fixture = loadedFixture()
      val rig = Rig(scope, fixture.item, fixture.resolved)
      val first = mutableListOf<Duration>()
      val second = mutableListOf<Duration>()

      rig.seeks.seek(PROBE_POSITIONS.first(), SeekAccuracy.Exact, wrap = false) { first += it }
      rig.seeks.seek(PROBE_POSITIONS.last(), SeekAccuracy.Exact, wrap = false) { second += it }
      awaitStep("both seeks to be answered") { rig.seeks.awaitAnswered() }

      val landing = fixture.resolved.seekLandingOf(PROBE_POSITIONS.last())
      first shouldBe listOf(PLAYHEAD)
      second shouldBe listOf(landing)
      rig.landings shouldBe listOf(landing)
      rig.seeks.countedSeeks shouldBe 2
    }

  @Test
  fun `a wrap landing after the loop range moved is sent on to the new range's first slot`() =
    contractTest { scope ->
      val fixture = loadedFixture()
      val moved = TimeRange.of(600.milliseconds, 1_200.milliseconds)
      val rig = Rig(scope, fixture.item, fixture.resolved)
      val answers = mutableListOf<Duration>()

      rig.seeks.seek(PROBE_POSITIONS.first(), SeekAccuracy.Exact, wrap = true) { answers += it }
      rig.wrap = loopWrapOf(moved, fixture.resolved.duration, fixture.resolved.frameRate)
      rig.guard.rangeChanged()
      awaitStep("the redirected seek to be answered") { rig.seeks.awaitAnswered() }

      val firstSlot = rig.wrap?.firstSlot ?: error("$moved has nothing to loop")
      val landing = outputSlotTime(firstSlot, fixture.resolved.frameRate)
      answers shouldBe listOf(landing)
      rig.landings shouldBe listOf(landing)
      rig.seeks.countedSeeks shouldBe 1
    }

  @Test
  fun `abandoning leaves a loaded item's seek counted until it answers`() =
    contractTest { scope ->
      val fixture = loadedFixture()
      val rig = Rig(scope, fixture.item, fixture.resolved)

      rig.seeks.seek(MID_GOP_POSITION, SeekAccuracy.Exact, wrap = false) { }
      rig.seeks.abandon()

      rig.seeks.inFlight shouldBe true
      rig.seeks.unansweredSeeks shouldBe 1
      awaitStep("the seek to be answered") { rig.seeks.awaitAnswered() }
    }

  @Test
  fun `abandoning once the item is gone forgets its seek and its late answer moves nothing`() =
    contractTest { scope ->
      val fixture = loadedFixture()
      val rig = Rig(scope, fixture.item, fixture.resolved)
      val answers = mutableListOf<Duration>()

      rig.seeks.seek(MID_GOP_POSITION, SeekAccuracy.Exact, wrap = false) { answers += it }
      rig.item = null
      rig.seeks.abandon()

      rig.seeks.inFlight shouldBe false
      rig.seeks.unansweredSeeks shouldBe 0
      awaitContract("the item to answer the forgotten seek") { answers.isNotEmpty() }
      answers shouldBe listOf(PLAYHEAD)
      rig.landings.shouldBeEmpty()
      rig.seeks.unansweredSeeks shouldBe 0
    }

  /**
   * A coordinator over [item] whose engine side is recorded.
   */
  private class Rig(
    scope: CoroutineScope,
    var item: AVPlayerItem?,
    resolved: ResolvedComposition?,
  ) {
    val guard = WrapGuard()
    var wrap: LoopWrap? = null
    var issued = 0
    val landings = mutableListOf<Duration>()

    val seeks =
      AvSeekCoordinator(
        scope = scope,
        item = { item },
        resolved = { resolved },
        guard = guard,
        loopWrap = { wrap },
        playhead = { PLAYHEAD },
        onIssued = { issued++ },
        onLanded = { landings += it },
      )
  }

  private companion object {
    // Where a superseded seek says playback is, away from every position a test seeks to.
    val PLAYHEAD = 100.milliseconds
  }
}

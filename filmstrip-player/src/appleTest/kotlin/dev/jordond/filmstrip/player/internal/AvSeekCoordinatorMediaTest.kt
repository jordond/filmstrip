package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.MID_GOP_POSITION
import dev.jordond.filmstrip.player.PROBE_POSITIONS
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.appleFixture
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.awaitStep
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.pumpMainRunLoopDuringContracts
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemOutput
import platform.AVFoundation.addOutput
import platform.AVFoundation.currentTime
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How the Apple engine hands seeks to an item and answers them, against a real item rather than the engine around it.
 */
@OptIn(ExperimentalForeignApi::class)
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

  @Test
  fun `a frame presented with a tolerance answers with the slot the item settled on inside it`() =
    contractTest { scope ->
      val fixture = loadedFixture(longComposition())
      fixture.item.addOutput(bgraVideoOutput() as AVPlayerItemOutput)
      val rig = Rig(scope, fixture.item, fixture.resolved)
      val behind = SeekTolerance(before = SHUTTLE_SEEK_TOLERANCE, after = Duration.ZERO)
      val ahead = SeekTolerance(before = Duration.ZERO, after = SHUTTLE_SEEK_TOLERANCE)

      for ((at, window, snaps) in SPARSE_SYNC_FRAMES) {
        val tolerance = if (window == Side.Behind) behind else ahead
        val answers = mutableListOf<Duration>()
        rig.seeks.present(at, tolerance) { answers += it }
        awaitStep("the frame at $at to be answered") { rig.seeks.awaitAnswered() }

        val settled = fixture.item.currentTime().toDuration()
        val asked = fixture.resolved.seekLandingOf(at)
        withClue("the frame asked for at $at within $tolerance, settled at $settled") {
          answers shouldBe listOf(fixture.resolved.seekLandingOf(settled))
          rig.landings.last() shouldBe answers.single()
          settled shouldBeGreaterThanOrEqualTo at - tolerance.before - TIMESCALE_SLACK
          settled shouldBeLessThanOrEqualTo at + tolerance.after + TIMESCALE_SLACK
          when {
            !snaps -> answers.single() shouldBe asked
            window == Side.Behind -> answers.single() shouldBeLessThan asked
            else -> answers.single() shouldBeGreaterThan asked
          }
        }
      }
      rig.seeks.inFlight shouldBe false
    }

  @Test
  fun `a frame presented with nothing loaded lands where it was asked straight away`() =
    contractTest { scope ->
      val rig = Rig(scope, item = null, resolved = null)
      val answers = mutableListOf<Duration>()

      rig.seeks.present(MID_GOP_POSITION, SeekTolerance(SHUTTLE_SEEK_TOLERANCE, Duration.ZERO)) { answers += it }

      answers shouldBe listOf(MID_GOP_POSITION)
      rig.issued shouldBe 0
    }

  private fun longComposition(): Composition =
    Composition(
      tracks =
        listOf(
          Track(
            listOf(Clip(MediaSource.of(appleFixture("apple_export_long.mp4")), TimeRange.of(Duration.ZERO, 5.seconds))),
          ),
        ),
    )

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

    // Measured on the long fixture, whose sync samples sit 29 frames apart. Each position has one within the
    // tolerance on one side and none on the other.
    val SPARSE_SYNC_FRAMES: List<Triple<Duration, Side, Boolean>> =
      listOf(
        Triple(4_011.milliseconds, Side.Behind, true),
        Triple(4_011.milliseconds, Side.Ahead, false),
        Triple(1_111.milliseconds, Side.Behind, true),
        Triple(1_111.milliseconds, Side.Ahead, false),
        Triple(3_811.milliseconds, Side.Ahead, true),
        Triple(3_811.milliseconds, Side.Behind, false),
      )

    // AVFoundation rounds the time asked for to the item's timescale before it settles on it.
    val TIMESCALE_SLACK = 1.milliseconds
  }

  private enum class Side { Behind, Ahead }
}

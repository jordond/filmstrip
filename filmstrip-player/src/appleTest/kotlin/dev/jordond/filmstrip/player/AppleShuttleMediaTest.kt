package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.contract.ContractRecorder
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.awaitStep
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.AvPlayerEngine
import dev.jordond.filmstrip.player.internal.AvPreviewPlanner
import dev.jordond.filmstrip.player.internal.bgraVideoOutput
import dev.jordond.filmstrip.player.internal.seekLandingOf
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeMonotonicallyIncreasing
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import platform.AVFoundation.AVPlayerItemOutput
import platform.AVFoundation.addOutput
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.muted
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What a shuttle does to the AVFoundation player underneath the engine.
 *
 * The long fixture has sync samples far enough apart that a frame asked for with a tolerance settles off the time it
 * was asked for, so the playhead has to come from the item and not from the request.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleShuttleMediaTest {
  init {
    pumpMainRunLoopDuringContracts()
  }

  @Test
  fun `a shuttle mutes the player and ending it brings the sound back`() =
    shuttleTest(muted = false) { engine, _ ->
      engine.platformPlayer.muted shouldBe false

      engine.beginShuttle(FAST)
      engine.platformPlayer.muted shouldBe true
      engine.beginShuttle(-FAST)
      engine.platformPlayer.muted shouldBe true

      engine.endShuttle()
      engine.platformPlayer.muted shouldBe false
    }

  @Test
  fun `a shuttle on a muted player leaves it muted when it ends`() =
    shuttleTest(muted = true) { engine, _ ->
      engine.beginShuttle(FAST)
      engine.endShuttle()

      engine.platformPlayer.muted shouldBe true
    }

  @Test
  fun `a shuttle reports the slot of the frame the item settled on`() =
    shuttleTest(muted = true) { engine, recorder ->
      val resolved = appleExportLowering(LONG_EDIT)
      val item = engine.platformPlayer.currentItem ?: fail("the player holds no item")
      item.addOutput(bgraVideoOutput() as AVPlayerItemOutput)

      for (rate in listOf(FAST, -FAST)) {
        val from = recorder.playheads.size
        engine.beginShuttle(rate)
        awaitContract("the shuttle at $rate to show $FRAMES frames") {
          recorder.playheads
            .drop(from)
            .distinct()
            .size >= FRAMES
        }
        engine.endShuttle()
        awaitStep("the engine to go quiet") { engine.fence.awaitDrained() }

        val shown = recorder.playheads.drop(from)
        withClue("frames $shown at a rate of $rate") {
          (if (rate > 0f) shown else shown.reversed()).shouldBeMonotonicallyIncreasing()
          recorder.playhead shouldBe resolved.seekLandingOf(item.currentTime().toDuration())
        }
      }
    }

  private fun shuttleTest(
    muted: Boolean,
    body: suspend (engine: AvPlayerEngine, recorder: ContractRecorder) -> Unit,
  ) = contractTest(timeout = TIMEOUT) { scope ->
    val engine = engineOf(scope, muted)
    withEngine(engine) { recorder ->
      engine.awaitComposition(LONG_EDIT).shouldBeInstanceOf<SetCompositionResult.Success>()
      awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }
      val before = recorder.seekCompletions.size
      engine.seekTo(START, SeekAccuracy.Exact)
      awaitContract("the seek to $START to complete") { recorder.seekCompletions.size > before }
      body(engine, recorder)
    }
  }

  private fun engineOf(
    scope: CoroutineScope,
    muted: Boolean,
  ): AvPlayerEngine =
    AvPlayerEngine(
      parent = scope,
      planner = AvPreviewPlanner(CONTRACT_COMPONENTS),
      config = PlayerConfig(),
      muted = muted,
    )

  private companion object {
    val TIMEOUT: Duration = 3.minutes
    val LONG_LENGTH: Duration = 5.seconds
    val LONG_EDIT: Composition =
      Composition(
        tracks =
          listOf(
            Track(
              listOf(
                Clip(MediaSource.of(appleFixture("apple_export_long.mp4")), TimeRange.of(Duration.ZERO, LONG_LENGTH)),
              ),
            ),
          ),
      )

    // Far enough from both ends that a shuttle each way shows its frames before it reaches one.
    val START: Duration = 2.seconds
    const val FAST = 3f
    const val FRAMES = 4
  }
}

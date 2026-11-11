package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.PreviewClock
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.filters.FilterEffectResolver
import dev.jordond.filmstrip.effects.filters.vhs.TapeClock
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.contract.ContractRecorder
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.settle
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.AvPlayerEngine
import dev.jordond.filmstrip.player.internal.AvPreviewPlanner
import dev.jordond.filmstrip.player.internal.bgraVideoOutput
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVPlayerItemOutput
import platform.AVFoundation.addOutput
import platform.AVFoundation.currentItem
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * What a paused AVFoundation preview draws under a `Vhs`, counted by how often the tape reads the preview clock, which
 * it does once for every frame the chain draws.
 *
 * A hardware test, since the tape is a Core Image Metal kernel a simulator on CI cannot compile.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
class AppleHeldRedrawHardwareTest {
  init {
    pumpMainRunLoopDuringContracts()
  }

  @Test
  fun `a paused wall tape is drawn again and again with the playhead and the state standing still`() =
    heldTest(Vhs(clock = TapeClock.Wall)) { _, recorder, draws ->
      val before = draws.load()
      val state = recorder.lastState
      val events = recorder.events.size
      val playhead = recorder.playhead

      awaitContract("the held frame to be drawn $REDRAWS more times") { draws.load() - before >= REDRAWS }

      recorder.lastState shouldBe state
      recorder.playhead shouldBe playhead
      withClue("held redraws raised ${recorder.events.drop(events)}") { recorder.events.size shouldBe events }
    }

  @Test
  fun `a paused composition tape is never asked for again`() =
    heldTest(Vhs()) { _, _, draws ->
      val before = draws.load()

      settle()

      draws.load() shouldBe before
    }

  private fun heldTest(
    vhs: Vhs,
    body: suspend (engine: AvPlayerEngine, recorder: ContractRecorder, draws: AtomicInt) -> Unit,
  ) = contractTest(timeout = TIMEOUT) { scope ->
    val draws = AtomicInt(0)
    val started = TimeSource.Monotonic.markNow()
    val clock =
      PreviewClock {
        draws.incrementAndFetch()
        started.elapsedNow()
      }
    val components =
      ComponentRegistry
        .Builder()
        .addEffectResolver(BuiltInEffectResolver())
        .addEffectResolver(FilterEffectResolver())
        .build()
    val engine = AvPlayerEngine(scope, AvPreviewPlanner(components, clock), PlayerConfig(), muted = true)
    withEngine(engine) { recorder ->
      engine.awaitComposition(edit(vhs)).shouldBeInstanceOf<SetCompositionResult.Success>()
      awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }
      // A headless player decodes nothing, so the item is given an output to draw for.
      val item = engine.platformPlayer.currentItem ?: fail("the player holds no item")
      item.addOutput(bgraVideoOutput() as AVPlayerItemOutput)
      val completed = recorder.seekCompletions.size
      engine.seekTo(HELD_AT, SeekAccuracy.Exact)
      awaitContract("the seek to $HELD_AT to complete") { recorder.seekCompletions.size > completed }
      // The seek completes as the frame is asked for, a moment before the chain draws it.
      if (vhs.clock == TapeClock.Wall) awaitContract("the seek's frame to be drawn") { draws.load() >= 1 }
      body(engine, recorder, draws)
    }
  }

  private fun edit(vhs: Vhs): Composition =
    Composition(
      tracks =
        listOf(
          Track(
            listOf(Clip(MediaSource.of(appleFixture("apple_export_long.mp4")), TimeRange.of(Duration.ZERO, LENGTH))),
          ),
        ),
      effects = listOf(vhs),
    )

  private companion object {
    val TIMEOUT: Duration = 3.minutes
    val LENGTH: Duration = 5.seconds
    val HELD_AT: Duration = 2.seconds
    const val REDRAWS = 10
  }
}

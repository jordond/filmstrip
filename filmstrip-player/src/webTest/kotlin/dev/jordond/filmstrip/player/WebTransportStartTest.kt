package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.player.contract.ContractRecorder
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.awaitStep
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.BrowserPlayerEngine
import dev.jordond.filmstrip.player.internal.BrowserPreviewPlanner
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * What the browser engine leaves behind of a transport start something superseded while it was still starting the
 * audio graph.
 *
 * Every start is held where it starts the audio graph, so the superseding call always lands mid-start. A start that
 * goes on past that point once released is one that survived being superseded.
 */
class WebTransportStartTest {
  @Test
  fun `a newer play cancels the start still in flight`() =
    heldStart { engine, gate ->
      engine.play()
      awaitContract("the first start to reach the audio graph") { gate.entered == 1 }
      engine.pause()
      engine.play()
      awaitContract("the second start to reach the audio graph") { gate.entered == 2 }
      gate.releaseAll()
      engine.awaitQuietDone()

      gate.resumed shouldBe listOf(1)
      engine.clockRunning shouldBe true
    }

  @Test
  fun `a pause cancels the start in flight`() =
    heldStart { engine, gate ->
      engine.play()
      awaitContract("the start to reach the audio graph") { gate.entered == 1 }
      engine.pause()
      gate.releaseAll()
      engine.awaitQuietDone()

      gate.resumed.shouldBeEmpty()
      engine.clockRunning shouldBe false
    }

  @Test
  fun `a stop cancels the start in flight`() =
    heldStart { engine, gate ->
      engine.play()
      awaitContract("the start to reach the audio graph") { gate.entered == 1 }
      engine.onPageHidden()
      gate.releaseAll()
      engine.awaitQuietDone()

      gate.resumed.shouldBeEmpty()
      engine.clockRunning shouldBe false
    }

  // The landing starts the transport again from where it landed, and only that start runs on.
  @Test
  fun `a seek landing cancels the start in flight`() =
    heldStart { engine, gate ->
      engine.play()
      awaitContract("the start to reach the audio graph") { gate.entered == 1 }
      engine.seekTo(SEEK_TARGET, SeekAccuracy.Exact)
      awaitContract("the landing's start to reach the audio graph") { gate.entered == 2 }
      gate.releaseAll()
      engine.awaitQuietDone()

      gate.resumed shouldBe listOf(1)
      gate.starts shouldBe
        listOf(Duration.ZERO, outputSlotTime(outputSlotOf(SEEK_TARGET, FIXTURE_FRAME_RATE), FIXTURE_FRAME_RATE))
      engine.clockRunning shouldBe true
    }

  // The rebuild replaces the clock and starts the replacement from the held playhead, and only that start runs on.
  @Test
  fun `a rebuild cancels the start in flight and starts its own`() =
    heldStart { engine, gate ->
      engine.play()
      awaitContract("the start to reach the audio graph") { gate.entered == 1 }
      engine.awaitComposition(webFixtureTrimmed(REBUILT_LENGTH)).shouldBeInstanceOf<SetCompositionResult.Success>()
      awaitContract("the rebuild's start to reach the audio graph") { gate.entered == 2 }
      gate.releaseAll()
      engine.awaitQuietDone()

      gate.resumed shouldBe listOf(1)
      gate.starts shouldBe listOf(Duration.ZERO, Duration.ZERO)
      engine.clockRunning shouldBe true
    }

  // A swap keeps the graph it had, which goes on sounding the old playhead, so an edit moved to a start begins again
  // there.
  @Test
  fun `a parameter edit moved to a start while playing starts the transport there`() =
    heldStart(webFixtureComposition(listOf(Brightness(DIM)))) { engine, gate ->
      engine.play()
      awaitContract("the start to reach the audio graph") { gate.entered == 1 }
      gate.releaseAll()
      awaitContract("the transport to start") { engine.transportStarted }

      engine
        .awaitComposition(webFixtureComposition(listOf(Brightness(BRIGHT))), startAt = SEEK_TARGET)
        .shouldBeInstanceOf<SetCompositionResult.Success>()
      awaitContract("the edit's start to reach the audio graph") { gate.entered == 2 }
      gate.releaseAll()
      engine.awaitQuietDone()

      engine.platformLoads shouldBe 1
      gate.starts shouldBe
        listOf(Duration.ZERO, outputSlotTime(outputSlotOf(SEEK_TARGET, FIXTURE_FRAME_RATE), FIXTURE_FRAME_RATE))
      engine.clockRunning shouldBe true
    }

  @Test
  fun `a play before the first load starts once the load has its first frame`() =
    beforeLoad { engine, gate, recorder ->
      engine.play()
      recorder.lastState.playWhenReady shouldBe true
      recorder.lastState.isPlaying shouldBe false
      engine.transportStarted shouldBe false
      gate.entered shouldBe 0

      engine.awaitComposition(webFixtureComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
      awaitContract("the start to reach the audio graph") { gate.entered == 1 }
      gate.releaseAll()
      engine.awaitQuietDone()

      gate.starts shouldBe listOf(Duration.ZERO)
      engine.clockRunning shouldBe true
      recorder.lastState.isPlaying shouldBe true
    }

  @Test
  fun `a pause before the first load leaves the load stopped`() =
    beforeLoad { engine, gate, recorder ->
      engine.play()
      engine.pause()

      engine.awaitComposition(webFixtureComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
      engine.awaitQuietDone()

      gate.entered shouldBe 0
      recorder.lastState.isPlaying shouldBe false
    }

  @Test
  fun `a page hidden before the first load takes the play back`() =
    beforeLoad { engine, gate, recorder ->
      engine.play()
      engine.onPageHidden()
      recorder.lastState.playWhenReady shouldBe false

      engine.awaitComposition(webFixtureComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
      engine.awaitQuietDone()

      gate.entered shouldBe 0
    }

  private fun beforeLoad(body: suspend (BrowserPlayerEngine, AudioStartGate, ContractRecorder) -> Unit) =
    contractTest { scope ->
      val gate = AudioStartGate()
      val engine = heldEngine(scope, gate)
      withEngine(engine) { recorder ->
        body(engine, gate, recorder)
        engine.pause()
      }
    }

  private fun heldStart(
    composition: Composition = webFixtureComposition(),
    body: suspend (BrowserPlayerEngine, AudioStartGate) -> Unit,
  ) = contractTest { scope ->
    val gate = AudioStartGate()
    val engine = heldEngine(scope, gate)
    withEngine(engine) { recorder ->
      engine.awaitComposition(composition).shouldBeInstanceOf<SetCompositionResult.Success>()
      awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }
      body(engine, gate)
      engine.pause()
    }
  }

  private fun heldEngine(
    scope: CoroutineScope,
    gate: AudioStartGate,
  ): BrowserPlayerEngine =
    BrowserPlayerEngine(
      parent = scope,
      planner = BrowserPreviewPlanner(CONTRACT_COMPONENTS),
      config = PlayerConfig(),
      startAudio = { _, from -> gate.start(from) },
    )

  private suspend fun BrowserPlayerEngine.awaitQuietDone() {
    awaitStep("the engine to go quiet") { awaitQuiet() }
  }

  /**
   * Stands in for starting the audio graph, holding every start there until the test releases it.
   *
   * It records which starts went on past the hold once released, in the order they arrived, so a start that should
   * have been cancelled shows up as one that resumed.
   */
  private class AudioStartGate {
    private val held = mutableListOf<CompletableDeferred<Unit>>()

    /**
     * Where each start was asked to start from, in the order they arrived.
     */
    val starts = mutableListOf<Duration>()

    /**
     * The index of every start that went on past the hold.
     */
    val resumed = mutableListOf<Int>()

    val entered: Int get() = held.size

    suspend fun start(from: Duration) {
      val index = held.size
      val release = CompletableDeferred<Unit>()
      held += release
      starts += from
      release.await()
      resumed += index
    }

    fun releaseAll() {
      held.forEach { it.complete(Unit) }
    }
  }

  private companion object {
    val SEEK_TARGET: Duration = 600.milliseconds
    val REBUILT_LENGTH: Duration = 1400.milliseconds
    const val BRIGHT = 1.4f
    const val DIM = 0.4f
  }
}

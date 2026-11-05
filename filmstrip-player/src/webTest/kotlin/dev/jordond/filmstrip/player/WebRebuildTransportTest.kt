package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.CompositionDiff
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.diff
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.player.contract.ContractRecorder
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.AUTOPLAY_GRACE
import dev.jordond.filmstrip.player.internal.BrowserPlayerEngine
import dev.jordond.filmstrip.player.internal.BrowserPreviewPlanner
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * What the browser engine's transport does while a rebuild is drawing its first frame.
 *
 * The rebuild's draw is held open, so anything else the engine is asked to do lands inside it.
 */
class WebRebuildTransportTest {
  @Test
  fun `a parameter edit replacing a rebuild mid draw starts the stopped transport`() =
    playing { engine, recorder, draws ->
      val rebuilt = webFixtureTrimmedBy(BRIGHT)
      engine.loadHeld(rebuilt, draws)

      val tuned = webFixtureTrimmedBy(DIM)
      diff(rebuilt, tuned) shouldBe CompositionDiff.ParametersOnly
      engine.awaitComposition(tuned).shouldBeInstanceOf<SetCompositionResult.Success>()

      awaitContract("the clock to run again") { engine.clockRunning }
      recorder.lastState.status shouldBe PlaybackStatus.Ready
      recorder.lastState.playWhenReady shouldBe true
      val mark = recorder.playheads.size
      awaitContract("the playhead to advance") {
        val since = recorder.playheads.drop(mark)
        since.size >= 2 && since.last() > since.first()
      }
    }

  // An equal edit has nothing to swap, and its graph still owes the frame the replaced load was drawing.
  @Test
  fun `an equal edit replacing a rebuild mid draw builds it again and plays on`() =
    playing { engine, recorder, draws ->
      val rebuilt = webFixtureTrimmedBy(BRIGHT)
      engine.loadHeld(rebuilt, draws)
      val loads = engine.platformLoads

      engine.awaitComposition(rebuilt).shouldBeInstanceOf<SetCompositionResult.Success>()

      engine.platformLoads shouldBe loads + 1
      awaitContract("the clock to run again") { engine.clockRunning }
      recorder.lastState.status shouldBe PlaybackStatus.Ready
      recorder.events.filterIsInstance<PlaybackEvent.FirstFrameRendered>().size shouldBe 2
    }

  @Test
  fun `a parameter edit over a running transport leaves it alone`() =
    playing { engine, _, draws ->
      val started = draws.starts

      engine
        .awaitComposition(webFixtureComposition(listOf(Brightness(DIM))))
        .shouldBeInstanceOf<SetCompositionResult.Success>()

      draws.starts shouldBe started
      engine.clockRunning shouldBe true
    }

  @Test
  fun `a rebuild drawing its first frame leaves no transport running`() =
    playing { engine, _, draws ->
      engine.transportStarted shouldBe true

      engine.loadHeld(webFixtureTrimmedBy(BRIGHT), draws)

      engine.transportStarted shouldBe false
      engine.clockRunning shouldBe false
      draws.release()
      awaitContract("the rebuild's start to reach the clock") { engine.transportStarted }
      engine.clockRunning shouldBe true
    }

  // A headless browser on its stock policy leaves every graph suspended, which is the case this needs. The first start
  // is refused for real a grace period on, so the rebuild has to land inside it, and nothing here reports a refusal for
  // a graph no start has reached.
  @Test
  fun `a rebuild drawing its first frame is not read as an autoplay refusal`() =
    held(webFixtureComposition(audio = AudioSpec.Keep)) { engine, recorder, draws ->
      engine.play()
      awaitContract("the transport to start") { engine.transportStarted }

      engine.loadHeld(webFixtureTrimmedBy(BRIGHT, AudioSpec.Keep), draws)
      val refusedAhead = recorder.externalChanges.size
      val since = TimeSource.Monotonic.markNow()
      awaitContract("the grace period to pass") { since.elapsedNow() > AUTOPLAY_GRACE * 2 }

      recorder.externalChanges.size shouldBe refusedAhead
      recorder.lastState.playWhenReady shouldBe (refusedAhead == 0)
    }

  private fun playing(body: suspend (BrowserPlayerEngine, ContractRecorder, HeldDraws) -> Unit) =
    held(webFixtureComposition(listOf(Brightness(BRIGHT)))) { engine, recorder, draws ->
      engine.play()
      awaitContract("the clock to run") { engine.clockRunning }
      body(engine, recorder, draws)
    }

  private fun held(
    composition: EditComposition,
    body: suspend (BrowserPlayerEngine, ContractRecorder, HeldDraws) -> Unit,
  ) = contractTest { scope ->
    val draws = HeldDraws()
    val engine =
      BrowserPlayerEngine(
        parent = scope,
        planner = BrowserPreviewPlanner(CONTRACT_COMPONENTS),
        config = PlayerConfig(),
        startAudio = { graph, from ->
          draws.starts++
          graph?.start(from)
        },
        drawFirstFrame = { preview, at ->
          draws.hold()
          preview.frameAt(at) != null
        },
      )
    withEngine(engine) { recorder ->
      engine.awaitComposition(composition).shouldBeInstanceOf<SetCompositionResult.Success>()
      awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }
      body(engine, recorder, draws)
      draws.release()
      engine.pause()
    }
  }

  // Sets composition and suspends once its first frame draw is being held.
  private suspend fun BrowserPlayerEngine.loadHeld(
    composition: EditComposition,
    draws: HeldDraws,
  ) {
    draws.holdNext()
    setComposition(SetCompositionRequest(composition)) { }
    awaitContract("the rebuild to reach its first frame") { draws.holding }
  }

  // The fixture cut short, which is a structural change from the untrimmed one, under one brightness.
  private fun webFixtureTrimmedBy(
    brightness: Float,
    audio: AudioSpec = AudioSpec.Remove,
  ): EditComposition =
    webFixtureComposition(listOf(Brightness(brightness)), audio).withClips(webFixtureTrimmed(REBUILT_LENGTH).clips)

  /**
   * Stands in for drawing a new graph's first frame, holding only the draw it was told to.
   *
   * A draw cancelled while held counts as let go, so the next edit's draw goes straight through.
   */
  private class HeldDraws {
    private var armed = false
    private var gate: CompletableDeferred<Unit>? = null

    /**
     * How many transport starts have reached the audio graph.
     */
    var starts = 0

    /**
     * Whether a draw is being held right now.
     */
    val holding: Boolean get() = gate != null

    fun holdNext() {
      armed = true
    }

    suspend fun hold() {
      if (!armed) return
      armed = false
      val waiting = CompletableDeferred<Unit>()
      gate = waiting
      try {
        waiting.await()
      } finally {
        gate = null
      }
    }

    fun release() {
      armed = false
      gate?.complete(Unit)
    }
  }

  private companion object {
    const val BRIGHT = 1.4f
    const val DIM = 0.4f
    val REBUILT_LENGTH: Duration = 1400.milliseconds
  }
}

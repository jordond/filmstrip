package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.codec.Bitrate
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.CompositionDiff
import dev.jordond.filmstrip.edit.diff
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.player.contract.ContractRecorder
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.AUTOPLAY_GRACE
import dev.jordond.filmstrip.player.internal.BrowserPlayerEngine
import dev.jordond.filmstrip.player.internal.BrowserPreviewPlan
import dev.jordond.filmstrip.player.internal.BrowserPreviewPlanner
import dev.jordond.filmstrip.player.internal.PlanResult
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
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

  // The fixture is SDR, so the planner stubs the grade, which is enough to pin the rebuild and the playhead it keeps.
  @Test
  fun `a spec that flips the grade rebuilds and keeps the playhead`() =
    held(webFixtureComposition(), StubbedGradePlanner()) { engine, recorder, _ ->
      engine.seekTo(LATER, SeekAccuracy.Exact)
      awaitContract("the seek to land") { recorder.playheads.lastOrNull() == LATER }
      val loads = engine.platformLoads

      engine.setExportSpec(ExportSpec(hdr = HdrMode.ToneMapToSdr))
      awaitContract("the grade to rebuild") { engine.platformLoads > loads }
      awaitContract("the rebuilt preview to be presentable") { recorder.lastState.status == PlaybackStatus.Ready }

      engine.platformLoads shouldBe loads + 1
      recorder.playheads.last() shouldBe LATER
    }

  @Test
  fun `a bitrate change rebuilds nothing`() {
    val planner = StubbedGradePlanner()
    held(webFixtureComposition(), planner) { engine, recorder, _ ->
      val loads = engine.platformLoads
      val spec = ExportSpec(bitrate = Bitrate.mbps(8))

      engine.setExportSpec(spec)
      awaitContract("the new spec to be planned") { planner.specs.last() == spec }
      engine.awaitComposition(webFixtureComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()

      engine.platformLoads shouldBe loads
      recorder.lastState.hasComposition shouldBe true
    }
  }

  private fun playing(body: suspend (BrowserPlayerEngine, ContractRecorder, HeldDraws) -> Unit) =
    held(webFixtureComposition(listOf(Brightness(BRIGHT)))) { engine, recorder, draws ->
      engine.play()
      awaitContract("the clock to run") { engine.clockRunning }
      body(engine, recorder, draws)
    }

  private fun held(
    composition: Composition,
    planner: BrowserPreviewPlanner = BrowserPreviewPlanner(CONTRACT_COMPONENTS),
    body: suspend (BrowserPlayerEngine, ContractRecorder, HeldDraws) -> Unit,
  ) = contractTest { scope ->
    val draws = HeldDraws()
    val engine =
      BrowserPlayerEngine(
        parent = scope,
        planner = planner,
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
    composition: Composition,
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
  ): Composition =
    webFixtureComposition(listOf(Brightness(brightness)), audio).withClips(webFixtureTrimmed(REBUILT_LENGTH).clips)

  /**
   * Plans every edit as usual, records each spec, and reports the tone mapped grade whenever a spec asks for it.
   */
  private class StubbedGradePlanner : BrowserPreviewPlanner(CONTRACT_COMPONENTS) {
    val specs = mutableListOf<ExportSpec>()

    override suspend fun plan(
      composition: Composition,
      policy: PreviewQualityPolicy,
      exportSpec: ExportSpec,
    ): PlanResult<BrowserPreviewPlan, PlaybackError> {
      specs += exportSpec
      val result = super.plan(composition, policy, exportSpec)
      if (exportSpec.hdr != HdrMode.ToneMapToSdr || result !is PlanResult.Ready) return result
      val plan = result.plan
      return PlanResult.Ready(BrowserPreviewPlan(plan.edit, plan.resolved.toneMapped(), plan.info))
    }

    private fun ResolvedComposition.toneMapped(): ResolvedComposition =
      ResolvedComposition(
        tracks = tracks,
        compositionEffects = compositionEffects,
        output = output,
        layoutSize = layoutSize,
        fill = fill,
        duration = duration,
        hdr = ResolvedHdr.ToneMap,
        hdrTransfer = null,
        path = path,
        audio = audio,
        adjustments = adjustments,
        encoderName = encoderName,
        naturalSize = naturalSize,
      )
  }

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
    val LATER: Duration = 700.milliseconds
  }
}

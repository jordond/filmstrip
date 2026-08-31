package dev.jordond.filmstrip.playback

import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.playback.contract.EngineUnderTest
import dev.jordond.filmstrip.playback.contract.Interruption
import dev.jordond.filmstrip.playback.contract.PlayerEngineContractTest
import dev.jordond.filmstrip.playback.contract.awaitComposition
import dev.jordond.filmstrip.playback.contract.awaitContract
import dev.jordond.filmstrip.playback.contract.contractTest
import dev.jordond.filmstrip.playback.contract.withEngine
import dev.jordond.filmstrip.player.PlayerFeature
import dev.jordond.filmstrip.player.SetCompositionResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlin.test.Test
import kotlin.time.Duration

/**
 * The shared engine contracts, run against real WebCodecs in a real browser.
 *
 * The browser's interruption list is genuinely shorter than any other platform's, and short for
 * platform reasons rather than because this suite left something out. A page never hears about
 * audio focus, because nothing takes it away, and never hears about an output route, because a
 * headphone unplug is invisible to it. What is left is the page going off screen and the browser
 * refusing to start an audio graph without a fresh gesture, and the second of those has no analogue
 * anywhere else. The two in `UNSTAGEABLE_INTERRUPTIONS` are out of reach on every platform.
 *
 * Both staged occasions go through the engine's own seam rather than through the browser, because
 * whether a page is granted autoplay depends on how the runner was launched and a karma page cannot
 * be sent to the background at all. The audio test below takes the real path instead, and a headless
 * browser with its stock policy does refuse there.
 */
class WebEngineContractTest : PlayerEngineContractTest() {
  override fun createEngine(scope: CoroutineScope): EngineUnderTest = WebEngineUnderTest(scope)

  override val fixture: EditComposition = webFixtureComposition()

  override val stageableInterruptions: Set<Interruption> =
    setOf(Interruption.AutoplayBlocked, Interruption.AppBackgrounded)

  /**
   * The audio branch of the clock, and what happens when the browser will not start it.
   *
   * Which of the two outcomes a run takes is the browser's call rather than the test's: a page that
   * has been granted autoplay plays and the hardware clock advances the playhead, and one that has
   * not is refused and says so exactly once. A headless browser on its stock policy takes the
   * second, so this is where the refusal is exercised for real rather than through a seam. Both are
   * the contract, and asserting only one would make this suite depend on how the runner was
   * launched.
   */
  @Test
  fun `a composition with audio runs on the audio clock, or reports the refusal`() =
    contractTest { scope ->
      val subject = WebEngineUnderTest(scope)
      withEngine(subject.engine) { recorder ->
        subject.engine.features
          .supports(PlayerFeature.AudioMonitoring) shouldBe true

        subject.engine
          .awaitComposition(webFixtureComposition(audio = AudioSpec.Keep))
          .shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the preview to be presentable") { recorder.lastState.hasComposition }

        // Written before the graph has been allowed to start, which a suspended context accepts.
        subject.engine.setVolume(HALF_VOLUME)
        subject.engine.play()

        awaitContract("the audio clock to advance, or the browser to refuse it") {
          (recorder.playhead ?: Duration.ZERO) > Duration.ZERO || recorder.externalChanges.isNotEmpty()
        }

        when {
          recorder.externalChanges.isEmpty() -> {
            recorder.lastState.playWhenReady shouldBe true
          }
          else -> {
            recorder.externalChanges.map { it.playWhenReady } shouldBe listOf(false)
            recorder.lastState.playWhenReady shouldBe false
          }
        }
      }
    }

  private companion object {
    const val HALF_VOLUME = 0.5f
  }
}

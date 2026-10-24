package dev.jordond.filmstrip.playback

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.CoreImageEffect
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.playback.contract.awaitComposition
import dev.jordond.filmstrip.playback.contract.awaitContract
import dev.jordond.filmstrip.playback.contract.contractTest
import dev.jordond.filmstrip.playback.contract.settleForAbsence
import dev.jordond.filmstrip.playback.contract.withEngine
import dev.jordond.filmstrip.playback.internal.AvPlayerEngine
import dev.jordond.filmstrip.playback.internal.AvPreviewPlanner
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.PlayerConfig
import dev.jordond.filmstrip.player.SetCompositionResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.time.Duration

/**
 * A step that throws while the preview draws, the way an overlay picture too large to draw at its colour does at its
 * first converting draw.
 *
 * The preview stops with the step's reason as its error, and keeps that status while its item reports changes.
 */
class AppleStepFailureTest {
  init {
    pumpMainRunLoopDuringContracts()
  }

  @Test
  fun `a step that throws while drawing fails playback with its reason`() =
    contractTest { scope ->
      val components = ComponentRegistry.Builder(CONTRACT_COMPONENTS).add(ThrowingResolver).build()
      val engine = AvPlayerEngine(scope, AvPreviewPlanner(components), PlayerConfig())
      withEngine(engine) { recorder ->
        engine
          .awaitComposition(appleFixtureComposition(listOf(Throwing)))
          .shouldBeInstanceOf<SetCompositionResult.Success>()
        engine.readback.requestFrame(Duration.ZERO) { }
        awaitContract("playback to fail") { recorder.events.any { it is PlaybackEvent.Failed } }
        settleForAbsence()

        val error =
          recorder.events
            .filterIsInstance<PlaybackEvent.Failed>()
            .single()
            .error
            .shouldBeInstanceOf<PlaybackError.UnsupportedFormat>()
        error.message shouldContain REASON
        recorder.lastState.status shouldBe PlaybackStatus.Error(error)
      }
    }

  private object Throwing : EffectSpec {
    override val id: String = "dev.jordond.filmstrip.test.throwing"
  }

  private object ThrowingResolver : EffectResolver {
    override fun resolve(
      spec: EffectSpec,
      capabilities: RenderCapabilities,
      attributes: Attributes,
    ): EffectResolution? =
      if (spec !is Throwing) {
        null
      } else {
        EffectResolution.Resolved(PlatformEffect(CoreImageEffect { _, _ -> throw IllegalStateException(REASON) }))
      }
  }

  private companion object {
    const val REASON = "The picture is too large to draw at its colour."
  }
}

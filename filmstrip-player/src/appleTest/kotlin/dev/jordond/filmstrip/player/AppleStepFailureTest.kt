package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.CoreImageEffect
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.AvPlayerEngine
import dev.jordond.filmstrip.player.internal.AvPreviewPlanner
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import platform.AVFoundation.rate
import kotlin.test.Test
import kotlin.time.Duration

/**
 * A step that throws while the preview draws, the way an overlay picture too large to draw at its color does at its
 * first converting draw.
 *
 * The preview stops with the step's reason as its error, and keeps that status while its item reports changes. A play
 * still wanted starts back up on the next load.
 */
class AppleStepFailureTest {
  init {
    pumpMainRunLoopDuringContracts()
  }

  @Test
  fun `a step that throws while drawing fails playback with its reason`() =
    contractTest { scope ->
      val components = ComponentRegistry.Builder(CONTRACT_COMPONENTS).addEffectResolver(ThrowingResolver).build()
      val engine = AvPlayerEngine(scope, AvPreviewPlanner(components), PlayerConfig(), muted = true)
      withEngine(engine) { recorder ->
        engine
          .awaitComposition(appleFixtureComposition(listOf(Throwing)))
          .shouldBeInstanceOf<SetCompositionResult.Success>()
        engine.readback.requestFrame(Duration.ZERO) { }
        awaitContract("playback to fail") { recorder.events.any { it is PlaybackEvent.Failed } }
        engine.drainCallbacks()

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

  @Test
  fun `a step that throws while drawing halts a player the engine was playing`() =
    contractTest { scope ->
      val components = ComponentRegistry.Builder(CONTRACT_COMPONENTS).addEffectResolver(ThrowingResolver).build()
      val engine = AvPlayerEngine(scope, AvPreviewPlanner(components), PlayerConfig(), muted = true)
      withEngine(engine) { recorder ->
        engine
          .awaitComposition(appleFixtureComposition(listOf(Throwing)))
          .shouldBeInstanceOf<SetCompositionResult.Success>()
        engine.play()
        engine.platformPlayer.rate shouldNotBe 0f

        engine.readback.requestFrame(Duration.ZERO) { }
        awaitContract("playback to fail") { recorder.events.any { it is PlaybackEvent.Failed } }
        engine.drainCallbacks()

        engine.platformPlayer.rate shouldBe 0f
      }
    }

  /**
   * The new parameters reach the item already attached, so the player is restarted with no rebuild behind it.
   */
  @Test
  fun `new parameters after a step threw start the play back up`() =
    contractTest { scope ->
      val components = ComponentRegistry.Builder(CONTRACT_COMPONENTS).addEffectResolver(ThrowingResolver).build()
      val engine = AvPlayerEngine(scope, AvPreviewPlanner(components), PlayerConfig(), muted = true)
      withEngine(engine) { recorder ->
        engine
          .awaitComposition(appleFixtureComposition(listOf(Tuned(throws = true))))
          .shouldBeInstanceOf<SetCompositionResult.Success>()
        engine.play()
        engine.readback.requestFrame(Duration.ZERO) { }
        awaitContract("playback to fail") { recorder.events.any { it is PlaybackEvent.Failed } }
        engine.drainCallbacks()
        engine.platformPlayer.rate shouldBe 0f
        val halted = recorder.playhead ?: Duration.ZERO

        engine
          .awaitComposition(appleFixtureComposition(listOf(Tuned(throws = false))))
          .shouldBeInstanceOf<SetCompositionResult.Success>()

        engine.platformLoads shouldBe 1
        awaitContract("the playhead to move on from $halted") { (recorder.playhead ?: Duration.ZERO) > halted }
        recorder.lastState.status shouldBe PlaybackStatus.Ready
        recorder.lastState.playWhenReady shouldBe true
      }
    }

  private object Throwing : EffectSpec {
    override val id: String = "dev.jordond.filmstrip.test.throwing"
  }

  private data class Tuned(
    val throws: Boolean,
  ) : EffectSpec {
    override val id: String = "dev.jordond.filmstrip.test.tuned"
  }

  private object ThrowingResolver : EffectResolver {
    override fun resolve(
      spec: EffectSpec,
      capabilities: RenderCapabilities,
      attributes: Attributes,
    ): EffectResolution? =
      when {
        spec is Throwing || (spec is Tuned && spec.throws) -> {
          EffectResolution.Resolved(PlatformEffect(CoreImageEffect { _, _ -> throw IllegalStateException(REASON) }))
        }
        spec is Tuned -> {
          EffectResolution.Resolved(PlatformEffect(CoreImageEffect { image, _ -> image }))
        }
        else -> {
          null
        }
      }
  }

  private companion object {
    const val REASON = "The picture is too large to draw at its color."
  }
}

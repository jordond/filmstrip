package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.audio.AudioAttributes
import dev.jordond.filmstrip.audio.AudioEffectResolution
import dev.jordond.filmstrip.audio.AudioEffectResolver
import dev.jordond.filmstrip.audio.AudioEffectSpec
import dev.jordond.filmstrip.audio.AudioKernel
import dev.jordond.filmstrip.audio.PlatformAudioEffect
import dev.jordond.filmstrip.edit.AudioLevel
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectId
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.AvPlayerEngine
import dev.jordond.filmstrip.player.internal.AvPreviewPlanner
import dev.jordond.filmstrip.test.TimelineBursts
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import platform.AVFoundation.audioMix
import platform.AVFoundation.currentItem
import kotlin.test.Test
import kotlin.time.Duration

/**
 * How the Apple preview takes a change to an edit's audio: its gains and its audio effects.
 *
 * The timeline does not move in any of these, so the item the player holds is the one that keeps playing wherever
 * the taps or the ramps can take the change, and only an edit gaining its first audio effect or losing its last
 * builds a new one.
 */
class ApplePreviewAudioEffectMediaTest {
  init {
    pumpMainRunLoopDuringContracts()
  }

  @Test
  fun `a changed audio effect and level play on through the item and the mix already attached`() =
    contractTest { scope ->
      val engine = engine(scope)
      withEngine(engine) {
        engine.awaitComposition(edit(effect = TimelineBursts(LOUD))).shouldBeInstanceOf<SetCompositionResult.Success>()
        val item = engine.platformPlayer.currentItem.shouldNotBeNull()
        val mix = item.audioMix.shouldNotBeNull()

        engine
          .awaitComposition(edit(level = AudioLevel.Volume(QUIET), effect = TimelineBursts(QUIET)))
          .shouldBeInstanceOf<SetCompositionResult.Success>()

        engine.platformLoads shouldBe 1
        engine.platformPlayer.currentItem shouldBe item
        item.audioMix shouldBe mix
      }
    }

  // With no audio effect there is no tap to take the level, so it reaches the item as ramps in a mix of its own.
  @Test
  fun `a changed level with no audio effect hands the same item a new mix`() =
    contractTest { scope ->
      val engine = engine(scope)
      withEngine(engine) {
        engine
          .awaitComposition(
            edit(level = AudioLevel.Volume(LOUD)),
          ).shouldBeInstanceOf<SetCompositionResult.Success>()
        val item = engine.platformPlayer.currentItem.shouldNotBeNull()
        val mix = item.audioMix.shouldNotBeNull()

        engine
          .awaitComposition(
            edit(level = AudioLevel.Volume(QUIET)),
          ).shouldBeInstanceOf<SetCompositionResult.Success>()

        engine.platformLoads shouldBe 1
        engine.platformPlayer.currentItem shouldBe item
        item.audioMix.shouldNotBeNull() shouldNotBe mix
      }
    }

  @Test
  fun `an edit gaining its first audio effect builds a new item`() =
    contractTest { scope ->
      val engine = engine(scope)
      withEngine(engine) {
        engine.awaitComposition(edit()).shouldBeInstanceOf<SetCompositionResult.Success>()

        engine.awaitComposition(edit(effect = TimelineBursts(LOUD))).shouldBeInstanceOf<SetCompositionResult.Success>()

        engine.platformLoads shouldBe 2
      }
    }

  @Test
  fun `an audio effect that throws while playing fails playback with its reason`() =
    contractTest { scope ->
      val engine = engine(scope)
      withEngine(engine) { recorder ->
        engine.awaitComposition(edit(effect = Throwing)).shouldBeInstanceOf<SetCompositionResult.Success>()
        engine.play()

        awaitContract("playback to fail") { recorder.events.any { it is PlaybackEvent.Failed } }
        engine.drainCallbacks()

        recorder.events
          .filterIsInstance<PlaybackEvent.Failed>()
          .first()
          .error.message shouldContain REASON
      }
    }

  private fun engine(scope: CoroutineScope): AvPlayerEngine {
    val components = ComponentRegistry.Builder(CONTRACT_COMPONENTS).addAudioEffectResolver(Kernels).build()
    return AvPlayerEngine(scope, AvPreviewPlanner(components), PlayerConfig(), muted = true)
  }

  private fun edit(
    level: AudioLevel = AudioLevel.Inherit,
    effect: AudioEffectSpec? = null,
  ): Composition {
    val clip = Clip(MediaSource.of(appleFixtureClip()), TimeRange.of(Duration.ZERO, CLIP_LENGTH), audio = level)
    return Composition(listOf(Track(listOf(clip)))).withAudioEffects(listOfNotNull(effect))
  }

  private data object Throwing : AudioEffectSpec {
    override val id: EffectId = EffectId("dev.jordond.filmstrip.test.throwing-audio")
  }

  private object Kernels : AudioEffectResolver {
    override fun resolve(
      spec: AudioEffectSpec,
      attributes: AudioAttributes,
    ): AudioEffectResolution? =
      when (spec) {
        is TimelineBursts -> AudioEffectResolution.Resolved(PlatformAudioEffect(spec::newKernel))
        is Throwing -> AudioEffectResolution.Resolved(PlatformAudioEffect { ThrowingKernel })
        else -> null
      }
  }

  private object ThrowingKernel : AudioKernel() {
    override fun process(
      buffer: FloatArray,
      frames: Int,
      position: Long,
    ): Unit = throw IllegalStateException(REASON)
  }

  private companion object {
    const val LOUD = 0.5f
    const val QUIET = 0.25f
    const val REASON = "this audio kernel always throws"
  }
}

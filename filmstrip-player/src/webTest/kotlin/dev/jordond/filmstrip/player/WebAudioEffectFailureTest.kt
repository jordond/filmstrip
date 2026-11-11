package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.audio.AudioAttributes
import dev.jordond.filmstrip.audio.AudioEffectResolution
import dev.jordond.filmstrip.audio.AudioEffectResolver
import dev.jordond.filmstrip.audio.AudioEffectSpec
import dev.jordond.filmstrip.audio.AudioKernel
import dev.jordond.filmstrip.audio.PlatformAudioEffect
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.BrowserPlayerEngine
import dev.jordond.filmstrip.player.internal.BrowserPreviewPlanner
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlin.test.Test

/**
 * What the browser preview does when an audio effect's kernel throws while it plays.
 *
 * The kernel runs where the transport schedules audio: once as playback starts, and then on every tick that brings a
 * new window inside the look-ahead. Either way playback stops and fails with what the kernel threw.
 */
class WebAudioEffectFailureTest {
  @Test
  fun `a kernel that throws on its first block fails playback as it starts`() =
    contractTest { scope ->
      val engine = engine(scope)
      withEngine(engine) { recorder ->
        engine.awaitComposition(edit(Throwing(after = 0f))).shouldBeInstanceOf<SetCompositionResult.Success>()
        engine.play()

        awaitContract("playback to fail") { recorder.events.any { it is PlaybackEvent.Failed } }

        recorder.events
          .filterIsInstance<PlaybackEvent.Failed>()
          .first()
          .error.message shouldContain REASON
        recorder.lastState.playWhenReady shouldBe false
      }
    }

  // The first windows play clean, so the start goes through and the kernel throws inside a tick.
  @Test
  fun `a kernel that throws part way in fails playback from the tick`() =
    contractTest { scope ->
      val engine = engine(scope)
      withEngine(engine) { recorder ->
        engine.awaitComposition(edit(Throwing(after = LATER))).shouldBeInstanceOf<SetCompositionResult.Success>()
        engine.play()

        awaitContract("playback to start") { recorder.lastState.isPlaying }
        awaitContract("playback to fail") { recorder.events.any { it is PlaybackEvent.Failed } }

        recorder.events
          .filterIsInstance<PlaybackEvent.Failed>()
          .first()
          .error.message shouldContain REASON
        recorder.lastState.playWhenReady shouldBe false
      }
    }

  private fun engine(scope: CoroutineScope): BrowserPlayerEngine {
    val components = ComponentRegistry.Builder(CONTRACT_COMPONENTS).addAudioEffectResolver(Kernels).build()
    return BrowserPlayerEngine(scope, BrowserPreviewPlanner(components), PlayerConfig())
  }

  private fun edit(effect: AudioEffectSpec) =
    webFixtureComposition(audio = AudioSpec.Keep).withAudioEffects(listOf(effect))

  // Throws once it is handed a block that opens [after] seconds or more into the composition.
  private data class Throwing(
    val after: Float,
  ) : AudioEffectSpec {
    override val id: String = "dev.jordond.filmstrip.test.throwing-audio"
  }

  private object Kernels : AudioEffectResolver {
    override fun resolve(
      spec: AudioEffectSpec,
      attributes: AudioAttributes,
    ): AudioEffectResolution? =
      (spec as? Throwing)?.let { throwing ->
        AudioEffectResolution.Resolved(
          PlatformAudioEffect { format -> ThrowingKernel((throwing.after * format.sampleRate).toLong()) },
        )
      }
  }

  private class ThrowingKernel(
    private val from: Long,
  ) : AudioKernel() {
    override fun process(
      buffer: FloatArray,
      frames: Int,
      position: Long,
    ) {
      if (position + frames > from) throw IllegalStateException(REASON)
    }
  }

  private companion object {
    const val REASON = "this audio kernel always throws"
    const val LATER = 0.75f
  }
}

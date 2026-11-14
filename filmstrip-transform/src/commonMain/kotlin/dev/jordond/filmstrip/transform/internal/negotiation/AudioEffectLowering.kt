package dev.jordond.filmstrip.transform.internal.negotiation

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.audio.AudioAttributes
import dev.jordond.filmstrip.audio.AudioEffectResolution
import dev.jordond.filmstrip.audio.AudioEffectResolver
import dev.jordond.filmstrip.audio.AudioEffectSpec
import dev.jordond.filmstrip.audio.PlatformAudioEffect
import dev.jordond.filmstrip.audio.resolveAudioEffect
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.EffectId
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedAudioEffect

/**
 * Lowers audio effects onto platform objects through the resolver chain, in the order they were declared.
 */
internal class AudioEffectLowering(
  private val resolvers: List<AudioEffectResolver>,
) {
  /**
   * Asks the resolvers for each of [specs] over [span], one entry per effect.
   */
  fun lower(
    specs: List<AudioEffectSpec>,
    span: TimeRange,
  ): List<LoweredAudioEffect> {
    if (specs.isEmpty()) return emptyList()
    val attributes = AudioAttributes(span)
    return specs.map { spec ->
      when (val resolution = resolvers.resolveAudioEffect(spec, attributes)) {
        is AudioEffectResolution.Resolved -> {
          LoweredAudioEffect(spec, resolution.effect)
        }
        is AudioEffectResolution.Degraded -> {
          LoweredAudioEffect(spec, resolution.effect, degraded = resolution.causes.map { spec.id to it.message })
        }
        is AudioEffectResolution.Unsupported -> {
          LoweredAudioEffect(spec, null, unsupported = resolution.message)
        }
        is AudioEffectResolution.Failed -> {
          LoweredAudioEffect(spec, null, failed = resolution.error)
        }
        null -> {
          LoweredAudioEffect(spec, null, unsupported = unclaimed(spec.id))
        }
      }
    }
  }
}

/**
 * One audio effect, after the resolver chain has been asked.
 *
 * @property unsupported Why the effect cannot be realised here, or null when it can or its resolution failed.
 * @property degraded The effect's id paired with each thing given up, in the order the resolver gave the causes.
 * @property failed The error the effect's resolution failed with, or null when it did not fail.
 */
internal class LoweredAudioEffect(
  val spec: AudioEffectSpec,
  effect: PlatformAudioEffect?,
  val unsupported: String? = null,
  val degraded: List<Pair<EffectId, String>> = emptyList(),
  val failed: FilmstripError? = null,
) {
  val resolvedEffect: ResolvedAudioEffect? = effect?.let { ResolvedAudioEffect(spec.id, it, spec) }
}

private fun unclaimed(id: EffectId): String =
  "No audio effect resolver claimed $id. Register the one that realizes it with addAudioEffectResolver."

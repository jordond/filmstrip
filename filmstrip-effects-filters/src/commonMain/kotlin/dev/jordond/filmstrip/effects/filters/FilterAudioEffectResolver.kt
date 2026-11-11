package dev.jordond.filmstrip.effects.filters

import dev.jordond.filmstrip.audio.AudioAttributes
import dev.jordond.filmstrip.audio.AudioEffectResolution
import dev.jordond.filmstrip.audio.AudioEffectResolver
import dev.jordond.filmstrip.audio.AudioEffectSpec

/**
 * Resolves the audio effects in this module for the current platform.
 */
public expect class FilterAudioEffectResolver() : AudioEffectResolver {
  /**
   * Realises [spec] for the current platform.
   *
   * @return The resolved effect, or null to decline [spec] and leave it to the next resolver.
   */
  override fun resolve(
    spec: AudioEffectSpec,
    attributes: AudioAttributes,
  ): AudioEffectResolution?
}

package dev.jordond.filmstrip.effects.filters

import dev.jordond.filmstrip.audio.AudioAttributes
import dev.jordond.filmstrip.audio.AudioEffectResolution
import dev.jordond.filmstrip.audio.AudioEffectResolver
import dev.jordond.filmstrip.audio.AudioEffectSpec
import dev.jordond.filmstrip.effects.filters.tape.TapeAudio
import dev.jordond.filmstrip.effects.filters.tape.tapeAudioResolution

/**
 * Resolves the audio effects in this module for ffmpeg filter graphs.
 */
public actual class FilterAudioEffectResolver actual constructor() : AudioEffectResolver {
  actual override fun resolve(
    spec: AudioEffectSpec,
    attributes: AudioAttributes,
  ): AudioEffectResolution? =
    when (spec) {
      is TapeAudio -> tapeAudioResolution(spec)
      else -> null
    }
}

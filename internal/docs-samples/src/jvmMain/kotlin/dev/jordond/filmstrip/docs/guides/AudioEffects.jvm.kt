package dev.jordond.filmstrip.docs.guides

import dev.jordond.filmstrip.audio.AudioEffectResolution
import dev.jordond.filmstrip.audio.PlatformAudioEffect
import dev.jordond.filmstrip.effect.FilterNode

// region filter-resolution
internal actual fun tremoloOn(spec: Tremolo): AudioEffectResolution =
  AudioEffectResolution.Resolved(
    PlatformAudioEffect(
      listOf(FilterNode("tremolo", "f" to spec.rateHz.toString(), "d" to spec.depth.toString())),
    ),
  )
// endregion

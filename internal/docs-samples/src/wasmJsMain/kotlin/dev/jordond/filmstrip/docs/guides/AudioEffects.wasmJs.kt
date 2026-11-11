package dev.jordond.filmstrip.docs.guides

import dev.jordond.filmstrip.audio.AudioEffectResolution
import dev.jordond.filmstrip.audio.PlatformAudioEffect

// region kernel-resolution
internal actual fun tremoloOn(spec: Tremolo): AudioEffectResolution =
  AudioEffectResolution.Resolved(PlatformAudioEffect { format -> TremoloKernel(format, spec) })
// endregion

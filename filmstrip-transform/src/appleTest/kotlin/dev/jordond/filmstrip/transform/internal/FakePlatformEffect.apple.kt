package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.audio.AudioKernel
import dev.jordond.filmstrip.audio.PlatformAudioEffect
import dev.jordond.filmstrip.effect.CoreImageEffect
import dev.jordond.filmstrip.effect.PlatformEffect

internal actual fun fakePlatformEffect(): PlatformEffect = PlatformEffect(CoreImageEffect { image, _ -> image })

internal actual fun fakePlatformAudioEffect(): PlatformAudioEffect = PlatformAudioEffect { SilentKernel() }

private class SilentKernel : AudioKernel() {
  override fun process(
    buffer: FloatArray,
    frames: Int,
    position: Long,
  ) = Unit
}

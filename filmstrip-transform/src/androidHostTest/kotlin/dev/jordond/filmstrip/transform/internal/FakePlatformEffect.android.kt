package dev.jordond.filmstrip.transform.internal

import androidx.media3.common.Effect
import dev.jordond.filmstrip.audio.AudioKernel
import dev.jordond.filmstrip.audio.PlatformAudioEffect
import dev.jordond.filmstrip.effect.PlatformEffect

internal actual fun fakePlatformEffect(): PlatformEffect = PlatformEffect(object : Effect {})

internal actual fun fakePlatformAudioEffect(): PlatformAudioEffect = PlatformAudioEffect { SilentKernel() }

private class SilentKernel : AudioKernel() {
  override fun process(
    buffer: FloatArray,
    frames: Int,
    position: Long,
  ) = Unit
}

package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.audio.AudioKernel
import dev.jordond.filmstrip.audio.PlatformAudioEffect
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effect.WebGlPass

internal actual fun fakePlatformEffect(): PlatformEffect = PlatformEffect(WebGlPass("test", emptyMap()))

internal actual fun fakePlatformAudioEffect(): PlatformAudioEffect = PlatformAudioEffect { SilentKernel() }

private class SilentKernel : AudioKernel() {
  override fun process(
    buffer: FloatArray,
    frames: Int,
    position: Long,
  ) = Unit
}

package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effect.WebGlPass

internal actual fun fakePlatformEffect(): PlatformEffect = PlatformEffect(WebGlPass("test", emptyMap()))

package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.effect.CoreImageEffect
import dev.jordond.filmstrip.effect.PlatformEffect

internal actual fun fakePlatformEffect(): PlatformEffect = PlatformEffect(CoreImageEffect { image, _ -> image })

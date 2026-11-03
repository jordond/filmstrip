package dev.jordond.filmstrip.transform.internal

import androidx.media3.common.Effect
import dev.jordond.filmstrip.effect.PlatformEffect

internal actual fun fakePlatformEffect(): PlatformEffect = PlatformEffect(object : Effect {})

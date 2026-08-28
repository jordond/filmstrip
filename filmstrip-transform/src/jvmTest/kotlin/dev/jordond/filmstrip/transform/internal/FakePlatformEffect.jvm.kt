package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.PlatformEffect

internal actual fun fakePlatformEffect(): PlatformEffect = PlatformEffect(FilterFragment())

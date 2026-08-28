package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.effect.PlatformEffect

/**
 * A [PlatformEffect] with no meaningful content, for a resolver that only needs to report a claim.
 */
internal expect fun fakePlatformEffect(): PlatformEffect

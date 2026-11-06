package dev.jordond.filmstrip.sample.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

/**
 * Overrides what the platform reports for reduced motion. Null reads the platform.
 */
internal val LocalReducedMotion = compositionLocalOf<Boolean?> { null }

/**
 * Whether the device asks for reduced motion. Every blink and loop in the sample stops on it.
 */
@Composable
public fun rememberReducedMotion(): Boolean = LocalReducedMotion.current ?: platformReducedMotion()

@Composable
internal expect fun platformReducedMotion(): Boolean

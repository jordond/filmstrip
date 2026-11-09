package dev.jordond.filmstrip.webcodecs

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal const val HALF_BRIGHTNESS: Float = 0.5f

internal const val SILENCE_THRESHOLD: Float = 0.01f

internal val TRIM_START: Duration = 1000.milliseconds

internal val TRIM_END: Duration = 1500.milliseconds

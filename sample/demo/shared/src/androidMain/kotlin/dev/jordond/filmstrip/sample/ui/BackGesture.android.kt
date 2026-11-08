package dev.jordond.filmstrip.sample.ui

import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.ui.Modifier

internal actual fun Modifier.excludeBackGesture(): Modifier = systemGestureExclusion()

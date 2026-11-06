package dev.jordond.filmstrip.sample.ui

import androidx.compose.runtime.Composable

// JVM desktop has no OS reduced motion setting filmstrip can read without a new dependency.
@Composable
internal actual fun platformReducedMotion(): Boolean = false

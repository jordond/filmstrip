package dev.jordond.filmstrip.sample.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.browser.window

@Composable
internal actual fun platformReducedMotion(): Boolean =
  remember { window.matchMedia("(prefers-reduced-motion: reduce)").matches }

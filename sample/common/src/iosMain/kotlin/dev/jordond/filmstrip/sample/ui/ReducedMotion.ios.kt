package dev.jordond.filmstrip.sample.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled

@Composable
internal actual fun platformReducedMotion(): Boolean = remember { UIAccessibilityIsReduceMotionEnabled() }

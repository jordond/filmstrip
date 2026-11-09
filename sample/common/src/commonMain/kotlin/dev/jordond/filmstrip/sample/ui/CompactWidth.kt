package dev.jordond.filmstrip.sample.ui

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_MEDIUM_LOWER_BOUND

/**
 * Whether the window is narrow enough that a modal belongs at the bottom edge rather than centred.
 */
@Composable
public fun isCompactWidth(): Boolean =
  !currentWindowAdaptiveInfoV2().windowSizeClass.isWidthAtLeastBreakpoint(WIDTH_DP_MEDIUM_LOWER_BOUND)

/**
 * The smallest height of a control, the chip size where there is a pointer and the touch size on a phone.
 */
@Composable
public fun controlHeight(): Dp = if (isCompactWidth()) Tokens.sizeTouch else Tokens.sizeChip

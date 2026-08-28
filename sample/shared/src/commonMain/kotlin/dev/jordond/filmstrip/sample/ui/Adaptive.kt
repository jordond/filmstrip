package dev.jordond.filmstrip.sample.ui

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_MEDIUM_LOWER_BOUND

/**
 * How the editor arranges its four regions for the window it has been given.
 */
public enum class EditorLayout {
  /**
   * Viewport, transport, timeline and tools stacked down the screen. A phone held upright.
   */
  Stacked,

  /**
   * The same order, split across a horizontal fold: viewport above the hinge, everything the user
   * touches below it.
   */
  Tabletop,

  /**
   * Tools on one side, viewport in the middle, the selected tool's controls on the other. A tablet,
   * a desktop window, an unfolded phone, or a phone on its side.
   */
  TwoPane,
}

/**
 * Picks the editor's arrangement from the window's size and posture.
 *
 * Width decides first, since the tool panel needs its own column before anything else is worth
 * rearranging. A window that is wide but short, which is a phone on its side, still takes the two
 * pane layout because stacking a viewport over a timeline there leaves room for neither.
 */
@Composable
public fun currentEditorLayout(): EditorLayout {
  val info = currentWindowAdaptiveInfoV2()
  val wide = info.windowSizeClass.isWidthAtLeastBreakpoint(WIDTH_DP_MEDIUM_LOWER_BOUND)
  return when {
    wide -> EditorLayout.TwoPane
    info.windowPosture.isTabletop -> EditorLayout.Tabletop
    else -> EditorLayout.Stacked
  }
}

/**
 * Whether the window is narrow enough that a modal belongs at the bottom edge rather than centred.
 */
@Composable
public fun isCompactWidth(): Boolean =
  !currentWindowAdaptiveInfoV2().windowSizeClass.isWidthAtLeastBreakpoint(WIDTH_DP_MEDIUM_LOWER_BOUND)

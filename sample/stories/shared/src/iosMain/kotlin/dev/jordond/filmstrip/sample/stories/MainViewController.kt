package dev.jordond.filmstrip.sample.stories

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * The story editor's root view controller, hosted by the Swift app.
 *
 * The Swift side only shows it, so everything the editor holds lives inside the composition.
 */
public fun mainViewController(): UIViewController = ComposeUIViewController { App() }

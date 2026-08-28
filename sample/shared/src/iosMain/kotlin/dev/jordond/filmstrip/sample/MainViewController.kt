package dev.jordond.filmstrip.sample

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * The sample's root view controller, hosted by the Swift app.
 *
 * The state is created once and held here rather than inside the composition, so it survives the
 * controller being torn down and rebuilt while an export is running.
 */
public fun MainViewController(): UIViewController = ComposeUIViewController { App(state) }

private val state: SampleAppState by lazy { createSampleAppState() }

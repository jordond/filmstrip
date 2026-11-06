package dev.jordond.filmstrip.sample.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties

/**
 * The properties an overlay dialog opens with, with the window behind it dimmed to the `backdrop` token where the
 * platform takes a scrim colour.
 *
 * @param dismissible Whether a back gesture or a click outside closes the dialog.
 */
internal expect fun overlayDialogProperties(dismissible: Boolean): DialogProperties

/**
 * Applies the `backdrop` token from inside a dialog, for a platform that dims its dialog windows on its own.
 */
@Composable
internal expect fun DialogScrim()

package dev.jordond.filmstrip.sample.ui.nav

import androidx.compose.ui.window.DialogProperties
import dev.jordond.filmstrip.sample.ui.Tokens

/**
 * The properties an overlay dialog opens with, with the page behind it dimmed to the `backdrop` token.
 *
 * @param dismissible Whether a back gesture or a click outside closes the dialog.
 */
internal fun overlayDialogProperties(dismissible: Boolean): DialogProperties =
  DialogProperties(
    dismissOnBackPress = dismissible,
    dismissOnClickOutside = dismissible,
    usePlatformDefaultWidth = false,
    scrimColor = Tokens.backdrop,
  )

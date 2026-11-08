package dev.jordond.filmstrip.sample.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

internal actual fun overlayDialogProperties(dismissible: Boolean): DialogProperties =
  DialogProperties(
    dismissOnBackPress = dismissible,
    dismissOnClickOutside = dismissible,
    usePlatformDefaultWidth = false,
  )

/**
 * Sets how far the dialog window dims what is behind it, by [dialogDimAmount].
 */
@Composable
internal actual fun DialogScrim() {
  val window = (LocalView.current.parent as? DialogWindowProvider)?.window
  SideEffect { window?.setDimAmount(dialogDimAmount()) }
}

package dev.jordond.filmstrip.sample.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties
import dev.jordond.filmstrip.sample.ui.Tokens

internal actual fun overlayDialogProperties(dismissible: Boolean): DialogProperties =
  DialogProperties(
    dismissOnBackPress = dismissible,
    dismissOnClickOutside = dismissible,
    usePlatformDefaultWidth = false,
    scrimColor = Tokens.backdrop,
  )

@Composable
internal actual fun DialogScrim() = Unit

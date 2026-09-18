package dev.jordond.filmstrip.filekit.compose

import androidx.compose.runtime.Composable
import io.github.vinceglb.filekit.dialogs.FileKitDialogException

// A browser export is a blob url with no file behind it, so there is nothing to share.
@Composable
public actual fun rememberShareLauncher(onError: (FileKitDialogException) -> Unit): ShareLauncher? = null

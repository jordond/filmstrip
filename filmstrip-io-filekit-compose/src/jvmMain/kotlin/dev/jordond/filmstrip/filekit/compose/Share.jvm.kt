package dev.jordond.filmstrip.filekit.compose

import androidx.compose.runtime.Composable
import io.github.vinceglb.filekit.dialogs.FileKitDialogException

// Desktop has no share sheet. A caller opens the file instead.
@Composable
public actual fun rememberShareLauncher(onError: (FileKitDialogException) -> Unit): ShareLauncher? = null

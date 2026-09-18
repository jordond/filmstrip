package dev.jordond.filmstrip.filekit.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.jordond.filmstrip.filekit.toMediaSink
import dev.jordond.filmstrip.media.MediaSink
import io.github.vinceglb.filekit.dialogs.FileKitDialogException
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.compose.rememberFileSaverLauncher

@Composable
public actual fun rememberExportSaverLauncher(
  dialogSettings: FileKitDialogSettings,
  onError: (FileKitDialogException) -> Unit,
  onResult: (MediaSink?) -> Unit,
): ExportSaverLauncher {
  val launcher =
    rememberFileSaverLauncher(
      dialogSettings = dialogSettings,
      onError = onError,
      onResult = { file -> onResult(file?.toMediaSink()) },
    )

  return remember(launcher) {
    ExportSaverLauncher { suggestedName, defaultExtension, directory ->
      launcher.launch(
        suggestedName = suggestedName,
        defaultExtension = defaultExtension,
        directory = directory,
      )
    }
  }
}

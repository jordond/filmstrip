package dev.jordond.filmstrip.filekit.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import dev.jordond.filmstrip.media.MediaSink
import io.github.vinceglb.filekit.dialogs.FileKitDialogException
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings

// A browser has no save dialog: an export to a path sink is a download under that name, so the
// launch arguments are the whole answer and nothing is asked of the user.
@Composable
public actual fun rememberExportSaverLauncher(
  dialogSettings: FileKitDialogSettings,
  onError: (FileKitDialogException) -> Unit,
  onResult: (MediaSink?) -> Unit,
): ExportSaverLauncher {
  val currentOnResult by rememberUpdatedState(onResult)

  return remember {
    ExportSaverLauncher { suggestedName, defaultExtension, _ ->
      currentOnResult(downloadSink(suggestedName, defaultExtension))
    }
  }
}

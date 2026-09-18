package dev.jordond.filmstrip.filekit.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.jordond.filmstrip.filekit.toPlatformFile
import dev.jordond.filmstrip.media.MediaSink
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitDialogException
import io.github.vinceglb.filekit.dialogs.compose.ShareResultLauncher
import io.github.vinceglb.filekit.dialogs.compose.rememberShareFileLauncher

/**
 * Shares whatever an export wrote, on the arm it reported, for a caller already holding FileKit's launcher.
 *
 * @throws IllegalStateException on [MediaSink.Temporary], which is a request rather than a
 *   location. Read the resolved path off `ExportStatus.Success.output` instead.
 */
public fun ShareResultLauncher.launch(output: MediaSink): Unit = launch(output.toPlatformFile())

@Composable
public actual fun rememberShareLauncher(onError: (FileKitDialogException) -> Unit): ShareLauncher? {
  val launcher = rememberShareFileLauncher(onError = onError)

  return remember(launcher) {
    object : ShareLauncher {
      override fun launch(file: PlatformFile) = launcher.launch(file)

      override fun launch(output: MediaSink) = launcher.launch(output)
    }
  }
}

package dev.jordond.filmstrip.sample

import androidx.compose.runtime.Composable
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.compose.rememberShareFileLauncher

@Composable
public actual fun rememberExportSharer(): ((path: String) -> Unit)? {
  val launcher = rememberShareFileLauncher()
  return { path -> launcher.launch(PlatformFile(path)) }
}

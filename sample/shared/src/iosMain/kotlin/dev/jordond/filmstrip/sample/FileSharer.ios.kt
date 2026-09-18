package dev.jordond.filmstrip.sample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.jordond.filmstrip.filekit.toPlatformFile
import dev.jordond.filmstrip.media.MediaSink
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.compose.rememberShareFileLauncher

@Composable
public actual fun rememberFileSharer(): FileSharer? {
  val launcher = rememberShareFileLauncher()

  return remember(launcher) {
    object : FileSharer {
      override fun share(file: PlatformFile) = launcher.launch(file)

      override fun share(output: MediaSink) = share(output.toPlatformFile())
    }
  }
}

package dev.jordond.filmstrip.filekit.compose

import androidx.compose.runtime.Composable
import dev.jordond.filmstrip.media.MediaSink
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitDialogException

/**
 * Hands a file to the platform's share sheet.
 *
 * Takes the sink an export reported, so a content uri destination reaches the sheet as the uri it
 * is.
 */
public interface ShareLauncher {
  /**
   * Shares a file that is already resolved, such as one a picker or a saver handed back.
   */
  public fun launch(file: PlatformFile)

  /**
   * Shares whatever an export wrote, on the arm it reported.
   *
   * @throws IllegalStateException on [MediaSink.Temporary], which is a request rather than a
   *   location. Read the resolved path off `ExportStatus.Success.output` instead.
   * @throws IllegalArgumentException on a [MediaSink.Uri] this platform cannot parse.
   */
  public fun launch(output: MediaSink)
}

/**
 * Remembers the share sheet for this platform.
 *
 * Null on every target FileKit has no share sheet for, which is the JVM and the browser. A caller
 * hides its share control rather than showing one that cannot do anything.
 *
 * @param onError Called when a share that started cannot finish.
 */
@Composable
public expect fun rememberShareLauncher(onError: (FileKitDialogException) -> Unit = {}): ShareLauncher?

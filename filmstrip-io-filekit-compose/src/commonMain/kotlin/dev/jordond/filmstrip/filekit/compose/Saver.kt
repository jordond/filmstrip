package dev.jordond.filmstrip.filekit.compose

import androidx.compose.runtime.Composable
import dev.jordond.filmstrip.media.MediaSink
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitDialogException
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings

/**
 * Asks for the destination a [rememberExportSaverLauncher] call reports back on.
 */
public class ExportSaverLauncher internal constructor(
  private val onLaunch: (String, String?, PlatformFile?) -> Unit,
) {
  /**
   * Opens the save dialog, or on a browser reports the download name straight back.
   *
   * A [suggestedName] that already ends in [defaultExtension] gets it a second time, the same way
   * FileKit's own saver joins the two.
   *
   * @param suggestedName The name the dialog starts on, without an extension.
   * @param defaultExtension The extension joined onto [suggestedName], left off when null.
   * @param directory Where the dialog opens, on the targets that honour it.
   */
  public fun launch(
    suggestedName: String,
    defaultExtension: String? = "mp4",
    directory: PlatformFile? = null,
  ) {
    onLaunch(suggestedName, defaultExtension, directory)
  }
}

/**
 * Remembers a saver that hands an export destination back as a [MediaSink].
 *
 * Android, Apple and the JVM open the platform's save dialog and report where the user chose. A
 * browser opens nothing and reports a [MediaSink.Path] named after the launch arguments at once,
 * which is the name the engine downloads under.
 *
 * An iOS dialog can hand back a security-scoped URL, so the caller holds `withScopedAccess` open
 * for the life of the export.
 *
 * @param dialogSettings FileKit's per-platform dialog settings.
 * @param onError Called when a save that started cannot finish. Dismissal is not an error.
 * @param onResult Called with the destination, or with null when the dialog was dismissed.
 */
@Composable
public expect fun rememberExportSaverLauncher(
  dialogSettings: FileKitDialogSettings = FileKitDialogSettings.createDefault(),
  onError: (FileKitDialogException) -> Unit = {},
  onResult: (MediaSink?) -> Unit,
): ExportSaverLauncher

/**
 * The name a browser downloads under, which is the launch arguments joined the way FileKit joins
 * them.
 */
internal fun downloadSink(
  suggestedName: String,
  defaultExtension: String?,
): MediaSink =
  if (defaultExtension.isNullOrBlank()) {
    MediaSink.of(suggestedName)
  } else {
    MediaSink.of("$suggestedName.$defaultExtension")
  }

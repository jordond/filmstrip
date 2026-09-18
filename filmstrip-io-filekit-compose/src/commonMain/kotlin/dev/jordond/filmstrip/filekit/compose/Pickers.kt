package dev.jordond.filmstrip.filekit.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.filekit.toImageSource
import dev.jordond.filmstrip.filekit.toMediaSource
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitPickerException
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.PickerResultLauncher
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher

/**
 * A picked clip, as both the file the dialog returned and the source an export reads it through.
 *
 * A [MediaSource] carries no display name, and on a browser its object URL is not a handle on the
 * picked file, so the picker hands both back. Reading the file's `name` goes through the content
 * resolver on Android, so read it once in the callback and hold the result rather than reading it
 * during composition.
 *
 * @property file What the picker handed back.
 * @property source The same file on whichever [MediaSource] arm this target's backend reads.
 */
@Immutable
@Poko
public class PickedMedia internal constructor(
  public val file: PlatformFile,
  public val source: MediaSource,
)

/**
 * A picked still, as both the file the dialog returned and the source an effect reads it through.
 *
 * Carries the file for the same reasons [PickedMedia] does, the name a caller labels a watermark
 * with among them.
 *
 * @property file What the picker handed back.
 * @property source The same file on whichever [ImageSource] arm this target's backend reads.
 */
@Immutable
@Poko
public class PickedImage internal constructor(
  public val file: PlatformFile,
  public val source: ImageSource,
)

/**
 * Remembers a picker that hands a clip back as a [MediaSource].
 *
 * Single selection only. The conversion runs once per pick, which matters in a browser where it
 * mints an object URL that the caller then owns revoking.
 *
 * @param type What the dialog offers, video by default.
 * @param directory Where the dialog opens, on the targets that honour it.
 * @param dialogSettings FileKit's per-platform dialog settings.
 * @param onError Called when a pick that started cannot finish. Dismissal is not an error.
 * @param onResult Called with the pick, or with null when the dialog was dismissed.
 */
@Composable
public fun rememberMediaPickerLauncher(
  type: FileKitType = FileKitType.Video,
  directory: PlatformFile? = null,
  dialogSettings: FileKitDialogSettings = FileKitDialogSettings.createDefault(),
  onError: (FileKitPickerException) -> Unit = {},
  onResult: (PickedMedia?) -> Unit,
): PickerResultLauncher =
  rememberFilePickerLauncher(
    type = type,
    directory = directory,
    dialogSettings = dialogSettings,
    onError = onError,
    onResult = { file -> onResult(file?.let { PickedMedia(it, it.toMediaSource()) }) },
  )

/**
 * Remembers a picker that hands a still back as an [ImageSource], for a watermark or a title card.
 *
 * Behaves the way [rememberMediaPickerLauncher] does, down to minting the browser's object URL
 * once per pick.
 *
 * @param type What the dialog offers, images by default.
 * @param directory Where the dialog opens, on the targets that honour it.
 * @param dialogSettings FileKit's per-platform dialog settings.
 * @param onError Called when a pick that started cannot finish. Dismissal is not an error.
 * @param onResult Called with the pick, or with null when the dialog was dismissed.
 */
@Composable
public fun rememberImagePickerLauncher(
  type: FileKitType = FileKitType.Image,
  directory: PlatformFile? = null,
  dialogSettings: FileKitDialogSettings = FileKitDialogSettings.createDefault(),
  onError: (FileKitPickerException) -> Unit = {},
  onResult: (PickedImage?) -> Unit,
): PickerResultLauncher =
  rememberFilePickerLauncher(
    type = type,
    directory = directory,
    dialogSettings = dialogSettings,
    onError = onError,
    onResult = { file -> onResult(file?.let { PickedImage(it, it.toImageSource()) }) },
  )

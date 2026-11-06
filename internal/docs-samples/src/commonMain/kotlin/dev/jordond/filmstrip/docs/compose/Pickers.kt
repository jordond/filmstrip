package dev.jordond.filmstrip.docs.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.jordond.filmstrip.filekit.compose.rememberExportSaverLauncher
import dev.jordond.filmstrip.filekit.compose.rememberImagePickerLauncher
import dev.jordond.filmstrip.filekit.compose.rememberMediaPickerLauncher
import dev.jordond.filmstrip.filekit.compose.rememberShareLauncher
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource

@Composable
internal fun PickClip() {
  // region media-picker
  var source by remember { mutableStateOf<MediaSource?>(null) }
  val picker =
    rememberMediaPickerLauncher { picked ->
      source = picked?.source
    }

  BasicText("Pick a clip", Modifier.clickable { picker.launch() })
  // endregion
}

@Composable
internal fun PickWatermark() {
  // region image-picker
  var watermark by remember { mutableStateOf<ImageSource?>(null) }
  val picker =
    rememberImagePickerLauncher { picked ->
      watermark = picked?.source
    }

  BasicText("Pick a watermark", Modifier.clickable { picker.launch() })
  // endregion
}

@Composable
internal fun SaveExport() {
  // region saver
  var destination by remember { mutableStateOf<MediaSink?>(null) }
  val saver =
    rememberExportSaverLauncher { sink ->
      destination = sink
    }

  BasicText("Export", Modifier.clickable { saver.launch(suggestedName = "holiday", defaultExtension = "mp4") })
  // endregion
}

@Composable
internal fun ShareExport(output: MediaSink) {
  // region share
  val share = rememberShareLauncher()

  if (share != null) {
    BasicText("Share", Modifier.clickable { share.launch(output) })
  }
  // endregion
}

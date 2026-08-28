package dev.jordond.filmstrip.sample

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.scene.DialogSceneStrategy
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.ui.NavDisplay
import dev.jordond.filmstrip.sample.ui.SampleTheme
import dev.jordond.filmstrip.sample.ui.isCompactWidth
import dev.jordond.filmstrip.sample.ui.nav.BottomSheetSceneStrategy
import dev.jordond.filmstrip.sample.ui.nav.OverlayContainer
import dev.jordond.filmstrip.sample.ui.nav.SheetProperties
import dev.jordond.filmstrip.sample.ui.screen.CapabilitiesPane
import dev.jordond.filmstrip.sample.ui.screen.DiagnosticsPane
import dev.jordond.filmstrip.sample.ui.screen.EditorScreen
import dev.jordond.filmstrip.sample.ui.screen.ExportPane
import dev.jordond.filmstrip.sample.ui.screen.StartScreen
import dev.jordond.filmstrip.sample.ui.screen.ResultScreen
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name

/**
 * The sample: an editor over one clip, and everywhere it can send you.
 *
 * The editor is the only root. It shows the picker and the device's capability report until a clip
 * is loaded, so there is nothing to get past before a session starts.
 *
 * Navigation is a back stack of [SampleRoute] keys rendered by `NavDisplay`. Export and
 * capabilities are entries like any other, so the system back gesture closes them and the window's
 * width decides whether they arrive as a bottom sheet or a dialog.
 */
@Composable
public fun App(state: SampleAppState) {
  SampleTheme {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      val picker = rememberFilePickerLauncher(
        type = FileKitType.Video,
        onError = { state.onPickFailed(it.message) },
        onResult = { file -> state.onPicked(file?.toMediaSource(), file?.name.orEmpty()) },
      )

      val compact = isCompactWidth()
      val sheetStrategy = remember { BottomSheetSceneStrategy<SampleRoute>() }
      val dialogStrategy = remember { DialogSceneStrategy<SampleRoute>() }

      NavDisplay(
        backStack = state.backStack,
        onBack = state::navigateBack,
        sceneStrategies = listOf(sheetStrategy, dialogStrategy, SinglePaneSceneStrategy()),
        entryProvider = entryProvider {
          entry<SampleRoute.Editor> {
            if (state.source == null) {
              StartScreen(state, onImport = picker::launch)
            } else {
              EditorScreen(state)
            }
          }

          entry<SampleRoute.Export>(
            metadata = overlayMetadata(compact, dismissible = !state.exporting),
          ) {
            OverlayContainer(compact) { ExportPane(state) }
          }

          entry<SampleRoute.Capabilities>(
            metadata = overlayMetadata(compact, dismissible = true),
          ) {
            OverlayContainer(compact) { CapabilitiesPane(state) }
          }

          entry<SampleRoute.Result> {
            ResultScreen(state)
          }

          entry<SampleRoute.Diagnostics>(
            metadata = overlayMetadata(compact, dismissible = true),
          ) {
            OverlayContainer(compact) { DiagnosticsPane(state) }
          }
        },
      )
    }
  }
}

/**
 * A sheet at the bottom edge where the window is narrow, a centred dialog where it is not.
 */
private fun overlayMetadata(compact: Boolean, dismissible: Boolean): Map<String, Any> =
  if (compact) {
    BottomSheetSceneStrategy.sheet(SheetProperties(dismissOnBack = dismissible))
  } else {
    DialogSceneStrategy.dialog(
      DialogProperties(
        dismissOnBackPress = dismissible,
        dismissOnClickOutside = dismissible,
        usePlatformDefaultWidth = false,
      ),
    )
  }

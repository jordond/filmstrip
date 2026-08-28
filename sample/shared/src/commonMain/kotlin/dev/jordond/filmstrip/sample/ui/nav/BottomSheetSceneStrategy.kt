package dev.jordond.filmstrip.sample.ui.nav

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavMetadataKey
import androidx.navigation3.runtime.get
import androidx.navigation3.runtime.metadata
import androidx.navigation3.scene.OverlayScene
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import dev.jordond.filmstrip.sample.ui.nav.BottomSheetSceneStrategy.Companion.sheet

/**
 * Shows an entry marked with [sheet] in a modal bottom sheet over the entry behind it.
 *
 * Navigation 3 ships a dialog scene and nothing else, so this is the other half of the pair: on a
 * narrow window the same destination that would be a centred dialog arrives at the bottom edge,
 * within reach of a thumb. Pair it with `DialogSceneStrategy` and pick between the two metadata
 * builders by window width.
 */
public class BottomSheetSceneStrategy<T : Any> : SceneStrategy<T> {

  override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T>? {
    val entry = entries.lastOrNull() ?: return null
    val properties = entry.metadata[SheetKey] ?: return null
    return BottomSheetScene(
      key = entry.contentKey,
      entry = entry,
      previousEntries = entries.dropLast(1),
      overlaidEntries = entries.dropLast(1),
      properties = properties,
      onBack = onBack,
    )
  }

  public companion object {
    private object SheetKey : NavMetadataKey<SheetProperties>

    /**
     * Marks an entry to be shown as a bottom sheet.
     *
     * @param properties How the sheet behaves once it is up.
     */
    public fun sheet(properties: SheetProperties = SheetProperties()): Map<String, Any> =
      metadata { put(SheetKey, properties) }
  }
}

/**
 * How a sheet scene behaves.
 *
 * @property skipPartiallyExpanded Whether the sheet opens straight to full height instead of
 *   stopping half way.
 * @property dismissOnBack Whether a back gesture or a tap outside closes the sheet. An export that
 *   is already running sets this false, so the run cannot be dismissed out from under itself.
 */
public class SheetProperties(
  public val skipPartiallyExpanded: Boolean = true,
  public val dismissOnBack: Boolean = true,
)

@OptIn(ExperimentalMaterial3Api::class)
private class BottomSheetScene<T : Any>(
  override val key: Any,
  private val entry: NavEntry<T>,
  override val previousEntries: List<NavEntry<T>>,
  override val overlaidEntries: List<NavEntry<T>>,
  private val properties: SheetProperties,
  private val onBack: () -> Unit,
) : OverlayScene<T> {

  override val entries: List<NavEntry<T>> = listOf(entry)

  override val content: @Composable () -> Unit = {
    ModalBottomSheet(
      onDismissRequest = { if (properties.dismissOnBack) onBack() },
      sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = properties.skipPartiallyExpanded,
        confirmValueChange = { properties.dismissOnBack },
      ),
      containerColor = MaterialTheme.colorScheme.surface,
      contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
      entry.Content()
    }
  }
}

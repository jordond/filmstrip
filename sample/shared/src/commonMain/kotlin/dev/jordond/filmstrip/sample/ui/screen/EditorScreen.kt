package dev.jordond.filmstrip.sample.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.sample.SampleAppState
import dev.jordond.filmstrip.sample.ui.EditorLayout
import dev.jordond.filmstrip.sample.ui.SampleIcons
import dev.jordond.filmstrip.sample.ui.asClock
import dev.jordond.filmstrip.sample.ui.currentEditorLayout
import kotlin.time.Duration.Companion.seconds

private val InspectorWidth = 340.dp

/**
 * The editor.
 *
 * Three arrangements of the same four regions, picked by [currentEditorLayout]: stacked down a
 * phone, split across a horizontal fold, or spread across a wide window with the tools in a rail
 * and the selected tool's controls in their own column.
 */
@Composable
public fun EditorScreen(
  state: SampleAppState,
  modifier: Modifier = Modifier,
) {
  val layout = currentEditorLayout()

  // Hoisted above the layout arrangement below, so switching between them (a fold, an unfold, a
  // window resize) only moves the viewport rather than tearing its player down and losing the
  // playhead. Restarts once a pick's probe has settled rather than on every step of it, so a probe
  // still in flight never opens a player the next step immediately replaces.
  LaunchedEffect(state.source, state.probing) {
    if (!state.probing) state.startPreview()
  }
  DisposableEffect(Unit) { onDispose { state.stopPreview() } }

  Scaffold(
    modifier = modifier,
    topBar = { EditorTopBar(state) },
    containerColor = MaterialTheme.colorScheme.background,
    contentColor = MaterialTheme.colorScheme.onSurface,
  ) { insets ->
    Box(Modifier.fillMaxSize().padding(insets)) {
      when (layout) {
        EditorLayout.TwoPane -> TwoPaneEditor(state)
        EditorLayout.Tabletop -> StackedEditor(state, splitEvenly = true)
        EditorLayout.Stacked -> StackedEditor(state, splitEvenly = false)
      }
    }
  }
}

/**
 * Viewport on top, then transport, timeline and tools, which is the order every phone editor lands
 * on because the frame has to stay visible while a control under the thumb is being dragged.
 *
 * @param splitEvenly Give the viewport exactly half the height, which on a folded-flat device puts
 *   it above the hinge and everything the user touches below it.
 */
@Composable
private fun StackedEditor(
  state: SampleAppState,
  splitEvenly: Boolean,
) {
  // The keyboard leaves nothing like enough room for a viewport and a timeline, so the editor drops
  // to the tool it is being typed into and puts everything back when the keyboard goes away.
  val typing = WindowInsets.ime.getBottom(LocalDensity.current) > 0

  Column(Modifier.fillMaxSize()) {
    if (!typing) {
      PreviewStage(state, Modifier.fillMaxWidth().weight(1f))
      PlaybackControls(state)
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

    Surface(
      color = MaterialTheme.colorScheme.surface,
      modifier = if (typing || splitEvenly) Modifier.weight(1f) else Modifier,
    ) {
      Column {
        ToolRail(state)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        ToolPanel(
          state = state,
          modifier = when {
            typing || splitEvenly -> Modifier.weight(1f)
            else -> Modifier.heightIn(min = 150.dp, max = 260.dp)
          },
        )
      }
    }
  }
}

/**
 * Tools down one edge, viewport in the middle, the selected tool's controls in a column of their
 * own. Nothing has to be dismissed to see the frame it is changing.
 */
@Composable
private fun TwoPaneEditor(state: SampleAppState) {
  Row(Modifier.fillMaxSize()) {
    ToolRail(state, vertical = true)
    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)

    Column(Modifier.weight(1f).fillMaxHeight()) {
      PreviewStage(state, Modifier.fillMaxWidth().weight(1f))
      PlaybackControls(state)
    }

    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Surface(
      color = MaterialTheme.colorScheme.surface,
      modifier = Modifier.width(InspectorWidth).fillMaxHeight(),
    ) {
      ToolPanel(state, Modifier.fillMaxHeight())
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorTopBar(state: SampleAppState) {
  TopAppBar(
    title = {
      Column {
        Text(
          text = state.sourceLabel.ifBlank { "Untitled" },
          style = MaterialTheme.typography.titleSmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = state.sourceSummary(),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.outline,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    },
    navigationIcon = {
      IconButton(onClick = state::closeProject) {
        Icon(SampleIcons.Close, contentDescription = "Close the project")
      }
    },
    actions = {
      IconButton(onClick = state::openCapabilities) {
        Icon(SampleIcons.Info, contentDescription = "Device capabilities")
      }
      IconButton(onClick = state::openDiagnostics) {
        Icon(SampleIcons.Warning, contentDescription = "Diagnostics")
      }
      Button(
        onClick = state::openExport,
        enabled = !state.exporting,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        modifier = Modifier.padding(end = 8.dp),
      ) {
        Icon(SampleIcons.Export, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("Export", style = MaterialTheme.typography.labelLarge)
      }
    },
    colors = TopAppBarDefaults.topAppBarColors(
      containerColor = MaterialTheme.colorScheme.background,
      titleContentColor = MaterialTheme.colorScheme.onSurface,
      navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
      actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ),
  )
}

/**
 * The transport and the timeline, which always travel together.
 */
@Composable
private fun PlaybackControls(
  state: SampleAppState,
  modifier: Modifier = Modifier,
) {
  Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
    Column {
      Transport(state)
      Timeline(state, Modifier.padding(horizontal = 12.dp))
      Spacer(Modifier.height(10.dp))
    }
  }
}

@Composable
private fun Transport(
  state: SampleAppState,
  modifier: Modifier = Modifier,
) {
  val position = state.positionSeconds.toDouble().seconds

  Row(
    modifier = modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    IconButton(onClick = { state.stepFrames(-1) }) {
      Icon(SampleIcons.StepBack, contentDescription = "Previous frame")
    }
    FilledIconButton(onClick = state::togglePlay) {
      Icon(
        imageVector = if (state.playing) SampleIcons.Pause else SampleIcons.Play,
        contentDescription = if (state.playing) "Pause" else "Play",
      )
    }
    IconButton(onClick = { state.stepFrames(1) }) {
      Icon(SampleIcons.StepForward, contentDescription = "Next frame")
    }

    Spacer(Modifier.width(10.dp))

    Text(position.asClock(), style = MaterialTheme.typography.labelLarge)
    Text(
      text = " / ${state.editedDuration.asClock()}",
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.outline,
    )

    Spacer(Modifier.weight(1f))

    IconButton(
      onClick = { state.looping = !state.looping },
      colors = IconButtonDefaults.iconButtonColors(
        contentColor = if (state.looping) {
          MaterialTheme.colorScheme.primary
        } else {
          MaterialTheme.colorScheme.outline
        },
      ),
    ) {
      Icon(SampleIcons.Loop, contentDescription = "Loop")
    }
  }
}

private fun SampleAppState.sourceSummary(): String {
  val video = info?.video ?: return if (probing) "Reading the file" else "No video track"
  val size = "${video.displaySize.width} x ${video.displaySize.height}"
  val fps = video.frameRate?.let { "${it.toInt()} fps" }
  val hdr = if (video.hdrTransfer != null) "HDR" else null
  return listOfNotNull(size, video.codec.name, fps, hdr, info?.duration?.asClock()).joinToString(" · ")
}

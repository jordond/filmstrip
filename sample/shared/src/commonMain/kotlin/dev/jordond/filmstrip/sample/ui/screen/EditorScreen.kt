package dev.jordond.filmstrip.sample.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jordond.filmstrip.sample.SampleAppState
import dev.jordond.filmstrip.sample.ui.EditorLayout
import dev.jordond.filmstrip.sample.ui.IconKey
import dev.jordond.filmstrip.sample.ui.Key
import dev.jordond.filmstrip.sample.ui.LocalScreenInsets
import dev.jordond.filmstrip.sample.ui.SampleIcons
import dev.jordond.filmstrip.sample.ui.Tokens
import dev.jordond.filmstrip.sample.ui.asClock
import dev.jordond.filmstrip.sample.ui.bottomLine
import dev.jordond.filmstrip.sample.ui.currentEditorLayout
import dev.jordond.filmstrip.sample.ui.excludeBackGesture
import dev.jordond.filmstrip.sample.ui.focusRing
import dev.jordond.filmstrip.sample.ui.isCompactWidth
import dev.jordond.filmstrip.sample.ui.scanlinesFaint
import kotlin.time.Duration.Companion.seconds

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
    topBar = { EditorTopBar(state, wide = layout == EditorLayout.TwoPane) },
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

  val cornerRoom = LocalScreenInsets.current.bottom

  BoxWithConstraints(Modifier.fillMaxSize()) {
    val panelMax = (maxHeight * PANEL_SHARE).coerceIn(PANEL_MIN, PANEL_MAX)
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
        Column(Modifier.padding(bottom = cornerRoom)) {
          ToolRail(state)
          HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
          ToolPanel(
            state = state,
            modifier = when {
              typing || splitEvenly -> Modifier.weight(1f)
              else -> Modifier.heightIn(min = PANEL_MIN, max = panelMax)
            },
          )
        }
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
  val cornerRoom = LocalScreenInsets.current.bottom

  Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
    ToolRail(state, Modifier.padding(bottom = cornerRoom), vertical = true)
    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)

    Column(Modifier.weight(1f).fillMaxHeight()) {
      PreviewStage(state, Modifier.fillMaxWidth().weight(1f))
      PlaybackControls(state)
    }

    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Surface(
      color = MaterialTheme.colorScheme.surface,
      modifier = Modifier.width(Tokens.sizeInspector).fillMaxHeight(),
    ) {
      ToolPanel(state, Modifier.fillMaxHeight().padding(bottom = cornerRoom), titled = true)
    }
  }
}

/**
 * The strip across the top of the editor, on a wide window also the deck's status beside the file.
 */
@Composable
private fun EditorTopBar(
  state: SampleAppState,
  wide: Boolean,
) {
  val colors = MaterialTheme.colorScheme
  val insets = LocalScreenInsets.current
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(colors.background)
      .scanlinesFaint()
      .bottomLine(colors.outlineVariant)
      .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
      .padding(start = insets.side, end = insets.side, top = insets.top)
      .heightIn(min = if (wide) 64.dp else 56.dp)
      .padding(start = 4.dp, end = 12.dp),
    horizontalArrangement = Arrangement.spacedBy(if (wide) 8.dp else 2.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IconKey(SampleIcons.Close, "Close the project", state::closeProject)
    if (wide) DeckStatus(state)
    Column(Modifier.weight(1f)) {
      Text(
        text = state.sourceLabel.ifBlank { "Untitled" },
        style = MaterialTheme.typography.titleSmall,
        color = colors.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = state.sourceSummary(),
        style = MaterialTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    IconKey(SampleIcons.Info, "Device capabilities", state::openCapabilities, iconSize = 22.dp)
    IconKey(SampleIcons.Warning, "Diagnostics", state::openDiagnostics, iconSize = 22.dp)
    Key(
      label = "EXPORT",
      onClick = state::openExport,
      enabled = !state.exporting,
      icon = SampleIcons.Record,
      minHeight = Tokens.sizeTouch,
      horizontalPadding = if (wide) 20.dp else 12.dp,
    )
  }
}

@Composable
private fun DeckStatus(state: SampleAppState) {
  val style = MaterialTheme.typography.labelLarge
  val colors = MaterialTheme.colorScheme
  Row(
    modifier = Modifier.widthIn(min = DECK_WIDTH.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(SampleIcons.Play, contentDescription = null, tint = colors.primary, modifier = Modifier.size(16.dp))
    Text(if (state.playing && state.canPlay) "PLAY" else "EDIT", style = style, color = colors.primary)
    Text("SP", style = style, color = colors.onSurfaceVariant)
  }
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
      Timeline(state, Modifier.excludeBackGesture().padding(horizontal = 12.dp))
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
  val compact = isCompactWidth()
  val clock = MaterialTheme.typography.labelLarge.copy(fontSize = if (compact) 20.sp else 24.sp)
  val total = state.editedDuration.asClock().let { if (compact) it.substringBeforeLast('.') else it }

  Row(
    modifier = modifier.fillMaxWidth().height(Tokens.sizeTransport).padding(horizontal = if (compact) 8.dp else 12.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp),
  ) {
    IconKey(
      icon = SampleIcons.StepBack,
      contentDescription = "Previous frame",
      onClick = { state.stepFrames(-1) },
      enabled = state.canPlay,
      tint = Tokens.mutedStrong,
    )
    PlayKey(state)
    IconKey(
      icon = SampleIcons.StepForward,
      contentDescription = "Next frame",
      onClick = { state.stepFrames(1) },
      enabled = state.canPlay,
      tint = Tokens.mutedStrong,
    )

    Spacer(Modifier.width(if (compact) 4.dp else 8.dp))

    Text(position.asClock(), style = clock, maxLines = 1)
    Text(
      text = " / $total",
      style = clock,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
    )

    Spacer(Modifier.weight(1f))

    IconKey(
      icon = SampleIcons.Loop,
      contentDescription = "Loop",
      onClick = { state.looping = !state.looping },
      tint = if (state.looping) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
      iconSize = 22.dp,
    )
  }
}

@Composable
private fun PlayKey(state: SampleAppState) {
  val interaction = remember { MutableInteractionSource() }
  val colors = MaterialTheme.colorScheme
  val running = state.playing && state.canPlay
  val enabled = state.canPlay
  Box(
    modifier = Modifier
      .focusRing(interaction)
      .size(width = PLAY_KEY_WIDTH.dp, height = Tokens.sizeTouch)
      .background(if (enabled) colors.primary else colors.outline)
      .clickable(
        enabled = enabled,
        role = Role.Button,
        interactionSource = interaction,
        indication = null,
        onClick = state::togglePlay,
      ),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = if (running) SampleIcons.Pause else SampleIcons.Play,
      contentDescription = if (running) "Pause" else "Play",
      tint = if (enabled) colors.onPrimary else colors.onSurfaceVariant,
    )
  }
}

private fun SampleAppState.sourceSummary(): String {
  if (source == null) return ""
  if (sourceFailure != null) return "Can't read this file"
  val video = info?.video ?: return if (probing) "Reading the file" else "No video track"
  val size = "${video.displaySize.width} x ${video.displaySize.height}"
  val fps = video.frameRate?.let { "${it.toInt()} fps" }
  val hdr = if (video.hdrTransfer != null) "HDR" else null
  return listOfNotNull(size, video.codec.name, fps, hdr, info?.duration?.asClock()).joinToString(" · ")
}

private const val PANEL_SHARE = 0.3f
private val PANEL_MIN = 150.dp
private val PANEL_MAX = 260.dp
private const val DECK_WIDTH = 104
private const val PLAY_KEY_WIDTH = 52

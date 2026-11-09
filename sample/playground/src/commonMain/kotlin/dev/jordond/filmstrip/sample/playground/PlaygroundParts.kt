package dev.jordond.filmstrip.sample.playground

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.compose.VideoSurface
import dev.jordond.filmstrip.compose.rememberFilmstripPlayer
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.sample.SamplePreset
import dev.jordond.filmstrip.sample.loadPreset
import dev.jordond.filmstrip.sample.ui.Tokens
import dev.jordond.filmstrip.sample.ui.controlHeight
import dev.jordond.filmstrip.sample.ui.focusRing
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration

/**
 * The line above a playground's picture: PREVIEW or ORIGINAL with its LED, then [middle] in amber and [output] in
 * coral.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StatusLine(
  compare: Boolean,
  middle: String,
  output: String,
) {
  FlowRow(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Led(if (compare) Tokens.muted else Tokens.cyan)
      Text(
        if (compare) "ORIGINAL" else "PREVIEW",
        style = MaterialTheme.typography.labelMedium,
        color = if (compare) Tokens.mutedStrong else Tokens.cyan,
      )
    }
    Text(middle, style = MaterialTheme.typography.labelMedium, color = Tokens.amber)
    Text(output, style = MaterialTheme.typography.labelMedium, color = Tokens.coral)
  }
}

/**
 * A 16:9 picture that fetches [clip], then plays [edit] of it muted on a loop with a PLAY / PAUSE key. A failed fetch
 * says why and offers RETRY.
 */
@Composable
internal fun Picture(
  filmstrip: Filmstrip,
  clip: SamplePreset,
  edit: (MediaSource) -> Composition,
) {
  var attempt by remember { mutableIntStateOf(0) }
  var source by remember { mutableStateOf<MediaSource?>(null) }
  var failure by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(clip, attempt) {
    failure = null
    try {
      source = loadPreset(clip)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Throwable) {
      failure = error.message ?: "The clip did not load"
    }
  }

  Box(
    modifier =
      Modifier
        .fillMaxWidth()
        .aspectRatio(16f / 9f)
        .background(Tokens.screen)
        .border(1.dp, Tokens.line),
    contentAlignment = Alignment.Center,
  ) {
    val loaded = source
    when {
      loaded != null -> {
        Playing(filmstrip, clip, edit(loaded))
      }
      failure != null -> {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(failure.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = Tokens.red)
          Chip("RETRY", selected = false) { attempt++ }
        }
      }
      else -> {
        Text("LOADING ${clip.file}", style = MaterialTheme.typography.labelMedium, color = Tokens.muted)
      }
    }
  }
}

@Composable
private fun Playing(
  filmstrip: Filmstrip,
  clip: SamplePreset,
  composition: Composition,
) {
  val player = rememberFilmstripPlayer(filmstrip, composition)
  val state by player.state.collectAsState()
  LaunchedEffect(player) {
    player.setVolume(0f)
    player.setLoopRange(TimeRange.of(Duration.ZERO, clip.duration))
    player.play()
  }
  Box(Modifier.fillMaxSize()) {
    VideoSurface(player, Modifier.fillMaxSize())
    Box(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
      Chip(if (state.playWhenReady) "PAUSE" else "PLAY", selected = false, ground = Tokens.backdrop) {
        if (state.playWhenReady) player.pause() else player.play()
      }
    }
  }
}

/**
 * The Kotlin listing under a playground, values in coral, with a COPY key that hands [copy] the plain text.
 */
@Composable
internal fun CodePanel(
  lines: List<List<CodePart>>,
  copy: (String) -> Unit,
) {
  var copied by remember(lines) { mutableStateOf(false) }
  Column(Modifier.fillMaxWidth().background(Tokens.panel).border(1.dp, Tokens.line)) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("KOTLIN", style = MaterialTheme.typography.labelMedium, color = Tokens.muted)
      Chip(if (copied) "COPIED" else "COPY", selected = false) {
        copy(codeText(lines))
        copied = true
      }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Tokens.line))
    val listing =
      buildAnnotatedString {
        lines.forEachIndexed { index, line ->
          if (index > 0) append('\n')
          line.forEach { part ->
            val colour =
              when (part.kind) {
                CodeKind.Frame, CodeKind.Comment -> Tokens.muted
                CodeKind.Call -> Tokens.text
                CodeKind.Value -> Tokens.coral
              }
            withStyle(SpanStyle(color = colour)) { append(part.text) }
          }
        }
      }
    Text(
      text = listing,
      style = MaterialTheme.typography.bodyMedium,
      softWrap = false,
      modifier = Modifier.horizontalScroll(rememberScrollState()).padding(14.dp),
    )
  }
}

/**
 * Lays the blocks added in [content] out in [columns] equal columns, row by row.
 */
@Composable
internal fun Groups(
  columns: Int,
  content: GroupsScope.() -> Unit,
) {
  val blocks = GroupsScope().apply(content).blocks
  Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
    blocks.chunked(columns).forEach { row ->
      Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        row.forEach { block -> Box(Modifier.weight(1f)) { block() } }
        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
      }
    }
  }
}

internal class GroupsScope {
  val blocks = mutableListOf<@Composable () -> Unit>()

  fun add(block: @Composable () -> Unit) {
    blocks += block
  }
}

@Composable
internal fun Group(
  title: String,
  hint: String? = null,
  content: @Composable () -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.Bottom,
    ) {
      Text(title, style = MaterialTheme.typography.labelSmall, color = Tokens.muted)
      if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = Tokens.muted)
    }
    content()
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChipRow(content: @Composable () -> Unit) {
  FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    content()
  }
}

@Composable
internal fun Chip(
  label: String,
  selected: Boolean,
  enabled: Boolean = true,
  ground: Color = Color.Transparent,
  onClick: () -> Unit,
) {
  val interaction = remember { MutableInteractionSource() }
  val text =
    when {
      !enabled -> Tokens.muted
      selected -> Tokens.ground
      else -> Tokens.text
    }
  Box(
    modifier =
      Modifier
        .defaultMinSize(minHeight = controlHeight(), minWidth = controlHeight())
        .background(if (selected) Tokens.amber else ground)
        .border(2.dp, if (selected) Tokens.amber else Tokens.lineStrong)
        .focusRing(interaction)
        .selectable(selected, interaction, null, enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 10.dp),
    contentAlignment = Alignment.Center,
  ) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = text)
  }
}

@Composable
internal fun LedSwitch(
  label: String,
  on: Boolean,
  onChange: (Boolean) -> Unit,
) {
  val interaction = remember { MutableInteractionSource() }
  Row(
    modifier =
      Modifier
        .defaultMinSize(minHeight = controlHeight())
        .background(Tokens.panelRaised)
        .border(2.dp, if (on) Tokens.cyan else Tokens.lineStrong)
        .focusRing(interaction)
        .toggleable(on, interaction, null, role = Role.Switch, onValueChange = onChange)
        .padding(horizontal = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Led(if (on) Tokens.cyan else Tokens.lineStrong)
    Text(label, style = MaterialTheme.typography.labelMedium, color = if (on) Tokens.cyan else Tokens.muted)
  }
}

@Composable
internal fun Led(colour: Color) {
  Box(Modifier.size(8.dp).background(colour, CircleShape))
}

/**
 * One of the nine named anchors, for a crop to keep or an overlay to sit at.
 */
internal enum class NamedAnchor(
  val anchor: Anchor,
) {
  TopStart(Anchor.TopStart),
  TopCenter(Anchor.TopCenter),
  TopEnd(Anchor.TopEnd),
  CenterStart(Anchor.CenterStart),
  Center(Anchor.Center),
  CenterEnd(Anchor.CenterEnd),
  BottomStart(Anchor.BottomStart),
  BottomCenter(Anchor.BottomCenter),
  BottomEnd(Anchor.BottomEnd),
}

@Composable
internal fun AnchorGrid(
  selected: NamedAnchor,
  enabled: Boolean = true,
  onSelect: (NamedAnchor) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    NamedAnchor.entries.chunked(3).forEach { row ->
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        row.forEach { anchor ->
          val on = if (enabled) anchor == selected else anchor == NamedAnchor.Center
          val interaction = remember { MutableInteractionSource() }
          val dot =
            when {
              !enabled -> Tokens.lineStrong
              on -> Tokens.amber
              else -> Tokens.muted
            }
          Box(
            modifier =
              Modifier
                .size(36.dp)
                .background(Tokens.panelRaised)
                .border(2.dp, if (on && enabled) Tokens.amber else Tokens.lineStrong)
                .focusRing(interaction)
                .selectable(on, interaction, null, enabled = enabled, role = Role.RadioButton) { onSelect(anchor) }
                .semantics { contentDescription = anchor.name },
            contentAlignment = Alignment.Center,
          ) {
            Box(Modifier.size(8.dp).background(dot))
          }
        }
      }
    }
  }
}

@Composable
internal fun <T> Tabs(
  tabs: List<T>,
  selected: T,
  label: (T) -> String,
  accent: (T) -> Color,
  onSelect: (T) -> Unit,
) {
  Row(
    Modifier
      .fillMaxWidth()
      .drawBehind {
        drawLine(Tokens.line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
        drawLine(Tokens.line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
      },
  ) {
    tabs.forEach { tab ->
      val on = tab == selected
      val interaction = remember { MutableInteractionSource() }
      val colour = accent(tab)
      Box(
        modifier =
          Modifier
            .weight(1f)
            .height(48.dp)
            .background(if (on) Tokens.panel else Tokens.ground)
            .drawBehind {
              if (on) drawRect(colour, Offset(0f, size.height - 3.dp.toPx()), Size(size.width, 3.dp.toPx()))
            }.focusRing(interaction)
            .selectable(on, interactionSource = interaction, indication = null, role = Role.Tab) { onSelect(tab) },
        contentAlignment = Alignment.Center,
      ) {
        Text(label(tab), style = MaterialTheme.typography.titleMedium, color = if (on) Tokens.text else Tokens.muted)
      }
    }
  }
}

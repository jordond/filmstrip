package dev.jordond.filmstrip.sample.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.filekit.compose.rememberShareLauncher
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.sample.ResultVideoPlayer
import dev.jordond.filmstrip.sample.SampleAppState
import dev.jordond.filmstrip.sample.ui.ColourBars
import dev.jordond.filmstrip.sample.ui.FringeText
import dev.jordond.filmstrip.sample.ui.IconKey
import dev.jordond.filmstrip.sample.ui.Key
import dev.jordond.filmstrip.sample.ui.KeyStyle
import dev.jordond.filmstrip.sample.ui.LedState
import dev.jordond.filmstrip.sample.ui.LocalScreenInsets
import dev.jordond.filmstrip.sample.ui.Panel
import dev.jordond.filmstrip.sample.ui.Pill
import dev.jordond.filmstrip.sample.ui.SampleIcons
import dev.jordond.filmstrip.sample.ui.StatRow
import dev.jordond.filmstrip.sample.ui.Tokens
import dev.jordond.filmstrip.sample.ui.TvSet
import dev.jordond.filmstrip.sample.ui.asClock
import dev.jordond.filmstrip.sample.ui.isCompactWidth
import dev.jordond.filmstrip.sample.ui.timecode

/**
 * What came out: the file played back on the set, what filmstrip said it wrote, and what a fresh probe of the output
 * says it actually is.
 *
 * A wide window puts the set beside the facts, and a phone stacks them with the keys held under the scroll.
 */
@Composable
public fun ResultScreen(
  state: SampleAppState,
  modifier: Modifier = Modifier,
) {
  val result = state.exported ?: return
  val compact = isCompactWidth()

  Scaffold(
    modifier = modifier,
    containerColor = MaterialTheme.colorScheme.background,
    contentColor = MaterialTheme.colorScheme.onSurface,
    topBar = { ResultHeader(state, compact) },
  ) { insets ->
    if (compact) {
      Column(Modifier.fillMaxSize().padding(insets)) {
        Column(
          modifier =
            Modifier
              .fillMaxWidth()
              .weight(1f)
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 16.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          ResultSet(result, compact = true)
          ResultFacts(state, result)
        }
        ResultKeys(state, result, Modifier.padding(16.dp))
      }
    } else {
      Row(
        modifier =
          Modifier
            .fillMaxSize()
            .padding(insets)
            .verticalScroll(rememberScrollState())
            .padding(start = 40.dp, end = 40.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
        verticalAlignment = Alignment.Top,
      ) {
        ResultSet(result, compact = false, modifier = Modifier.weight(WIDE_SET_WEIGHT))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
          ResultFacts(state, result)
          ResultKeys(state, result)
        }
      }
    }
  }
}

@Composable
private fun ResultHeader(
  state: SampleAppState,
  compact: Boolean,
) {
  val insets = LocalScreenInsets.current
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
        .padding(start = insets.side, end = insets.side, top = insets.top)
        .heightIn(min = HEADER_HEIGHT)
        .padding(horizontal = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IconKey(SampleIcons.Close, "Back to the editor", state::navigateBack)
    FringeText(
      text = "Export complete",
      style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.displaySmall,
      color = MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.padding(end = 12.dp),
      fitWidth = true,
    )
  }
}

/**
 * The set with the written file on its tube, inset far enough that the tube's corners never touch the picture.
 *
 * The player is a platform view that does not report where it is, so the OSD carries the file's own length rather than
 * a running timecode.
 */
@Composable
private fun ResultSet(
  result: ExportStatus.Success,
  compact: Boolean,
  modifier: Modifier = Modifier,
) {
  val path = result.playablePath()
  TvSet(modifier = modifier.fillMaxWidth(), compact = compact, led = LedState.Ready, tubeEffects = path == null) {
    if (path == null) {
      ColourBars(Modifier.fillMaxSize(), compact = compact)
      return@TvSet
    }
    val video = result.info.video
    val aspect = video?.displaySize?.aspect?.takeIf { it > 0f } ?: (16f / 9f)
    val inset = if (compact) TUBE_INSET_COMPACT else TUBE_INSET
    Column(
      Modifier
        .fillMaxSize()
        .padding(horizontal = inset * 3 / 2, vertical = inset),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        FringeText("LENGTH", MaterialTheme.typography.labelMedium, MaterialTheme.colorScheme.primary)
        FringeText(
          text = timecode(result.info.duration.inWholeMilliseconds / 1000.0, video?.frameRate),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.primary,
        )
      }
      Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
        ResultVideoPlayer(
          path = path,
          modifier = Modifier.aspectRatio(aspect).background(Tokens.screen),
        )
      }
    }
  }
}

@Composable
private fun ResultFacts(
  state: SampleAppState,
  result: ExportStatus.Success,
) {
  Panel("ON THE TAPE", Modifier.fillMaxWidth()) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      StatRow("Duration", result.info.duration.asClock())
      result.info.video?.let { video ->
        StatRow("Frame", "${video.displaySize.width} x ${video.displaySize.height}")
        StatRow("Video", video.codec.name)
        video.bitrate?.let { StatRow("Bitrate", it.toString()) }
      }
      result.info.audio?.let { audio ->
        StatRow("Audio", "${audio.codec.name} · ${audio.sampleRate} Hz · ${audio.channelCount} ch")
      }
    }
    when (val probe = state.exportedInfo) {
      is ProbeResult.Success -> {
        Pill("RE-PROBED · ${probe.info.duration.asClock()}", MaterialTheme.colorScheme.secondary)
      }
      is ProbeResult.Failure -> {
        ErrorLine(probe.error)
      }
      null -> {}
    }
  }

  if (result.adjustments.isNotEmpty()) {
    AdjustedFrame {
      Text("ADJUSTED ON THE WAY OUT", style = MaterialTheme.typography.titleMedium, color = Tokens.amber)
      result.adjustments.forEach { adjustment ->
        Text(adjustment.message, style = MaterialTheme.typography.bodySmall, color = Tokens.mutedStrong)
      }
    }
  }

  result.playablePath()?.let { path ->
    SelectionContainer {
      Text(path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
private fun ResultKeys(
  state: SampleAppState,
  result: ExportStatus.Success,
  modifier: Modifier = Modifier,
) {
  // Sharing hands the sink over whole, so a content uri reaches the sheet as a content uri.
  val sharer = rememberShareLauncher()
  val shareable = result.output !is MediaSink.Temporary

  Row(modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    Key(
      label = "KEEP EDITING",
      onClick = state::navigateBack,
      modifier = Modifier.weight(1f).fillMaxHeight(),
      style = KeyStyle.Secondary,
      minHeight = KEY_HEIGHT,
      horizontalPadding = 12.dp,
    )
    if (sharer != null && shareable) {
      Key(
        label = "SHARE",
        onClick = { sharer.launch(result.output) },
        modifier = Modifier.fillMaxHeight(),
        minHeight = KEY_HEIGHT,
        horizontalPadding = 12.dp,
      )
    }
  }
}

/**
 * Where the file sits as the player needs it, which is only ever a string.
 */
private fun ExportStatus.Success.playablePath(): String? =
  when (val sink = output) {
    is MediaSink.Path -> sink.path
    is MediaSink.Uri -> sink.uri
    MediaSink.Temporary -> null
  }

private const val WIDE_SET_WEIGHT = 1.6f
private val HEADER_HEIGHT = 64.dp
private val KEY_HEIGHT = 52.dp
private val TUBE_INSET = 20.dp
private val TUBE_INSET_COMPACT = 12.dp

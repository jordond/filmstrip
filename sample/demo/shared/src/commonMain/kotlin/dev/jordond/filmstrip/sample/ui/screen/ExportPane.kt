package dev.jordond.filmstrip.sample.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.sample.SampleAppState
import dev.jordond.filmstrip.sample.ui.Chip
import dev.jordond.filmstrip.sample.ui.ChipGroup
import dev.jordond.filmstrip.sample.ui.ChipPalette
import dev.jordond.filmstrip.sample.ui.LocalChipPalette
import dev.jordond.filmstrip.sample.ui.SampleIcons
import dev.jordond.filmstrip.sample.ui.Tokens
import dev.jordond.filmstrip.sample.ui.drawTubeScanlines
import dev.jordond.filmstrip.sample.ui.isCompactWidth

/**
 * Everything between an edit and a file, laid out as the deck's blue setup menu: what to ask the encoder for, what this
 * device says it will actually do, and the run itself.
 *
 * A narrow pane keeps the verdict and the run on a strip under the rows, and one with the room gives them a column of
 * their own. The scene around it decides whether this is a bottom sheet or a dialog.
 */
@Composable
public fun ExportPane(
  state: SampleAppState,
  modifier: Modifier = Modifier,
) {
  val compact = isCompactWidth()
  val base = MaterialTheme.colorScheme
  val menu =
    remember(base) {
      base.copy(onSurface = Tokens.white, onSurfaceVariant = Tokens.white, outline = MENU_LINE)
    }

  MaterialTheme(colorScheme = menu) {
    CompositionLocalProvider(LocalChipPalette provides MenuChips, LocalContentColor provides Tokens.white) {
      BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = maxWidth >= COLUMNS_FROM
        Column(
          Modifier
            .fillMaxWidth()
            .fillMaxHeight(if (compact) PHONE_HEIGHT_FRACTION else 1f)
            .background(Tokens.vcrBlue)
            .drawBehind { drawTubeScanlines() },
        ) {
          MenuHeader(state, compact)
          if (columns) WideMenu(state) else PhoneMenu(state)
        }
      }
    }
  }
}

@Composable
private fun ColumnScope.PhoneMenu(state: SampleAppState) {
  Column(
    Modifier
      .fillMaxWidth()
      .weight(1f)
      .verticalScroll(rememberScrollState())
      .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
  ) {
    SetupRows(state, labelWidth = null)
    DeviceDetailsBlock(state, Modifier.padding(top = 12.dp))
  }
  PhoneDeviceStrip(state)
}

@Composable
private fun ColumnScope.WideMenu(state: SampleAppState) {
  Row(
    Modifier
      .fillMaxWidth()
      .weight(1f)
      .padding(start = 22.dp, end = 22.dp, bottom = 22.dp),
    horizontalArrangement = Arrangement.spacedBy(28.dp),
  ) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
      Row(Modifier.padding(top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("SETTING", style = rowLabel(), color = Tokens.vcrHeader, modifier = Modifier.width(LABEL_WIDTH))
        Text("VALUE", style = rowLabel(), color = Tokens.vcrHeader)
      }
      SetupRows(state, labelWidth = LABEL_WIDTH)
    }
    WideDeviceColumn(state, Modifier.width(DEVICE_WIDTH).fillMaxHeight())
  }
}

@Composable
private fun MenuHeader(
  state: SampleAppState,
  compact: Boolean,
) {
  val style = menuTitle(compact)
  Row(
    modifier =
      Modifier
        .fillMaxWidth()
        .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    MenuPath("SETUP", "EXPORT", style)
    MenuPath(
      "MENU",
      "EXIT",
      style,
      Modifier
        .defaultMinSize(minHeight = Tokens.sizeTouch)
        .semantics { contentDescription = "Close Export" }
        .clickable(enabled = !state.exporting, role = Role.Button, onClick = state::navigateBack),
    )
  }
}

/**
 * Two words either side of the menu's vector chevron, since the fonts hold no such glyph.
 */
@Composable
private fun MenuPath(
  from: String,
  to: String,
  style: TextStyle,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(from, style = style)
    Icon(SampleIcons.ChevronRight, contentDescription = null, modifier = Modifier.size(CHEVRON))
    Text(to, style = style)
  }
}

@Composable
private fun SetupRows(
  state: SampleAppState,
  labelWidth: Dp?,
) {
  val busy = state.exporting

  SetupRow("Height", labelWidth) {
    ChipGroup(
      options = listOf("Source" to null, "480" to 480, "720" to 720, "1080" to 1080, "2160" to 2160),
      selected = state.targetHeight,
      enabled = !busy,
      onSelect = {
        state.targetHeight = it
        state.onEditChanged()
      },
    )
  }

  SetupRow("Video codec", labelWidth) {
    ChipGroup(
      options =
        listOf(
          "Auto" to VideoCodec.Auto,
          "H.264" to VideoCodec.H264,
          "HEVC" to VideoCodec.Hevc,
          "VP9" to VideoCodec.Vp9,
          "AV1" to VideoCodec.Av1,
        ),
      selected = state.videoCodec,
      enabled = !busy,
      onSelect = {
        state.videoCodec = it
        state.onEditChanged()
      },
    )
  }

  SetupRow("Audio codec", labelWidth) {
    ChipGroup(
      options =
        listOf(
          "Auto" to AudioCodec.Auto,
          "AAC" to AudioCodec.Aac,
          "Opus" to AudioCodec.Opus,
          "None" to AudioCodec.None,
        ),
      selected = state.audioCodec,
      enabled = !busy,
      onSelect = {
        state.audioCodec = it
        state.onEditChanged()
      },
    )
  }

  SetupRow("Bitrate", labelWidth) {
    ChipGroup(
      options = listOf("Auto" to null, "2 Mbps" to 2, "4 Mbps" to 4, "8 Mbps" to 8, "16 Mbps" to 16),
      selected = state.bitrateMbps,
      enabled = !busy,
      onSelect = {
        state.bitrateMbps = it
        state.onEditChanged()
      },
    )
  }

  SetupRow("Frame rate", labelWidth) {
    ChipGroup(
      options = listOf("Source" to null, "24" to 24, "30" to 30, "60" to 60),
      selected = state.frameRate,
      enabled = !busy,
      onSelect = {
        state.frameRate = it
        state.onEditChanged()
      },
    )
  }

  SetupRow("High dynamic range", labelWidth) {
    ChipGroup(
      options =
        listOf(
          "Auto" to HdrMode.Auto,
          "Keep HDR" to HdrMode.KeepHdr,
          "Tone map" to HdrMode.ToneMapToSdr,
        ),
      selected = state.hdr,
      enabled = !busy,
      onSelect = {
        state.hdr = it
        state.onEditChanged()
      },
    )
  }

  SetupRow("Snap the cut back to a key frame", labelWidth) {
    ChipGroup(
      options = listOf("Never" to 0f, "0.5s" to 0.5f, "2s" to 2f, "5s" to 5f),
      selected = state.edit.snapWithinSeconds,
      enabled = !busy,
      onSelect = {
        state.edit.snapWithinSeconds = it
        state.onEditChanged()
      },
    )
  }

  SetupRow("Strict", labelWidth) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
      Chip(
        label = if (state.strict) "On" else "Off",
        selected = state.strict,
        enabled = !busy,
        role = Role.Switch,
        onClick = {
          state.strict = !state.strict
          state.onEditChanged()
        },
        modifier = Modifier.semantics { contentDescription = "Strict" },
      )
      Text("Refuse a fallback instead of adjusting", style = MaterialTheme.typography.bodySmall)
    }
  }
}

/**
 * One line of the setup menu under a faint rule, its label above the values on a phone and in a column of
 * [labelWidth] on a wide window.
 */
@Composable
private fun SetupRow(
  label: String,
  labelWidth: Dp?,
  content: @Composable () -> Unit,
) {
  val modifier =
    Modifier
      .fillMaxWidth()
      .drawBehind {
        drawRect(MENU_RULE, Offset.Zero, Size(size.width, RULE_HEIGHT.toPx()))
      }
  if (labelWidth == null) {
    Column(modifier.padding(top = 8.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(label.uppercase(), style = rowLabel(), color = Tokens.vcrHeader)
      content()
    }
  } else {
    Row(
      modifier.padding(vertical = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(label.uppercase(), style = rowLabel(), modifier = Modifier.width(labelWidth))
      Row(Modifier.weight(1f)) { content() }
    }
  }
}

@Composable
private fun rowLabel(): TextStyle = MaterialTheme.typography.labelMedium.copy(fontSize = ROW_LABEL_SIZE.sp)

@Composable
private fun menuTitle(compact: Boolean): TextStyle =
  MaterialTheme.typography.labelLarge.copy(fontSize = if (compact) TITLE_SIZE_COMPACT.sp else TITLE_SIZE.sp)

private val MENU_LINE = Tokens.white.copy(alpha = 0.55f)
private val MENU_RULE = Tokens.white.copy(alpha = 0.18f)

private val MenuChips =
  ChipPalette(
    selectedFill = Tokens.vcrHeader,
    selectedInk = Tokens.vcrBlue,
    border = MENU_LINE,
    ink = Tokens.white,
  )

private const val PHONE_HEIGHT_FRACTION = 0.9f
private const val ROW_LABEL_SIZE = 22
private const val TITLE_SIZE = 28
private const val TITLE_SIZE_COMPACT = 26
private val COLUMNS_FROM = 760.dp
private val LABEL_WIDTH = 180.dp
private val DEVICE_WIDTH = 360.dp
private val RULE_HEIGHT = 2.dp
private val CHEVRON = 18.dp

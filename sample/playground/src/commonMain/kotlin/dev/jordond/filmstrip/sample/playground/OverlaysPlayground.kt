package dev.jordond.filmstrip.sample.playground

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.sample.ui.SliderRow
import dev.jordond.filmstrip.sample.ui.Tokens
import dev.jordond.filmstrip.sample.ui.controlHeight
import dev.jordond.filmstrip.style.FontWeight

private val TwoColumns = 560.dp

private enum class OverlayTab(
  val label: String,
) {
  Caption("CAPTION"),
  Logo("LOGO"),
  Motion("MOTION"),
}

/**
 * The overlays sample: real footage on a loop with a caption and a logo over it, controls for each, how both move and
 * when they show, and the Kotlin that gives the same picture.
 *
 * @param filmstrip Opens the player.
 * @param logo The picture the logo overlay draws.
 * @param copy Puts the code listing on the clipboard.
 */
@Composable
internal fun OverlaysPlayground(
  filmstrip: Filmstrip,
  logo: ImageSource,
  copy: (String) -> Unit,
) {
  var settings by remember { mutableStateOf(OverlaySettings()) }
  var tab by remember { mutableStateOf(OverlayTab.Caption) }

  BoxWithConstraints(Modifier.fillMaxSize().background(Tokens.ground)) {
    val columns = if (maxWidth >= TwoColumns) 2 else 1
    Column(
      modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      StatusLine(compare = false, overlaySummary(settings), "OUT 16:9")
      Picture(filmstrip, tearsOfSteel) { overlaysEdit(it, logo, settings) }
      tearsOfSteel.attribution?.let { credit ->
        Text("${tearsOfSteel.name} · $credit", style = MaterialTheme.typography.bodySmall, color = Tokens.muted)
      }
      Tabs(OverlayTab.entries, tab, { it.label }, ::tabColour) { tab = it }
      when (tab) {
        OverlayTab.Caption -> CaptionControls(settings, columns) { settings = it }
        OverlayTab.Logo -> LogoControls(settings, columns) { settings = it }
        OverlayTab.Motion -> MotionControls(settings, columns) { settings = it }
      }
      CodePanel(overlaysCode(settings), copy)
    }
  }
}

@Composable
private fun CaptionControls(
  settings: OverlaySettings,
  columns: Int,
  onChange: (OverlaySettings) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
    LedSwitch("CAPTION", settings.caption) { onChange(settings.copy(caption = it)) }
    if (!settings.caption) return@Column
    Group("TEXT", "empty text draws nothing") {
      CaptionField(settings.text) { onChange(settings.copy(text = it.take(OverlaySettings.MAX_TEXT))) }
    }
    Groups(columns) {
      add {
        Group("ANCHOR", settings.anchor.name) {
          AnchorGrid(settings.anchor) { onChange(settings.copy(anchor = it)) }
        }
      }
      add {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
          Group("WEIGHT") {
            ChipRow {
              FontWeight.entries.forEach { weight ->
                Chip(weight.name.uppercase(), settings.weight == weight) { onChange(settings.copy(weight = weight)) }
              }
            }
          }
          LedSwitch("PLATE", settings.plate) { onChange(settings.copy(plate = it)) }
        }
      }
    }
    SliderRow(
      label = "Size",
      value = settings.fontSize,
      valueLabel = floatLiteral(settings.fontSize).removeSuffix("f"),
      range = 0.03f..0.12f,
      onValueChange = { onChange(settings.copy(fontSize = roundToHundredths(it))) },
      onReset = { onChange(settings.copy(fontSize = OverlaySettings.DEFAULT_FONT_SIZE)) },
    )
  }
}

@Composable
private fun LogoControls(
  settings: OverlaySettings,
  columns: Int,
  onChange: (OverlaySettings) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
    LedSwitch("LOGO", settings.logo) { onChange(settings.copy(logo = it)) }
    if (!settings.logo) return@Column
    Group("CORNER") {
      ChipRow {
        Corner.entries.forEach { corner ->
          Chip(cornerLabel(corner), settings.corner == corner) { onChange(settings.copy(corner = corner)) }
        }
      }
    }
    Groups(columns) {
      add {
        SliderRow(
          label = "Scale",
          value = settings.logoScale,
          valueLabel = floatLiteral(settings.logoScale).removeSuffix("f"),
          range = 0.05f..0.3f,
          onValueChange = { onChange(settings.copy(logoScale = roundToHundredths(it))) },
          onReset = { onChange(settings.copy(logoScale = OverlaySettings.DEFAULT_LOGO_SCALE)) },
        )
      }
      add {
        SliderRow(
          label = "Opacity",
          value = settings.logoOpacity,
          valueLabel = floatLiteral(settings.logoOpacity).removeSuffix("f"),
          range = 0f..1f,
          onValueChange = { onChange(settings.copy(logoOpacity = roundToHundredths(it))) },
          onReset = { onChange(settings.copy(logoOpacity = OverlaySettings.DEFAULT_LOGO_OPACITY)) },
        )
      }
    }
  }
}

@Composable
private fun MotionControls(
  settings: OverlaySettings,
  columns: Int,
  onChange: (OverlaySettings) -> Unit,
) {
  Groups(columns) {
    add {
      Group("ANIMATION", "both overlays") {
        ChipRow {
          OverlayMotion.entries.forEach { motion ->
            Chip(motion.label, settings.motion == motion) { onChange(settings.copy(motion = motion)) }
          }
        }
      }
    }
    add {
      Group("SHOW", windowHint(settings)) {
        ChipRow {
          OverlayWindow.entries.forEach { window ->
            Chip(window.label, settings.window == window) { onChange(settings.copy(window = window)) }
          }
        }
      }
    }
  }
}

@Composable
private fun CaptionField(
  text: String,
  onChange: (String) -> Unit,
) {
  val interaction = remember { MutableInteractionSource() }
  val focused by interaction.collectIsFocusedAsState()
  BasicTextField(
    value = text,
    onValueChange = onChange,
    singleLine = true,
    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Tokens.text),
    cursorBrush = SolidColor(Tokens.amber),
    interactionSource = interaction,
    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Caption text" },
    decorationBox = { field ->
      Box(
        modifier =
          Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = controlHeight())
            .background(Tokens.panelRaised)
            .border(2.dp, if (focused) Tokens.amber else Tokens.lineStrong)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
      ) {
        field()
      }
    },
  )
}

private fun tabColour(tab: OverlayTab): Color =
  when (tab) {
    OverlayTab.Caption -> Tokens.amber
    OverlayTab.Logo -> Tokens.cyan
    OverlayTab.Motion -> Tokens.pink
  }

private fun cornerLabel(corner: Corner): String =
  when (corner) {
    Corner.TopStart -> "TOP START"
    Corner.TopEnd -> "TOP END"
    Corner.BottomStart -> "BOTTOM START"
    Corner.BottomEnd -> "BOTTOM END"
  }

private fun windowHint(settings: OverlaySettings): String =
  when {
    settings.window == OverlayWindow.Timed -> "of the 10 s clip"
    settings.motion == OverlayMotion.Fade || settings.motion == OverlayMotion.Slide -> "no end, so it never leaves"
    else -> "the whole clip"
  }

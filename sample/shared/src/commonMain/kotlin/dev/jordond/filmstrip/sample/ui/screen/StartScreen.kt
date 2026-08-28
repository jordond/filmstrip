package dev.jordond.filmstrip.sample.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.sample.SampleAppState
import dev.jordond.filmstrip.sample.presetsAvailable
import dev.jordond.filmstrip.sample.ui.ControlGroup
import dev.jordond.filmstrip.sample.ui.Pill
import dev.jordond.filmstrip.sample.ui.SampleIcons
import dev.jordond.filmstrip.sample.ui.isCompactWidth

private const val HEADLINE = "Cut, reframe and export\non the device's own encoder"
private const val BLURB =
  "One composition value drives the plan, the preview and the export. Pick a clip to see what " +
    "this device will do with it."

/**
 * The editor before a clip is loaded: the pitch, both ways of starting a session, and what this
 * device's encoders report.
 *
 * Everything a session needs is on this one screen, so importing is the only step between launch
 * and the editor. A narrow window reads it top to bottom. A wide one puts the capability report
 * beside the picker rather than at the end of a scroll.
 */
@Composable
public fun StartScreen(
  state: SampleAppState,
  onImport: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val compact = isCompactWidth()

  LaunchedEffect(Unit) {
    if (state.capabilities == null) state.refreshCapabilities()
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(
        Brush.verticalGradient(
          listOf(Color(0xFF14121A), MaterialTheme.colorScheme.background, Color(0xFF0D1116)),
        ),
      ),
    contentAlignment = Alignment.Center,
  ) {
    if (compact) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .windowInsetsPadding(WindowInsets.safeDrawing)
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(28.dp),
      ) {
        Wordmark(Alignment.CenterHorizontally)
        StripArtwork(Modifier.fillMaxWidth().height(86.dp))
        Pitch(state, onImport, TextAlign.Center, Alignment.CenterHorizontally)
        PresetSection(state)
        CapabilitiesSection(state)
      }
    } else {
      Row(
        modifier = Modifier
          .fillMaxHeight()
          .widthIn(max = 1120.dp)
          .windowInsetsPadding(WindowInsets.safeDrawing)
          .padding(horizontal = 40.dp, vertical = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
      ) {
        Column(
          modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
          Wordmark(Alignment.Start)
          StripArtwork(Modifier.fillMaxWidth().height(120.dp))
          Pitch(state, onImport, TextAlign.Start, Alignment.Start)
          PresetSection(state)
        }

        Column(
          modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
        ) {
          CapabilitiesSection(state)
        }
      }
    }
  }
}

@Composable
private fun Wordmark(alignment: Alignment.Horizontal) {
  Column(Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Icon(
        imageVector = SampleIcons.Film,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(26.dp),
      )
      Text("filmstrip", style = MaterialTheme.typography.headlineSmall)
    }
    Spacer(Modifier.height(12.dp))
    Pill("PRE-ALPHA SAMPLE", MaterialTheme.colorScheme.secondary)
  }
}

@Composable
private fun Pitch(
  state: SampleAppState,
  onImport: () -> Unit,
  align: TextAlign,
  alignment: Alignment.Horizontal,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier.fillMaxWidth(),
    horizontalAlignment = alignment,
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Text(HEADLINE, style = MaterialTheme.typography.displaySmall, textAlign = align)

    Text(
      text = BLURB,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.outline,
      textAlign = align,
    )

    Button(
      onClick = onImport,
      enabled = !state.probing,
      modifier = Modifier.height(52.dp).padding(top = 6.dp),
    ) {
      if (state.probing) {
        CircularProgressIndicator(
          modifier = Modifier.size(18.dp),
          color = MaterialTheme.colorScheme.onPrimary,
          strokeWidth = 2.dp,
        )
        Text("   Reading the file", style = MaterialTheme.typography.labelLarge)
      } else {
        Icon(SampleIcons.Plus, contentDescription = null, modifier = Modifier.size(20.dp))
        Text("   Import a video", style = MaterialTheme.typography.labelLarge)
      }
    }

    state.pickFailure?.let { failure ->
      Text(
        text = "Could not load that clip: $failure",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        textAlign = align,
      )
    }
  }
}

@Composable
private fun PresetSection(state: SampleAppState) {
  if (!presetsAvailable) return

  ControlGroup("Sample clips") {
    PresetList(state)
  }
}

@Composable
private fun CapabilitiesSection(state: SampleAppState) {
  ControlGroup(
    label = "Device capabilities",
    trailing = {
      IconButton(
        onClick = state::refreshCapabilities,
        enabled = !state.loadingCapabilities,
        modifier = Modifier.size(24.dp),
      ) {
        Icon(
          imageVector = SampleIcons.Refresh,
          contentDescription = "Refresh capabilities",
          tint = MaterialTheme.colorScheme.outline,
          modifier = Modifier.size(18.dp),
        )
      }
    },
  ) {
    CapabilitiesContent(state)

    TextButton(onClick = state::openDiagnostics) {
      Text(
        text = "Collect a diagnostic report",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
      )
    }
  }
}

/**
 * A strip of frames receding to the right, drawn rather than shipped as an asset.
 */
@Composable
private fun StripArtwork(modifier: Modifier = Modifier) {
  val scrim = MaterialTheme.colorScheme.scrim

  Canvas(modifier) {
    val frames = 4
    val gap = size.width * 0.02f
    val frameWidth = (size.width - gap * (frames - 1)) / frames
    val hues = listOf(28f, 268f, 200f, 150f)

    repeat(frames) { index ->
      val left = index * (frameWidth + gap)
      val shrink = index * size.height * 0.05f
      val top = shrink / 2f
      val height = size.height - shrink
      val alpha = 1f - index * 0.14f

      drawRoundRect(
        brush = Brush.verticalGradient(
          listOf(
            Color.hsv(hues[index], 0.42f, 0.55f, alpha),
            Color.hsv((hues[index] + 20f) % 360f, 0.5f, 0.22f, alpha),
          ),
          startY = top,
          endY = top + height,
        ),
        topLeft = Offset(left, top),
        size = Size(frameWidth, height),
        cornerRadius = CornerRadius(6f, 6f),
      )

      repeat(3) { hole ->
        val holeWidth = frameWidth * 0.16f
        val holeLeft = left + frameWidth * (0.18f + hole * 0.32f)
        drawRoundRect(
          color = scrim.copy(alpha = 0.7f * alpha),
          topLeft = Offset(holeLeft, top + height * 0.06f),
          size = Size(holeWidth, height * 0.1f),
          cornerRadius = CornerRadius(2f, 2f),
        )
        drawRoundRect(
          color = scrim.copy(alpha = 0.7f * alpha),
          topLeft = Offset(holeLeft, top + height * 0.84f),
          size = Size(holeWidth, height * 0.1f),
          cornerRadius = CornerRadius(2f, 2f),
        )
      }
    }
  }
}

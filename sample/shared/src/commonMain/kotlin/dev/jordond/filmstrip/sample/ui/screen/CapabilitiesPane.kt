package dev.jordond.filmstrip.sample.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.sample.SampleAppState
import dev.jordond.filmstrip.sample.ui.Led
import dev.jordond.filmstrip.sample.ui.LedState
import dev.jordond.filmstrip.sample.ui.Pill
import dev.jordond.filmstrip.sample.ui.SampleIcons
import dev.jordond.filmstrip.sample.ui.StatRow
import dev.jordond.filmstrip.sample.ui.Tokens

/**
 * The capability report as an overlay, for reading it without leaving an open edit.
 */
@Composable
public fun CapabilitiesPane(
  state: SampleAppState,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .heightIn(max = 620.dp)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp)
        .padding(top = 8.dp, bottom = 32.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    PaneHeader(
      title = "Device capabilities",
      onClose = state::navigateBack,
    ) {
      IconButton(onClick = state::refreshCapabilities) {
        Icon(SampleIcons.Refresh, contentDescription = "Refresh")
      }
    }

    CapabilitiesContent(state)
  }
}

/**
 * What the device's hardware encoders say they can do, straight from `Filmstrip.capabilities`.
 */
@Composable
internal fun CapabilitiesContent(
  state: SampleAppState,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    when (val result = state.capabilities) {
      null -> {
        if (state.loadingCapabilities) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Led(LedState.Busy, blinking = true)
            Text(
              "Asking the encoders",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        } else {
          Text(
            "Nothing asked yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      is CapabilitiesResult.Failure -> {
        ErrorLine(result.error)
      }
      is CapabilitiesResult.Success -> {
        val capabilities = result.capabilities

        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          if (capabilities.supportsHdrEncoding) GoodPill("HDR ENCODING") else PlainPill("NO HDR ENCODING")
          capabilities.concurrentSessionBudget?.let { PlainPill("$it SESSIONS") }
        }

        capabilities.video.forEach { video ->
          CodecCard {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(video.codec.name, style = MaterialTheme.typography.titleMedium)
              when (video.isHardwareAccelerated) {
                true -> GoodPill("HARDWARE")
                false -> PlainPill("SOFTWARE")
                null -> Unit
              }
            }
            video.encoderName?.let { StatRow("Encoder", it) }
            StatRow("Max frame", "${video.maxSize.width} x ${video.maxSize.height}")
            StatRow("Alignment", "${video.sizeAlignment} px")
            video.maxFrameRate?.let { StatRow("Max frame rate", "$it fps") }
            video.maxBitrate?.let { StatRow("Max bitrate", it.toString()) }
          }
        }

        capabilities.audio.forEach { audio ->
          CodecCard {
            Text(audio.codec.name, style = MaterialTheme.typography.titleMedium)
            StatRow("Sample rates", audio.sampleRates.joinToString(" / ") { "$it Hz" })
            StatRow("Max channels", "${audio.maxChannelCount}")
          }
        }
      }
    }
  }
}

@Composable
private fun CodecCard(content: @Composable ColumnScope.() -> Unit) {
  Column(
    modifier =
      Modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.surfaceContainer)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant)
        .padding(horizontal = 14.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
    content = content,
  )
}

@Composable
private fun GoodPill(text: String) {
  Pill(text, MaterialTheme.colorScheme.secondary)
}

@Composable
private fun PlainPill(text: String) {
  Pill(text, Tokens.mutedStrong, borderColor = MaterialTheme.colorScheme.outline)
}

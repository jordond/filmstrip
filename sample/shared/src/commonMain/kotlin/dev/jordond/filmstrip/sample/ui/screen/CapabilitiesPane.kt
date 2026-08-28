package dev.jordond.filmstrip.sample.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import dev.jordond.filmstrip.sample.ui.Pill
import dev.jordond.filmstrip.sample.ui.SampleIcons
import dev.jordond.filmstrip.sample.ui.StatRow

/**
 * The capability report as an overlay, for reading it without leaving an open edit.
 */
@Composable
public fun CapabilitiesPane(
  state: SampleAppState,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
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
      null ->
        if (state.loadingCapabilities) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(
              Modifier.size(16.dp),
              color = MaterialTheme.colorScheme.primary,
              strokeWidth = 2.dp,
            )
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
            color = MaterialTheme.colorScheme.outline,
          )
        }

      is CapabilitiesResult.Failure -> ErrorLine(result.error)

      is CapabilitiesResult.Success -> {
        val capabilities = result.capabilities

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Pill(
            if (capabilities.supportsHdrEncoding) "HDR ENCODING" else "NO HDR ENCODING",
            if (capabilities.supportsHdrEncoding) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline,
          )
          capabilities.concurrentSessionBudget?.let { Pill("$it SESSIONS", MaterialTheme.colorScheme.secondary) }
        }

        capabilities.video.forEach { video ->
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
              .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                video.codec.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
              )
              when (video.isHardwareAccelerated) {
                true -> Pill("HARDWARE", MaterialTheme.colorScheme.tertiary)
                false -> Pill("SOFTWARE", MaterialTheme.colorScheme.outline)
                null -> Unit
              }
            }
            StatRow("Encoder", video.encoderName ?: "unknown")
            StatRow("Max frame", "${video.maxSize.width} x ${video.maxSize.height}")
            StatRow("Alignment", "${video.sizeAlignment} px")
            video.maxFrameRate?.let { StatRow("Max frame rate", "$it fps") }
            video.maxBitrate?.let { StatRow("Max bitrate", "${it.bitsPerSecond / 1_000_000} Mbps") }
          }
        }

        capabilities.audio.forEach { audio ->
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
              .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Text(
              audio.codec.name,
              style = MaterialTheme.typography.titleSmall,
              color = MaterialTheme.colorScheme.onSurface,
            )
            StatRow("Sample rates", audio.sampleRates.joinToString(" / ") { "$it Hz" })
            StatRow("Max channels", "${audio.maxChannelCount}")
          }
        }
      }
    }
  }
}

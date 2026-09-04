package dev.jordond.filmstrip.sample.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.export.Adjustment
import dev.jordond.filmstrip.export.AudioCodec
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.sample.SampleAppState
import dev.jordond.filmstrip.sample.ui.ChipGroup
import dev.jordond.filmstrip.sample.ui.ControlGroup
import dev.jordond.filmstrip.sample.ui.Pill
import dev.jordond.filmstrip.sample.ui.StatRow
import dev.jordond.filmstrip.sample.ui.SwitchRow
import dev.jordond.filmstrip.sample.ui.asClock
import dev.jordond.filmstrip.sample.ui.formatBytes

/**
 * Everything between an edit and a file: what to ask the encoder for, what this device says it will
 * actually do, and the run itself.
 *
 * The scene around it decides whether this is a bottom sheet or a dialog.
 */
@Composable
public fun ExportPane(
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
    verticalArrangement = Arrangement.spacedBy(20.dp),
  ) {
    PaneHeader(
      title = "Export",
      onClose = state::navigateBack,
      closeEnabled = !state.exporting,
    )

    OutputControls(state)

    VerdictBlock(state)

    RunBlock(state)
  }
}

@Composable
private fun OutputControls(state: SampleAppState) {
  val busy = state.exporting

  ControlGroup("Height") {
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

  ControlGroup("Video codec") {
    ChipGroup(
      options = listOf(
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

  ControlGroup("Audio codec") {
    ChipGroup(
      options = listOf(
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

  ControlGroup("Bitrate") {
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

  ControlGroup("Frame rate") {
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

  ControlGroup("High dynamic range") {
    ChipGroup(
      options = listOf(
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

  ControlGroup("Snap the cut back to a key frame") {
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

  SwitchRow(
    label = "Strict",
    supporting = "Refuse a fallback instead of adjusting",
    checked = state.strict,
    enabled = !busy,
    onCheckedChange = {
      state.strict = it
      state.onEditChanged()
    },
  )
}

@Composable
private fun VerdictBlock(state: SampleAppState) {
  ControlGroup("This device") {
    OutlinedButton(
      onClick = state::plan,
      enabled = !state.planning && !state.exporting,
      shape = RoundedCornerShape(10.dp),
      modifier = Modifier.fillMaxWidth(),
    ) {
      if (state.planning) {
        CircularProgressIndicator(Modifier.size(16.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
        Text("   Planning")
      } else {
        Text("Check what this device will do")
      }
    }

    when (val verdict = state.verdict) {
      null -> Unit

      is Verdict.Capable -> {
        Pill("READY", MaterialTheme.colorScheme.tertiary)
        PlanSummary(verdict.plan, state)
      }

      is Verdict.Degraded -> {
        Pill("WILL ADJUST", MaterialTheme.colorScheme.primary)
        Adjustments(verdict.adjustments)
        PlanSummary(verdict.plan, state)
      }

      is Verdict.Incapable -> {
        Pill("CANNOT EXPORT", MaterialTheme.colorScheme.error)
        verdict.reasons.forEach { ErrorLine(it) }
        verdict.withoutUnsupported?.let { fallback ->
          Button(
            onClick = { state.export(fallback) },
            enabled = !state.exporting,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text("Export without the unsupported effects")
          }
        }
      }
    }
  }
}

@Composable
private fun PlanSummary(
  plan: ExportPlan,
  state: SampleAppState,
) {
  val output = plan.output
  val estimate = plan.estimate
  val minBytes = estimate.outputSizeBytesMin
  val maxBytes = estimate.outputSizeBytesMax

  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
      .padding(14.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    StatRow("Frame", "${output.size.width} x ${output.size.height}")
    StatRow("Video", output.videoCodec.name)
    StatRow(
      "Audio",
      output.audioFormat?.let { "${output.audioCodec.name} · ${it.sampleRate} Hz · ${it.channelCount} ch" }
        ?: output.audioCodec.name,
    )
    output.bitrate?.let { StatRow("Bitrate", "${it.bitsPerSecond / 1_000_000} Mbps") }
    output.frameRate?.let { StatRow("Frame rate", "$it fps") }
    StatRow("Path", plan.path.name.lowercase())
    if (plan.copyBlockedBy.isNotEmpty()) {
      StatRow("No copy because", plan.copyBlockedBy.joinToString { it.name })
    }
    StatRow("Parity", plan.parity.name.lowercase())
    if (minBytes != null && maxBytes != null) {
      StatRow("Estimated size", "${formatBytes(minBytes)} to ${formatBytes(maxBytes)}")
    }
    estimate.approximateDuration?.let { StatRow("Estimated run", "about ${it.asClock()}") }

    val parity = state.parity()
    if (parity.isNotEmpty()) {
      Spacer(Modifier.height(2.dp))
      parity.forEach { (id, name) ->
        StatRow(
          id,
          name.lowercase(),
          valueColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun Adjustments(adjustments: List<Adjustment>) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
      .padding(14.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    adjustments.forEach { adjustment ->
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
          "${adjustment.kind.name.lowercase()}: ${adjustment.requested} to ${adjustment.resolved}",
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
        )
        Text(
          adjustment.message,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun RunBlock(state: SampleAppState) {
  val plan = when (val verdict = state.verdict) {
    is Verdict.Capable -> verdict.plan
    is Verdict.Degraded -> verdict.plan
    else -> null
  }

  if (state.exporting) {
    val progress = state.exportProgress
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      if (progress == null) {
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
        Text("Starting", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      } else {
        LinearProgressIndicator(
          progress = { progress.fraction },
          modifier = Modifier.fillMaxWidth(),
          color = MaterialTheme.colorScheme.primary,
        )
        val parts = buildList {
          add("${(progress.fraction * 100).toInt()}%")
          progress.position?.let { add(it.asClock()) }
          progress.estimatedRemaining?.let { add("about ${it.asClock()} left") }
        }
        Text(
          parts.joinToString("  ·  "),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      OutlinedButton(
        onClick = state::cancelExport,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text("Cancel")
      }
    }
  } else {
    Button(
      onClick = { plan?.let(state::export) },
      enabled = plan != null,
      shape = RoundedCornerShape(12.dp),
      colors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        disabledContentColor = MaterialTheme.colorScheme.outline,
      ),
      modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
      Text(if (plan == null) "Check the device first" else "Export", style = MaterialTheme.typography.labelLarge)
    }
  }

  if (state.exportAdjustments.isNotEmpty()) {
    Adjustments(state.exportAdjustments)
  }

  state.exportFailure?.let { ErrorLine(it) }
}

@Composable
internal fun ErrorLine(error: ExportError) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(MaterialTheme.colorScheme.error.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
      .padding(12.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.Top,
  ) {
    Box(Modifier.size(6.dp).background(MaterialTheme.colorScheme.error, RoundedCornerShape(3.dp)))
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
        error::class.simpleName ?: "Error",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.error,
      )
      Text(
        error.message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

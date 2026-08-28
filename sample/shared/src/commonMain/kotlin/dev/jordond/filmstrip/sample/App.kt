package dev.jordond.filmstrip.sample

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.export.Adjustment
import dev.jordond.filmstrip.export.AudioCodec
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.TrimStrategy
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.ProbeResult
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.PickerResultLauncher
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.dialogs.compose.rememberShareFileLauncher
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The whole sample on one screen: pick a video, describe an edit, plan it, export it, watch the
 * result and read the device's encoder capabilities.
 */
@Composable
fun App(state: SampleAppState) {
  MaterialTheme {
    Surface(Modifier.fillMaxSize()) {
      val picker = rememberFilePickerLauncher(
        type = FileKitType.Video,
        onError = { state.onPickFailed(it.message) },
        onResult = { file -> state.onPicked(file?.toMediaSource()) },
      )

      Column(
        modifier = Modifier
          .fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
      ) {
        Text("filmstrip", style = MaterialTheme.typography.headlineMedium)

        SourceSection(state, picker)
        EditSection(state)
        OutputSection(state)
        PlanSection(state)
        ExportSection(state)
        ResultSection(state)
        CapabilitiesSection(state)
      }
    }
  }
}

@Composable
private fun SourceSection(
  state: SampleAppState,
  picker: PickerResultLauncher,
) {
  Section("Source") {
    Button(
      onClick = picker::launch,
      enabled = !state.probing && !state.exporting,
    ) {
      Text("Pick a video")
    }

    when {
      state.probing -> {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          CircularProgressIndicator(Modifier.size(16.dp))
          Text("Probing")
        }
      }

      state.probe is ProbeResult.Success -> {
        SourceInfo((state.probe as ProbeResult.Success).info)
      }

      state.probe is ProbeResult.Failure -> {
        ErrorText((state.probe as ProbeResult.Failure).error)
      }
    }

    state.pickFailure?.let {
      Text("The picker failed: $it", color = MaterialTheme.colorScheme.error)
    }
  }
}

@Composable
private fun SourceInfo(info: MediaInfo) {
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(formatDuration(info.duration))

    info.video?.let { video ->
      val frameRate = video.frameRate?.let { "${it.toInt()} fps" }
      val bitrate = video.bitrate?.let { "${it.bitsPerSecond / 1_000_000} Mbps" }
      val hdr = if (video.hdrTransfer != null) "HDR" else "SDR"
      Text(
        listOf(
          "${video.displaySize.width}x${video.displaySize.height}",
          video.codec.name,
          frameRate,
          bitrate,
          hdr,
        ).filterNotNull().joinToString("  "),
      )
    }

    info.audio?.let { audio ->
      Text("${audio.codec.name} ${audio.sampleRate} Hz ${audio.channelCount} ch")
    }

    if (!info.isExportable) {
      Text("Not exportable", color = MaterialTheme.colorScheme.error)
    }
  }
}

@Composable
private fun EditSection(state: SampleAppState) {
  Section("Edit") {
    if (state.source == null) {
      Text("Pick a video first.")
      return@Section
    }

    val busy = state.exporting

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text("Trim")
      Switch(
        checked = state.trimming,
        onCheckedChange = {
          state.trimming = it
          state.onEditChanged()
        },
        enabled = !busy,
      )
    }

    val durationSeconds = state.sourceDurationSeconds
    if (state.trimming && durationSeconds != null && durationSeconds > 0f) {
      RangeSlider(
        value = state.trimStartSeconds..state.trimEndSeconds,
        onValueChange = { range ->
          state.trimStartSeconds = range.start
          state.trimEndSeconds = range.endInclusive
          state.onEditChanged()
        },
        valueRange = 0f..durationSeconds,
        enabled = !busy,
      )
      Text(
        "${formatDuration(state.trimStartSeconds.toDouble().seconds)} to " +
          formatDuration(state.trimEndSeconds.toDouble().seconds),
      )
    }

    ChoiceRow(
      title = "Rotate",
      options = listOf("0°" to 0, "90°" to 90, "180°" to 180, "270°" to 270),
      selected = state.rotationDegrees,
      enabled = !busy,
      onSelect = {
        state.rotationDegrees = it
        state.onEditChanged()
      },
    )

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text("Flip horizontally")
      Switch(
        checked = state.flipHorizontal,
        onCheckedChange = {
          state.flipHorizontal = it
          state.onEditChanged()
        },
        enabled = !busy,
      )
    }

    ChoiceRow(
      title = "Crop",
      options = listOf(
        "Off" to null,
        "Square" to AspectRatio.Square,
        "Portrait" to AspectRatio.Portrait,
        "Landscape" to AspectRatio.Landscape,
        "Feed" to AspectRatio.Feed,
      ),
      selected = state.cropAspect,
      enabled = !busy,
      onSelect = {
        state.cropAspect = it
        state.onEditChanged()
      },
    )

    OutlinedTextField(
      value = state.caption,
      onValueChange = {
        state.caption = it
        state.onEditChanged()
      },
      label = { Text("Caption (burned in)") },
      singleLine = true,
      enabled = !busy,
      modifier = Modifier.fillMaxWidth(),
    )

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text("Mute audio")
      Switch(
        checked = state.muteAudio,
        onCheckedChange = {
          state.muteAudio = it
          state.onEditChanged()
        },
        enabled = !busy,
      )
    }
  }
}

@Composable
private fun OutputSection(state: SampleAppState) {
  Section("Output") {
    ChoiceRow(
      title = "Height",
      options = listOf("Source" to null, "480" to 480, "720" to 720, "1080" to 1080, "2160" to 2160),
      selected = state.targetHeight,
      enabled = !state.exporting,
      onSelect = {
        state.targetHeight = it
        state.onEditChanged()
      },
    )

    ChoiceRow(
      title = "Video codec",
      options = listOf(
        "Auto" to VideoCodec.Auto,
        "H264" to VideoCodec.H264,
        "HEVC" to VideoCodec.Hevc,
        "VP9" to VideoCodec.Vp9,
        "AV1" to VideoCodec.Av1,
      ),
      selected = state.videoCodec,
      enabled = !state.exporting,
      onSelect = {
        state.videoCodec = it
        state.onEditChanged()
      },
    )

    ChoiceRow(
      title = "Audio codec",
      options = listOf(
        "Auto" to AudioCodec.Auto,
        "AAC" to AudioCodec.Aac,
        "Opus" to AudioCodec.Opus,
        "None" to AudioCodec.None,
      ),
      selected = state.audioCodec,
      enabled = !state.exporting,
      onSelect = {
        state.audioCodec = it
        state.onEditChanged()
      },
    )

    ChoiceRow(
      title = "Bitrate",
      options = listOf("Auto" to null, "2 Mbps" to 2, "4 Mbps" to 4, "8 Mbps" to 8, "16 Mbps" to 16),
      selected = state.bitrateMbps,
      enabled = !state.exporting,
      onSelect = {
        state.bitrateMbps = it
        state.onEditChanged()
      },
    )

    ChoiceRow(
      title = "Frame rate",
      options = listOf("Source" to null, "24" to 24, "30" to 30, "60" to 60),
      selected = state.frameRate,
      enabled = !state.exporting,
      onSelect = {
        state.frameRate = it
        state.onEditChanged()
      },
    )

    ChoiceRow(
      title = "HDR",
      options = listOf(
        "Auto" to HdrMode.Auto,
        "Keep HDR" to HdrMode.KeepHdr,
        "Tone map" to HdrMode.ToneMapToSdr,
      ),
      selected = state.hdr,
      enabled = !state.exporting,
      onSelect = {
        state.hdr = it
        state.onEditChanged()
      },
    )

    ChoiceRow(
      title = "Trim strategy",
      options = listOf(
        "Precise" to TrimStrategy.Precise,
        "Fast" to TrimStrategy.Fast,
      ),
      selected = state.trimStrategy,
      enabled = !state.exporting,
      onSelect = {
        state.trimStrategy = it
        state.onEditChanged()
      },
    )

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Column(Modifier.weight(1f)) {
        Text("Strict")
        Text("Refuse a fallback instead of adjusting", style = MaterialTheme.typography.bodySmall)
      }
      Switch(
        checked = state.strict,
        onCheckedChange = {
          state.strict = it
          state.onEditChanged()
        },
        enabled = !state.exporting,
      )
    }
  }
}

@Composable
private fun PlanSection(state: SampleAppState) {
  Section("Plan") {
    Button(
      onClick = state::plan,
      enabled = state.source != null && !state.planning && !state.exporting,
    ) {
      Text("Plan")
    }

    when (val verdict = state.verdict) {
      null -> Unit

      is Verdict.Capable -> {
        Text("Ready", color = MaterialTheme.colorScheme.primary)
        PlanSummary(verdict.plan)
      }

      is Verdict.Degraded -> {
        Text("Will export with changes", color = MaterialTheme.colorScheme.tertiary)
        Adjustments(verdict.adjustments)
        PlanSummary(verdict.plan)
      }

      is Verdict.Incapable -> {
        Text("Cannot export", color = MaterialTheme.colorScheme.error)
        verdict.reasons.forEach { ErrorText(it) }
        val fallbackPlan = verdict.withoutUnsupported
        if (fallbackPlan != null) {
          Button(
            onClick = { state.export(fallbackPlan) },
            enabled = !state.exporting,
          ) {
            Text("Export without unsupported effects")
          }
        }
      }
    }
  }
}

@Composable
private fun PlanSummary(plan: ExportPlan) {
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text("path: ${plan.path.name.lowercase()}")

    val output = plan.output
    val bits = listOf(
      "${output.size.width}x${output.size.height}",
      output.videoCodec.name,
      output.audioCodec.name,
      output.audioFormat?.let { "${it.sampleRate} Hz ${it.channelCount} ch" },
      output.bitrate?.let { "${it.bitsPerSecond / 1_000_000} Mbps" },
      output.frameRate?.let { "$it fps" },
    ).filterNotNull()
    Text("output: ${bits.joinToString(" ")}")

    Text("parity: ${plan.parity.name.lowercase()}")

    val estimate = plan.estimate
    val min = estimate.outputSizeBytesMin
    val max = estimate.outputSizeBytesMax
    val size = if (min != null && max != null) {
      "${formatMegabytes(min)} to ${formatMegabytes(max)}"
    } else {
      null
    }
    val duration = estimate.approximateDuration?.let { "about ${formatDuration(it)}" }
    val extras = listOf(size, duration).filterNotNull()
    if (extras.isNotEmpty()) {
      Text("estimate: ${extras.joinToString(", ")}")
    }
  }
}

@Composable
private fun Adjustments(adjustments: List<Adjustment>) {
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    adjustments.forEach { adjustment ->
      Text(
        "${adjustment.kind.name.lowercase()}: ${adjustment.requested} -> ${adjustment.resolved}",
        color = MaterialTheme.colorScheme.tertiary,
      )
      Text(adjustment.message)
    }
  }
}

@Composable
private fun ExportSection(state: SampleAppState) {
  Section("Export") {
    val plan = when (val verdict = state.verdict) {
      is Verdict.Capable -> verdict.plan
      is Verdict.Degraded -> verdict.plan
      else -> null
    }

    Button(
      onClick = { plan?.let(state::export) },
      enabled = plan != null && !state.exporting,
    ) {
      Text("Export")
    }

    if (state.exporting) {
      val progress = state.exportProgress
      if (progress == null) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("Starting")
      } else {
        LinearProgressIndicator(
          progress = { progress.fraction },
          modifier = Modifier.fillMaxWidth(),
        )
        val parts = buildList {
          add("${(progress.fraction * 100).toInt()}%")
          progress.position?.let { add(formatDuration(it)) }
          progress.estimatedRemaining?.let { add("about ${formatDuration(it)} left") }
        }
        Text(parts.joinToString("  "))
      }

      Button(onClick = state::cancelExport) {
        Text("Cancel")
      }
    }

    if (state.exportAdjustments.isNotEmpty()) {
      Adjustments(state.exportAdjustments)
    }

    state.exportFailure?.let { ErrorText(it) }
  }
}

@Composable
private fun ResultSection(state: SampleAppState) {
  Section("Result") {
    val result = state.exported ?: return@Section

    val path = when (val sink = result.output) {
      is MediaSink.Path -> sink.path
      is MediaSink.Uri -> sink.uri
      MediaSink.Temporary -> null
    }
    if (path != null) {
      SelectionContainer { Text(path) }
    }

    Text(formatDuration(result.info.duration))
    result.info.video?.let { video ->
      Text("${video.displaySize.width}x${video.displaySize.height} ${video.codec.name}")
    }
    result.info.audio?.let { audio ->
      Text("${audio.codec.name} ${audio.sampleRate} Hz ${audio.channelCount} ch")
    }

    if (result.adjustments.isNotEmpty()) {
      Adjustments(result.adjustments)
    }

    when (val probe = state.exportedInfo) {
      is ProbeResult.Success -> {
        Text("probed the output again: ${formatDuration(probe.info.duration)}, readable")
      }
      is ProbeResult.Failure -> ErrorText(probe.error)
      null -> Unit
    }

    if (path != null) {
      val aspect = result.info.video?.displaySize?.aspect ?: 16f / 9f
      ResultVideoPlayer(
        path = path,
        modifier = Modifier
          .fillMaxWidth()
          .aspectRatio(aspect),
      )

      val sharer = rememberShareFileLauncher()
      Button(onClick = { sharer.launch(PlatformFile(path)) }) {
        Text("Share the file")
      }
    }
  }
}

@Composable
private fun CapabilitiesSection(state: SampleAppState) {
  Section("Device capabilities") {
    Button(onClick = state::refreshCapabilities) {
      Text("Refresh")
    }

    when (val result = state.capabilities) {
      null -> Unit

      is CapabilitiesResult.Success -> {
        val capabilities = result.capabilities
        capabilities.video.forEachIndexed { index, video ->
          val frameRate = video.maxFrameRate?.let { "up to $it fps" }
          val bitrate = video.maxBitrate?.let { "up to ${it.bitsPerSecond / 1_000_000} Mbps" }
          val hardware = when (video.isHardwareAccelerated) {
            true -> "hardware"
            false -> "software"
            null -> null
          }
          val bits = listOf(
            "${index + 1}.",
            video.codec.name,
            video.encoderName,
            "max ${video.maxSize.width}x${video.maxSize.height}",
            "align ${video.sizeAlignment}",
            frameRate,
            bitrate,
            hardware,
          ).filterNotNull()
          Text(bits.joinToString("  "))
        }
        capabilities.audio.forEach { audio ->
          val rates = audio.sampleRates.joinToString("/")
          Text("${audio.codec.name} $rates Hz, up to ${audio.maxChannelCount} ch")
        }
        Text("HDR encoding: ${if (capabilities.supportsHdrEncoding) "yes" else "no"}")
        capabilities.concurrentSessionBudget?.let { Text("session budget: $it") }
      }

      is CapabilitiesResult.Failure -> ErrorText(result.error)
    }
  }
}

@Composable
private fun ErrorText(error: ExportError) {
  Text("${error::class.simpleName}: ${error.message}", color = MaterialTheme.colorScheme.error)
}

@Composable
private fun <T> ChoiceRow(
  title: String,
  options: List<Pair<String, T>>,
  selected: T,
  enabled: Boolean,
  onSelect: (T) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(title)
    FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      options.forEach { (label, value) ->
        FilterChip(
          selected = selected == value,
          onClick = { onSelect(value) },
          label = { Text(label) },
          enabled = enabled,
        )
      }
    }
  }
}

@Composable
private fun Section(
  title: String,
  content: @Composable ColumnScope.() -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    HorizontalDivider()
    content()
  }
}

private fun formatDuration(duration: Duration): String = "${duration.inWholeSeconds}s"

private fun formatMegabytes(bytes: Long): String = "${bytes / 1_000_000} MB"

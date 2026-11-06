package dev.jordond.filmstrip.sample.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.export.Adjustment
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.sample.SampleAppState
import dev.jordond.filmstrip.sample.ui.Key
import dev.jordond.filmstrip.sample.ui.KeyStyle
import dev.jordond.filmstrip.sample.ui.Led
import dev.jordond.filmstrip.sample.ui.LedState
import dev.jordond.filmstrip.sample.ui.SampleIcons
import dev.jordond.filmstrip.sample.ui.StatRow
import dev.jordond.filmstrip.sample.ui.Tokens
import dev.jordond.filmstrip.sample.ui.VerdictPill
import dev.jordond.filmstrip.sample.ui.asClock
import dev.jordond.filmstrip.sample.ui.formatBytes
import dev.jordond.filmstrip.sample.ui.timecode
import dev.jordond.filmstrip.sample.ui.Verdict as VerdictWord

/**
 * The `THIS DEVICE` column of a wide setup menu: the verdict and its facts, the run, and the keys that move things on.
 */
@Composable
internal fun WideDeviceColumn(
  state: SampleAppState,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier
      .background(Tokens.vcrBorder)
      .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Column(
      Modifier.weight(1f).verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text("THIS DEVICE", style = MaterialTheme.typography.titleMedium, color = Tokens.vcrHeader)
      if (state.exporting) RunProgress(state) else state.verdict?.let { VerdictWordFor(it) } ?: IdleNote()
      DeviceDetails(state)
    }
    RunKey(state)
  }
}

/**
 * The strip under a phone's setup rows, the verdict in a line with the keys under it, or the run while one is going.
 */
@Composable
internal fun PhoneDeviceStrip(state: SampleAppState) {
  Column(
    Modifier
      .fillMaxWidth()
      .background(Tokens.vcrBorder)
      .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    val verdict = state.verdict
    when {
      state.exporting -> RunProgress(state)
      verdict == null -> IdleNote()
      else -> Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        VerdictWordFor(verdict)
        verdict.planOrNull()?.let { plan ->
          Text(
            text = plan.oneLine(),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
          )
        }
      }
    }
    RunKey(state)
  }
}

/**
 * The verdict's facts on a phone, inline at the end of the scrolling rows since they are too long for the strip.
 */
@Composable
internal fun DeviceDetailsBlock(
  state: SampleAppState,
  modifier: Modifier = Modifier,
) {
  if (state.verdict == null && state.exportFailure == null && state.exportAdjustments.isEmpty()) return

  Column(
    modifier
      .fillMaxWidth()
      .background(Tokens.vcrBorder)
      .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text("THIS DEVICE", style = MaterialTheme.typography.titleMedium, color = Tokens.vcrHeader)
    DeviceDetails(state)
  }
}

@Composable
private fun IdleNote() {
  Text(
    "Nothing asked yet. The verdict covers this spec on this device, before anything decodes.",
    style = MaterialTheme.typography.bodySmall,
  )
}

@Composable
private fun VerdictWordFor(verdict: Verdict) {
  VerdictPill(
    when (verdict) {
      is Verdict.Capable -> VerdictWord.Ready
      is Verdict.Degraded -> VerdictWord.WillAdjust
      is Verdict.Incapable -> VerdictWord.CannotExport
    },
  )
}

@Composable
private fun DeviceDetails(state: SampleAppState) {
  when (val verdict = state.verdict) {
    null -> Unit

    is Verdict.Capable -> PlanSummary(verdict.plan, state)

    is Verdict.Degraded -> {
      Adjustments(verdict.adjustments)
      PlanSummary(verdict.plan, state)
    }

    is Verdict.Incapable -> {
      verdict.reasons.forEach { ErrorLine(it) }
      verdict.withoutUnsupported?.let { Adjustments(it.adjustments) }
    }
  }

  if (state.exportAdjustments.isNotEmpty()) Adjustments(state.exportAdjustments)
  state.exportFailure?.let { ErrorLine(it) }
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

  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
    MenuStat("Frame", "${output.size.width} x ${output.size.height}")
    MenuStat("Video", output.videoCodec.name)
    MenuStat(
      "Audio",
      output.audioFormat?.let { "${output.audioCodec.name} · ${it.sampleRate} Hz · ${it.channelCount} ch" }
        ?: output.audioCodec.name,
    )
    output.bitrate?.let { MenuStat("Bitrate", it.toString()) }
    MenuStat("Frame rate", "${output.frameRate} fps")
    MenuStat("Path", plan.path.name.lowercase())
    if (plan.copyBlockedBy.isNotEmpty()) {
      MenuStat("No copy because", plan.copyBlockedBy.joinToString { it.name })
    }
    if (plan.inertNotices.isNotEmpty()) {
      MenuStat("Left out, renders nothing", plan.inertNotices.joinToString { it.subject })
    }
    MenuStat("Parity", plan.parity.name.lowercase())
    if (minBytes != null && maxBytes != null) {
      MenuStat("Estimated size", "${formatBytes(minBytes)} to ${formatBytes(maxBytes)}")
    }
    estimate.approximateDuration?.let { MenuStat("Estimated run", "about ${it.asClock()}") }

    state.parity().forEach { (id, name) -> MenuStat(id, name.lowercase()) }
  }
}

@Composable
private fun MenuStat(
  label: String,
  value: String,
) {
  StatRow(label, value, valueColor = Tokens.vcrHeader)
}

/**
 * What the run is up to: `REC` blinking on its LED, how far along, 16 cells and the timecode reached.
 */
@Composable
private fun RunProgress(state: SampleAppState) {
  val progress = state.exportProgress
  val fps = state.info?.video?.frameRate
  val large = MaterialTheme.typography.labelLarge.copy(fontSize = RUN_TEXT_SIZE.sp)
  val fraction = progress?.fraction ?: 0f

  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(
      Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Led(LedState.Recording, blinking = true)
        Text("REC", style = large, color = Tokens.amber)
      }
      Text("${(fraction * 100).toInt()}%", style = large)
    }

    ProgressCells(fraction)

    if (progress == null) {
      Text("STARTING", style = MaterialTheme.typography.labelLarge)
    } else {
      progress.position?.let { position ->
        val reached = timecode(position.inWholeMilliseconds / 1000.0, fps)
        val total = timecode(state.editedDuration.inWholeMilliseconds / 1000.0, fps)
        Text("$reached / $total", style = MaterialTheme.typography.labelLarge)
      }
      progress.estimatedRemaining?.let {
        Text("about ${it.asClock()} left", style = MaterialTheme.typography.bodySmall)
      }
    }
  }
}

@Composable
private fun ProgressCells(fraction: Float) {
  val lit = litCells(fraction)
  val percent = (fraction * 100).toInt()
  Row(
    Modifier.fillMaxWidth().semantics { contentDescription = "Export progress, $percent percent" },
    horizontalArrangement = Arrangement.spacedBy(CELL_GAP),
  ) {
    repeat(EXPORT_CELLS) { index ->
      val on = index < lit
      Box(
        Modifier
          .weight(1f)
          .height(CELL_HEIGHT)
          .background(if (on) Tokens.amber else Tokens.vcrBorder)
          .border(2.dp, if (on) Tokens.amber else MaterialTheme.colorScheme.outline),
      )
    }
  }
}

/**
 * The keys that move the run on: ask the device, export, export without what it refused, or cancel. A verdict on show
 * keeps a second key to ask again.
 */
@Composable
private fun RunKey(state: SampleAppState) {
  val verdict = state.verdict
  val plan = verdict?.planOrNull()
  val modifier = Modifier.fillMaxWidth()

  when {
    state.exporting -> Key("CANCEL", state::cancelExport, modifier, style = KeyStyle.Secondary, minHeight = RUN_KEY)

    verdict == null -> CheckKey(
      label = if (state.planning) "PLANNING" else "CHECK WHAT THIS DEVICE WILL DO",
      state = state,
      modifier = modifier,
    )

    else -> {
      if (plan != null) {
        Key(
          label = "EXPORT",
          onClick = { state.export(plan) },
          modifier = modifier,
          icon = SampleIcons.Record,
          minHeight = RUN_KEY,
        )
      } else if (verdict is Verdict.Incapable) {
        verdict.withoutUnsupported?.let { fallback ->
          Key(
            label = "EXPORT WITHOUT THE UNSUPPORTED EFFECTS",
            onClick = { state.export(fallback.plan) },
            modifier = modifier,
            style = KeyStyle.Secondary,
            minHeight = RUN_KEY,
            horizontalPadding = 12.dp,
          )
        }
      }
      CheckKey(label = "CHECK AGAIN", state = state, modifier = modifier)
    }
  }
}

@Composable
private fun CheckKey(
  label: String,
  state: SampleAppState,
  modifier: Modifier,
) {
  Key(
    label = label,
    onClick = state::plan,
    modifier = modifier,
    style = KeyStyle.Secondary,
    enabled = !state.planning,
    minHeight = RUN_KEY,
    horizontalPadding = 12.dp,
  )
}

private fun Verdict.planOrNull(): ExportPlan? =
  when (this) {
    is Verdict.Capable -> plan
    is Verdict.Degraded -> plan
    is Verdict.Incapable -> null
  }

private fun ExportPlan.oneLine(): String {
  val size = "${output.size.width} x ${output.size.height}"
  val run = estimate.approximateDuration?.let { "about ${it.asClock()}" }
  return listOfNotNull(size, output.videoCodec.name, run).joinToString(" · ")
}

/**
 * What the device or the encoder changed, each line naming the setting, on an amber frame.
 */
@Composable
internal fun Adjustments(adjustments: List<Adjustment>) {
  AdjustedFrame {
    adjustments.forEach { adjustment ->
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
          "${adjustment.kind.name.lowercase()}: ${adjustment.requested} to ${adjustment.resolved}",
          style = MaterialTheme.typography.labelMedium,
          color = Tokens.amber,
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

/**
 * The amber frame anything adjusted sits in.
 */
@Composable
internal fun AdjustedFrame(content: @Composable () -> Unit) {
  Column(
    Modifier
      .fillMaxWidth()
      .background(Tokens.amber.copy(alpha = ADJUSTED_TINT))
      .border(2.dp, Tokens.amber)
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    content()
  }
}

/**
 * One error, its kind in the red and its message below, on a red frame.
 */
@Composable
internal fun ErrorLine(error: FilmstripError) {
  Column(
    Modifier
      .fillMaxWidth()
      .background(MaterialTheme.colorScheme.background)
      .border(2.dp, MaterialTheme.colorScheme.error)
      .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    Text(
      error::class.simpleName ?: "Error",
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.error,
    )
    Text(
      error.message,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

private const val RUN_TEXT_SIZE = 30
private const val ADJUSTED_TINT = 0.08f
private val RUN_KEY = 52.dp
private val CELL_HEIGHT = 18.dp
private val CELL_GAP = 4.dp

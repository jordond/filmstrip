package dev.jordond.filmstrip.sample.playground

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.effects.filters.vhs.TapeSpeed
import dev.jordond.filmstrip.effects.filters.vhs.Tracking
import dev.jordond.filmstrip.sample.SamplePreset
import dev.jordond.filmstrip.sample.samplePresets
import dev.jordond.filmstrip.sample.ui.SliderRow
import dev.jordond.filmstrip.sample.ui.Tokens

/**
 * The real footage the filters sample plays, which has the grain, faces and motion a look needs to show itself on.
 */
internal val tearsOfSteel: SamplePreset by lazy { samplePresets.first { it.file == "tears-of-steel-bridge-720p.mp4" } }

/**
 * The filters sample: real footage on a loop with one look on it at a time, its intensity, the tape, tracking, snow
 * and camcorder display for VHS, and the Kotlin that gives the same picture. NONE plays the untouched clip.
 *
 * @param filmstrip Opens the player, with the filters registered.
 * @param copy Puts the code listing on the clipboard.
 */
@Composable
internal fun FiltersPlayground(
  filmstrip: Filmstrip,
  copy: (String) -> Unit,
) {
  var settings by remember { mutableStateOf(FilterSettings()) }

  Column(
    modifier =
      Modifier
        .fillMaxSize()
        .background(Tokens.ground)
        .verticalScroll(rememberScrollState())
        .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    StatusLine(compare = false, filterSummary(settings), "OUT 16:9")
    Picture(
      filmstrip,
      tearsOfSteel,
    ) { filtersEdit(it, settings) }
    tearsOfSteel.attribution?.let { credit ->
      Text("${tearsOfSteel.name} · $credit", style = MaterialTheme.typography.bodySmall, color = Tokens.muted)
    }
    Group("LOOK") {
      ChipRow {
        FilterLook.entries.forEach { look ->
          Chip(look.label, settings.look == look) { settings = settings.copy(look = look) }
        }
      }
    }
    if (settings.look != FilterLook.None) {
      SliderRow(
        label = "Intensity",
        value = settings.intensity(),
        valueLabel = floatLiteral(settings.intensity()).removeSuffix("f"),
        range = 0f..1f,
        onValueChange = { settings = settings.withIntensity(roundToHundredths(it)) },
        onReset = { settings = settings.withIntensity(FilterSettings.FULL) },
      )
    }
    when (settings.look) {
      FilterLook.Vhs -> {
        Group("TAPE", "sharpest to softest") {
          ChipRow {
            TapeSpeed.entries.forEach { tape ->
              Chip(tape.name, settings.tape == tape) { settings = settings.copy(tape = tape) }
            }
          }
        }
        Group("TRACKING", "the band of mistracked tape") {
          ChipRow {
            TrackingMode.entries.forEach { mode ->
              Chip(mode.label, settings.tracking == mode) { settings = settings.copy(tracking = mode) }
            }
          }
        }
        if (settings.tracking != TrackingMode.Off) {
          SliderRow(
            label = "Snow",
            value = settings.snow,
            valueLabel = floatLiteral(settings.snow).removeSuffix("f"),
            range = 0f..1f,
            onValueChange = { settings = settings.copy(snow = roundToHundredths(it)) },
            onReset = { settings = settings.copy(snow = Tracking.DEFAULT_SNOW) },
          )
        }
        Group("CAMCORDER", "recorded on tape or drawn clean") {
          ChipRow {
            CamcorderMode.entries.forEach { mode ->
              Chip(mode.label, settings.camcorder.mode == mode) {
                settings = settings.copy(camcorder = settings.camcorder.copy(mode = mode))
              }
            }
          }
          if (settings.camcorder.mode != CamcorderMode.Off) {
            ChipRow {
              CamcorderPart.entries.forEach { part ->
                Chip(part.label, part in settings.camcorder.parts) {
                  settings = settings.copy(camcorder = settings.camcorder.toggle(part))
                }
              }
            }
          }
        }
      }
      FilterLook.Lut -> {
        Text(
          "The table is a teal and orange grade, read from a .cube file.",
          style = MaterialTheme.typography.bodySmall,
          color = Tokens.muted,
        )
      }
      else -> {
        Unit
      }
    }
    CodePanel(filtersCode(settings), copy)
  }
}

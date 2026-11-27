package dev.jordond.filmstrip.sample.ui.screen

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.jordond.filmstrip.effects.filters.camcorder.ClockStyle
import dev.jordond.filmstrip.effects.filters.camcorder.DateStyle
import dev.jordond.filmstrip.effects.filters.pixel.Pixel
import dev.jordond.filmstrip.effects.filters.pixel.PixelPalette
import dev.jordond.filmstrip.effects.filters.vhs.TapeClock
import dev.jordond.filmstrip.effects.filters.vhs.Tracking
import dev.jordond.filmstrip.sample.CamcorderState
import dev.jordond.filmstrip.sample.CamcorderTransport
import dev.jordond.filmstrip.sample.SampleAppState
import dev.jordond.filmstrip.sample.TapeFormat
import dev.jordond.filmstrip.sample.TapeTrackingMode
import dev.jordond.filmstrip.sample.asSeconds
import dev.jordond.filmstrip.sample.ui.ChipGroup
import dev.jordond.filmstrip.sample.ui.SwitchRow
import dev.jordond.filmstrip.sample.ui.asClock
import dev.jordond.filmstrip.sample.ui.formatPercent
import io.github.vinceglb.filekit.name

/**
 * The looks an edit can wear: tape, camcorder, film, bloom, CRT, pixel and a LUT. Each section adds one effect to the
 * composition when its switch is on.
 */
@Composable
internal fun LooksSections(state: SampleAppState) {
  val edit = state.edit

  ToolSection("Tape") {
    SwitchRow(
      label = "Tape look",
      checked = edit.tapeEnabled,
      onCheckedChange = {
        edit.tapeEnabled = it
        state.onEditChanged()
      },
    )
    if (edit.tapeEnabled || edit.tapeAudioEnabled) {
      ChipGroup(
        options = TapeFormat.entries.map { it.label to it },
        selected = edit.tapeFormat,
        onSelect = {
          edit.selectTapeFormat(it)
          state.onEditChanged()
        },
      )
    }
    if (edit.tapeEnabled) {
      GradeSlider(state, "Intensity", edit.tapeIntensity, formatPercent(edit.tapeIntensity), 0f..1f, 1f) {
        edit.tapeIntensity = it
      }
      ChipGroup(
        options = edit.tapeFormat.stocks,
        selected = edit.tapeStock,
        onSelect = {
          edit.tapeStock = it
          state.onEditChanged()
        },
      )
      ChipGroup(
        options = TapeTrackingMode.entries.map { it.name to it },
        selected = edit.tapeTracking,
        onSelect = {
          edit.tapeTracking = it
          state.onEditChanged()
        },
      )
      if (edit.tapeTracking != TapeTrackingMode.Off) {
        GradeSlider(state, "Snow", edit.tapeSnow, formatPercent(edit.tapeSnow), 0f..1f, Tracking.DEFAULT_SNOW) {
          edit.tapeSnow = it
        }
      }
      SwitchRow(
        label = "Settle in",
        checked = edit.tapeSettleEnabled,
        onCheckedChange = {
          edit.tapeSettleEnabled = it
          state.onEditChanged()
        },
      )
      if (edit.tapeSettleEnabled) {
        GradeSlider(state, "Starts at", edit.tapeSettleFrom, formatPercent(edit.tapeSettleFrom), 0f..1f, 1f) {
          edit.tapeSettleFrom = it
        }
        GradeSlider(
          state,
          "Settles over",
          edit.tapeSettleSeconds,
          edit.tapeSettleSeconds
            .asSeconds()
            .asClock(),
          0.5f..10f,
          2f,
        ) {
          edit.tapeSettleSeconds = it
        }
      }
      ChipGroup(
        options = TapeClock.entries.map { (if (it == TapeClock.Composition) "Frame clock" else it.name) to it },
        selected = edit.tapeClock,
        onSelect = {
          edit.tapeClock = it
          state.onEditChanged()
        },
      )
    }
    SwitchRow(
      label = "Tape audio",
      checked = edit.tapeAudioEnabled,
      onCheckedChange = {
        edit.tapeAudioEnabled = it
        state.onEditChanged()
      },
    )
    if (edit.tapeAudioEnabled) {
      ChipGroup(
        options = edit.tapeFormat.stocks,
        selected = edit.tapeAudioStock,
        onSelect = {
          edit.tapeAudioStock = it
          state.onEditChanged()
        },
      )
      GradeSlider(state, "Amount", edit.tapeAudioIntensity, formatPercent(edit.tapeAudioIntensity), 0f..1f, 1f) {
        edit.tapeAudioIntensity = it
      }
      GradeSlider(state, "Hiss", edit.tapeAudioHiss, formatPercent(edit.tapeAudioHiss), 0f..1f, 1f) {
        edit.tapeAudioHiss = it
      }
      GradeSlider(state, "Band limit", edit.tapeAudioBandLimit, formatPercent(edit.tapeAudioBandLimit), 0f..1f, 1f) {
        edit.tapeAudioBandLimit = it
      }
      GradeSlider(state, "Wow", edit.tapeAudioWow, formatPercent(edit.tapeAudioWow), 0f..1f, 1f) {
        edit.tapeAudioWow = it
      }
      GradeSlider(state, "Flutter", edit.tapeAudioFlutter, formatPercent(edit.tapeAudioFlutter), 0f..1f, 1f) {
        edit.tapeAudioFlutter = it
      }
      SwitchRow(
        label = "Mono",
        checked = edit.tapeAudioMono,
        onCheckedChange = {
          edit.tapeAudioMono = it
          state.onEditChanged()
        },
      )
    }
  }

  ToolSection("Camcorder") {
    SwitchRow(
      label = "Camcorder",
      checked = edit.camcorderDisplay.enabled,
      onCheckedChange = {
        edit.camcorderDisplay.enabled = it
        state.onEditChanged()
      },
    )
    if (edit.camcorderDisplay.enabled) {
      ChipGroup(
        options = CamcorderTransport.entries.map { it.name to it },
        selected = edit.camcorderDisplay.transport,
        onSelect = {
          edit.camcorderDisplay.transport = it
          state.onEditChanged()
        },
      )
      SwitchRow(label = "Date and clock", checked = edit.camcorderDisplay.dateAndClock, onCheckedChange = {
        edit.camcorderDisplay.dateAndClock = it
        state.onEditChanged()
      })
      if (edit.camcorderDisplay.dateAndClock) {
        ChipGroup(
          options =
            listOf(
              "M/D/Y" to DateStyle.MonthDayYear,
              "D/M/Y" to DateStyle.DayMonthYear,
              "Y/M/D" to DateStyle.YearMonthDay,
            ),
          selected = edit.camcorderDisplay.dateStyle,
          onSelect = {
            edit.camcorderDisplay.dateStyle = it
            state.onEditChanged()
          },
        )
        ChipGroup(
          options = listOf("12 hour" to ClockStyle.TwelveHour, "24 hour" to ClockStyle.TwentyFourHour),
          selected = edit.camcorderDisplay.clockStyle,
          onSelect = {
            edit.camcorderDisplay.clockStyle = it
            state.onEditChanged()
          },
        )
        ChipGroup(
          options = CamcorderState.RecordedDates,
          selected = edit.camcorderDisplay.recordedAt,
          onSelect = {
            edit.camcorderDisplay.recordedAt = it
            state.onEditChanged()
          },
        )
      }
      SwitchRow(label = "Counter", checked = edit.camcorderDisplay.counter, onCheckedChange = {
        edit.camcorderDisplay.counter = it
        state.onEditChanged()
      })
      SwitchRow(label = "Battery", checked = edit.camcorderDisplay.battery, onCheckedChange = {
        edit.camcorderDisplay.battery = it
        state.onEditChanged()
      })
      SwitchRow(label = "Tape speed", checked = edit.camcorderDisplay.tapeBadge, onCheckedChange = {
        edit.camcorderDisplay.tapeBadge = it
        state.onEditChanged()
      })
      OutlinedTextField(
        value = edit.camcorderDisplay.label,
        onValueChange = {
          edit.camcorderDisplay.label = it
          state.onEditChanged()
        },
        label = { Text("Label") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
      SwitchRow(label = "Recorded on tape", checked = edit.camcorderDisplay.burned, onCheckedChange = {
        edit.camcorderDisplay.burned = it
        state.onEditChanged()
      })
    }
  }

  ToolSection("Film") {
    SwitchRow(
      label = "Film",
      checked = edit.filmEnabled,
      onCheckedChange = {
        edit.filmEnabled = it
        state.onEditChanged()
      },
    )
    if (edit.filmEnabled) {
      GradeSlider(state, "Grain", edit.filmGrain, formatPercent(edit.filmGrain), 0f..1f, 0.5f) {
        edit.filmGrain = it
      }
      GradeSlider(state, "Halation", edit.filmHalation, formatPercent(edit.filmHalation), 0f..1f, 0.5f) {
        edit.filmHalation = it
      }
      GradeSlider(state, "Leak", edit.filmLeak, formatPercent(edit.filmLeak), 0f..1f, 0f) {
        edit.filmLeak = it
      }
      GradeSlider(state, "Weave", edit.filmWeave, formatPercent(edit.filmWeave), 0f..1f, 0f) {
        edit.filmWeave = it
      }
      GradeSlider(state, "Flicker", edit.filmFlicker, formatPercent(edit.filmFlicker), 0f..1f, 0f) {
        edit.filmFlicker = it
      }
      GradeSlider(state, "Dust", edit.filmDust, formatPercent(edit.filmDust), 0f..1f, 0f) {
        edit.filmDust = it
      }
      GradeSlider(state, "Gate", edit.filmGate, formatPercent(edit.filmGate), 0f..1f, 0f) {
        edit.filmGate = it
      }
      GradeSlider(state, "Breath", edit.filmBreath, formatPercent(edit.filmBreath), 0f..1f, 0f) {
        edit.filmBreath = it
      }
      GradeSlider(state, "Fade", edit.filmFade, formatPercent(edit.filmFade), 0f..1f, 0f) {
        edit.filmFade = it
      }
    }
  }

  ToolSection("Night vision") {
    SwitchRow(
      label = "Night vision",
      checked = edit.nightVisionEnabled,
      onCheckedChange = {
        edit.nightVisionEnabled = it
        state.onEditChanged()
      },
    )
    if (edit.nightVisionEnabled) {
      GradeSlider(
        state,
        "Intensity",
        edit.nightVisionIntensity,
        formatPercent(edit.nightVisionIntensity),
        0f..1f,
        1f,
      ) {
        edit.nightVisionIntensity = it
      }
    }
  }

  ToolSection("Glitch") {
    SwitchRow(
      label = "Glitch",
      checked = edit.glitchEnabled,
      onCheckedChange = {
        edit.glitchEnabled = it
        state.onEditChanged()
      },
    )
    if (edit.glitchEnabled) {
      GradeSlider(state, "Intensity", edit.glitchIntensity, formatPercent(edit.glitchIntensity), 0f..1f, 1f) {
        edit.glitchIntensity = it
      }
      GradeSlider(state, "Broken blocks", edit.glitchBlocks, formatPercent(edit.glitchBlocks), 0f..1f, 0f) {
        edit.glitchBlocks = it
      }
    }
  }

  ToolSection("Bloom") {
    SwitchRow(
      label = "Bloom",
      checked = edit.bloomEnabled,
      onCheckedChange = {
        edit.bloomEnabled = it
        state.onEditChanged()
      },
    )
    if (edit.bloomEnabled) {
      GradeSlider(state, "Intensity", edit.bloomIntensity, formatPercent(edit.bloomIntensity), 0f..1f, 0.5f) {
        edit.bloomIntensity = it
      }
      GradeSlider(state, "Threshold", edit.bloomThreshold, formatPercent(edit.bloomThreshold), 0f..1f, 0.7f) {
        edit.bloomThreshold = it
      }
      GradeSlider(state, "Spread", edit.bloomSpread, formatPercent(edit.bloomSpread), 0f..1f, 0.5f) {
        edit.bloomSpread = it
      }
    }
  }

  ToolSection("CRT") {
    SwitchRow(
      label = "CRT",
      checked = edit.crtEnabled,
      onCheckedChange = {
        edit.crtEnabled = it
        state.onEditChanged()
      },
    )
    if (edit.crtEnabled) {
      GradeSlider(state, "Intensity", edit.crtIntensity, formatPercent(edit.crtIntensity), 0f..1f, 1f) {
        edit.crtIntensity = it
      }
    }
  }

  ToolSection("Pixel") {
    SwitchRow(
      label = "Pixel",
      checked = edit.pixelLook.enabled,
      onCheckedChange = {
        edit.pixelLook.enabled = it
        state.onEditChanged()
      },
    )
    if (edit.pixelLook.enabled) {
      GradeSlider(
        state,
        "Cells",
        edit.pixelLook.cells,
        edit.pixelLook.cells
          .toInt()
          .toString(),
        Pixel.MIN_CELLS.toFloat()..PIXEL_CELLS_MAX,
        Pixel.DEFAULT_CELLS.toFloat(),
      ) {
        edit.pixelLook.cells = it
      }
      ChipGroup(
        options =
          listOf<Pair<String, PixelPalette?>>(
            "Levels" to null,
            "Game Boy" to PixelPalette.GameBoy,
            "CGA" to PixelPalette.Cga,
          ),
        selected = edit.pixelLook.palette,
        onSelect = {
          edit.pixelLook.palette = it
          state.onEditChanged()
        },
      )
      if (edit.pixelLook.palette == null) {
        val levels = edit.pixelLook.levelCount
        GradeSlider(
          state,
          "Levels",
          edit.pixelLook.levels,
          if (levels ==
            0
          ) {
            "Off"
          } else {
            "$levels"
          },
          0f..PIXEL_LEVELS_MAX,
          0f,
        ) {
          edit.pixelLook.levels = it
        }
      }
      GradeSlider(state, "Dither", edit.pixelLook.dither, formatPercent(edit.pixelLook.dither), 0f..1f, 0f) {
        edit.pixelLook.dither = it
      }
    }
  }

  ToolSection("LUT") {
    SwitchRow(
      label = "Teal and orange",
      checked = edit.lutEnabled,
      onCheckedChange = {
        edit.lutEnabled = it
        state.onEditChanged()
      },
    )
    if (edit.lutEnabled) {
      GradeSlider(state, "Intensity", edit.lutIntensity, formatPercent(edit.lutIntensity), 0f..1f, 1f) {
        edit.lutIntensity = it
      }
    }
  }
}

// The coarse end of the cell range, where the look reads as pixel art.
private const val PIXEL_CELLS_MAX = 360f
private const val PIXEL_LEVELS_MAX = Pixel.MAX_LEVELS.toFloat()

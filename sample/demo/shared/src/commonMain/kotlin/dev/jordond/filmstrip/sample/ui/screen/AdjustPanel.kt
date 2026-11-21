package dev.jordond.filmstrip.sample.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.effects.color.ColorMatrix
import dev.jordond.filmstrip.sample.EditState
import dev.jordond.filmstrip.sample.SampleAppState
import dev.jordond.filmstrip.sample.ui.ChipGroup
import dev.jordond.filmstrip.sample.ui.formatDegrees
import dev.jordond.filmstrip.sample.ui.formatPercent

@Composable
internal fun AdjustPanel(state: SampleAppState) {
  val edit = state.edit

  ToolSection("Tone") {
    Text(
      "Every slider is a color matrix, and a run of them is folded into one before anything clamps, " +
        "so a brightness above 100% and a contrast that pulls it back cancel out.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    GradeSlider(state, "Brightness", edit.brightness, formatPercent(edit.brightness), 0f..2f, 1f) {
      edit.brightness = it
    }
    GradeSlider(state, "Contrast", edit.contrast, formatPercent(edit.contrast), 0f..2f, 1f) {
      edit.contrast = it
    }
    GradeSlider(state, "Saturation", edit.saturation, formatPercent(edit.saturation), 0f..2f, 1f) {
      edit.saturation = it
    }
  }

  ToolSection("Tint") {
    GradeSlider(state, "Hue", edit.hueDegrees, formatDegrees(edit.hueDegrees), -180f..180f, 0f) {
      edit.hueDegrees = it
    }
    GradeSlider(state, "Sepia", edit.sepia, formatPercent(edit.sepia), 0f..1f, 0f) {
      edit.sepia = it
    }
    GradeSlider(state, "Invert", edit.invert, formatPercent(edit.invert), 0f..1f, 0f) {
      edit.invert = it
    }
  }

  ToolSection("Lens") {
    GradeSlider(state, "Blur", edit.blur, formatPercent(edit.blur), 0f..0.1f, 0f) {
      edit.blur = it
    }
    GradeSlider(state, "Vignette", edit.vignette, formatPercent(edit.vignette), 0f..1f, 0f) {
      edit.vignette = it
    }
    if (edit.vignette != 0f) {
      GradeSlider(
        state,
        "Vignette radius",
        edit.vignetteRadius,
        formatPercent(edit.vignetteRadius),
        0f..1.5f,
        EditState.VIGNETTE_RADIUS,
      ) {
        edit.vignetteRadius = it
      }
      GradeSlider(
        state,
        "Vignette softness",
        edit.vignetteSoftness,
        formatPercent(edit.vignetteSoftness),
        0f..1f,
        EditState.VIGNETTE_SOFTNESS,
      ) {
        edit.vignetteSoftness = it
      }
    }
  }

  LooksSections(state)

  ToolSection("Channels") {
    GradeSlider(state, "Red", edit.channelRed, formatPercent(edit.channelRed), 0f..2f, 1f) {
      edit.channelRed = it
    }
    GradeSlider(state, "Green", edit.channelGreen, formatPercent(edit.channelGreen), 0f..2f, 1f) {
      edit.channelGreen = it
    }
    GradeSlider(state, "Blue", edit.channelBlue, formatPercent(edit.channelBlue), 0f..2f, 1f) {
      edit.channelBlue = it
    }
  }

  ToolSection("Matrix") {
    Text(
      "Each output channel is a weighted sum of the three inputs plus a bias. It runs last, after " +
        "everything above.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ChipGroup(
      options = matrixLooks,
      selected = edit.customMatrix,
      onSelect = {
        edit.customMatrix = it
        state.onEditChanged()
      },
    )
    MatrixGrid(
      matrix = edit.customMatrix,
      onMatrixChange = {
        edit.customMatrix = it
        state.onEditChanged()
      },
    )
  }
}

private val matrixLooks: List<Pair<String, ColorMatrix>> =
  listOf(
    "Identity" to ColorMatrix.Identity,
    "Swap R/B" to ColorMatrix(rr = 0f, rb = 1f, br = 1f, bb = 0f),
    "Warm" to ColorMatrix(rr = 1.1f, rBias = 0.03f, bb = 0.9f),
    "Cool" to ColorMatrix(rr = 0.9f, bb = 1.1f, bBias = 0.03f),
    "Lift" to ColorMatrix(rr = 0.8f, rBias = 0.1f, gg = 0.8f, gBias = 0.1f, bb = 0.8f, bBias = 0.1f),
  )

/**
 * The matrix as three rows of four cells, one output channel per row with its bias last.
 */
@Composable
private fun MatrixGrid(
  matrix: ColorMatrix,
  onMatrixChange: (ColorMatrix) -> Unit,
) {
  val entries = matrix.entries

  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
      Box(Modifier.width(MATRIX_LABEL_WIDTH))
      listOf("R", "G", "B", "Bias").forEach { input ->
        Text(
          text = input,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.weight(1f),
        )
      }
    }
    listOf("R", "G", "B").forEachIndexed { row, output ->
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = output,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.width(MATRIX_LABEL_WIDTH),
        )
        repeat(MATRIX_COLUMNS) { column ->
          val index = row * MATRIX_COLUMNS + column
          MatrixCell(
            value = entries[index],
            onValueChange = { value ->
              onMatrixChange(colorMatrixOfEntries(entries.toMutableList().also { it[index] = value }))
            },
            modifier = Modifier.weight(1f),
          )
        }
      }
    }
  }
}

/**
 * One editable entry. What is typed stays as typed until it parses, and the field only rewrites it
 * when the value changes from outside, so a half-typed number is not snatched away.
 *
 * Text that never parsed is written back when the field loses focus. Nothing else can restore it: a
 * reset that lands on the value the cell already held changes nothing for the effect below to fire
 * on, and the cell would sit blank over a value it is not showing.
 */
@Composable
private fun MatrixCell(
  value: Float,
  onValueChange: (Float) -> Unit,
  modifier: Modifier = Modifier,
) {
  var text by remember { mutableStateOf(value.toString()) }
  var typed by remember { mutableStateOf(value) }

  LaunchedEffect(value) {
    if (value != typed) {
      typed = value
      text = value.toString()
    }
  }

  OutlinedTextField(
    value = text,
    onValueChange = { input ->
      text = input
      input.toFloatOrNull()?.let { parsed ->
        typed = parsed
        onValueChange(parsed)
      }
    },
    singleLine = true,
    textStyle = MaterialTheme.typography.bodySmall,
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    modifier =
      modifier.onFocusChanged { focus ->
        if (!focus.isFocused && text.toFloatOrNull() != value) {
          typed = value
          text = value.toString()
        }
      },
    colors =
      OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
      ),
  )
}

private val ColorMatrix.entries: List<Float>
  get() = listOf(rr, rg, rb, rBias, gr, gg, gb, gBias, br, bg, bb, bBias)

private fun colorMatrixOfEntries(entries: List<Float>): ColorMatrix =
  ColorMatrix(
    rr = entries[0],
    rg = entries[1],
    rb = entries[2],
    rBias = entries[3],
    gr = entries[4],
    gg = entries[5],
    gb = entries[6],
    gBias = entries[7],
    br = entries[8],
    bg = entries[9],
    bb = entries[10],
    bBias = entries[11],
  )

private const val MATRIX_COLUMNS = 4
private val MATRIX_LABEL_WIDTH = 16.dp

package dev.jordond.filmstrip.sample.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.jordond.filmstrip.sample.OverlayMotionState
import dev.jordond.filmstrip.sample.SampleAppState
import dev.jordond.filmstrip.sample.SlideEdge
import dev.jordond.filmstrip.sample.asSeconds
import dev.jordond.filmstrip.sample.ui.ChipGroup
import dev.jordond.filmstrip.sample.ui.SampleRangeSlider
import dev.jordond.filmstrip.sample.ui.SliderRow
import dev.jordond.filmstrip.sample.ui.asClock
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

@Composable
internal fun GradeSlider(
  state: SampleAppState,
  label: String,
  value: Float,
  valueLabel: String,
  range: ClosedFloatingPointRange<Float>,
  default: Float,
  onValueChange: (Float) -> Unit,
) {
  SliderRow(
    label = label,
    value = value,
    valueLabel = valueLabel,
    range = range,
    onValueChange = {
      onValueChange(it)
      state.onEditChanged()
    },
    onReset = {
      onValueChange(default)
      state.onEditChanged()
    },
  )
}

@Composable
internal fun WindowSlider(
  start: Float,
  end: Float,
  duration: Float,
  onChange: (Float, Float) -> Unit,
) {
  Column(Modifier.fillMaxWidth()) {
    SampleRangeSlider(
      value = start.coerceIn(0f, duration)..end.coerceIn(0f, duration),
      onValueChange = { range -> onChange(range.start, range.endInclusive) },
      valueRange = 0f..duration,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(
        start.asSeconds().asClock(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        end.asSeconds().asClock(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

internal val Palette =
  listOf(
    0xFFFFFFFF.toInt(),
    0xFF000000.toInt(),
    0xFFFFB454.toInt(),
    0xFF5FD3A3.toInt(),
    0xFF8E9BFF.toInt(),
    0xFFFF6F6F.toInt(),
  )

internal val Plates =
  listOf(
    0xFF000000.toInt(),
    0xFF1A1A21.toInt(),
    0xFFFFFFFF.toInt(),
    0xFFFFB454.toInt(),
    0xFF8E9BFF.toInt(),
    0xFF5FD3A3.toInt(),
  )

/**
 * The fade and slide controls for one overlay.
 *
 * @param timed Whether the overlay has a visible window, which the out half of a fade and a slide out need.
 */
@Composable
internal fun MotionSection(
  state: SampleAppState,
  motion: OverlayMotionState,
  timed: Boolean,
) {
  ToolSection("Motion") {
    GradeSlider(state, "Fade in", motion.fadeInSeconds, motion.fadeInSeconds.secondsLabel(), 0f..3f, 0f) {
      motion.fadeInSeconds = it
    }
    if (timed) {
      GradeSlider(state, "Fade out", motion.fadeOutSeconds, motion.fadeOutSeconds.secondsLabel(), 0f..3f, 0f) {
        motion.fadeOutSeconds = it
      }
    }
    Text("Slide in from", style = MaterialTheme.typography.labelMedium)
    ChipGroup(
      options = listOf("None" to null) + SlideEdge.entries.map { it.name to it },
      selected = motion.slideIn,
      onSelect = {
        motion.slideIn = it
        state.onEditChanged()
      },
    )
    if (timed) {
      Text("Slide out to", style = MaterialTheme.typography.labelMedium)
      ChipGroup(
        options = listOf("None" to null) + SlideEdge.entries.map { it.name to it },
        selected = motion.slideOut,
        onSelect = {
          motion.slideOut = it
          state.onEditChanged()
        },
      )
    }
    if (motion.slideIn != null || motion.slideOut != null) {
      GradeSlider(state, "Slide time", motion.slideSeconds, motion.slideSeconds.secondsLabel(), 0.1f..3f, 0.5f) {
        motion.slideSeconds = it
      }
    }
  }
}

private fun Float.secondsLabel(): String = "${(this * 10).roundToInt() / 10f}s"

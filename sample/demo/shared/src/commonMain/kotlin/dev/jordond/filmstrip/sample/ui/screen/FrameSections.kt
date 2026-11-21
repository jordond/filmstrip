package dev.jordond.filmstrip.sample.ui.screen

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.sample.FrameMode
import dev.jordond.filmstrip.sample.SampleAppState
import dev.jordond.filmstrip.sample.ui.ChipGroup

/**
 * The output frame and end card controls, under the fill in the frame tool.
 *
 * The output frame picks the shape the clip lands in, and the end card holds a solid color after the clip.
 */
@Composable
internal fun FrameSections(state: SampleAppState) {
  val edit = state.edit

  ToolSection("Output frame") {
    Text(
      "The frame the clip lands in. Auto takes the clip's own size, and the fill covers whatever the clip leaves.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ChipGroup(
      options = listOf("Auto" to FrameMode.Auto, "Aspect" to FrameMode.Aspect, "Fixed" to FrameMode.Fixed),
      selected = edit.frameMode,
      onSelect = {
        edit.frameMode = it
        state.onEditChanged()
      },
    )
  }

  when (edit.frameMode) {
    FrameMode.Auto -> {}
    FrameMode.Aspect -> {
      ToolSection("Frame aspect") {
        ChipGroup(
          options =
            listOf(
              "9:16" to AspectRatio.Portrait,
              "1:1" to AspectRatio.Square,
              "4:5" to AspectRatio.Feed,
              "16:9" to AspectRatio.Landscape,
            ),
          selected = edit.frameAspect,
          onSelect = {
            edit.frameAspect = it
            state.onEditChanged()
          },
        )
      }
    }
    FrameMode.Fixed -> {
      ToolSection("Frame size") {
        ChipGroup(
          options = FixedSizes.map { "${it.width} x ${it.height}" to it },
          selected = edit.frameSize,
          onSelect = {
            edit.frameSize = it
            state.onEditChanged()
          },
        )
      }
    }
  }
}

private val FixedSizes =
  listOf(
    Size(1920, 1080),
    Size(1080, 1920),
    Size(1080, 1080),
    Size(1280, 720),
  )

@file:OptIn(ExperimentalFilmstripApi::class)

package dev.jordond.filmstrip.sample.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.ExperimentalFilmstripApi
import dev.jordond.filmstrip.compose.ui.CropOverlayDefaults
import dev.jordond.filmstrip.compose.ui.VideoStageScope
import dev.jordond.filmstrip.compose.ui.component.CropOverlay
import dev.jordond.filmstrip.compose.ui.interaction.CropConstraint
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.motion.Easing
import dev.jordond.filmstrip.sample.ClipEdit
import dev.jordond.filmstrip.sample.PanEnd
import dev.jordond.filmstrip.sample.SampleAppState
import dev.jordond.filmstrip.sample.ui.Chip
import dev.jordond.filmstrip.sample.ui.ChipGroup
import dev.jordond.filmstrip.sample.ui.SwitchRow

/**
 * The pan and zoom of [clip]: whether it runs, which of its two rectangles is on the stage, and how the travel
 * between them is paced.
 *
 * Picking a rectangle brings the playhead onto the clip and holds its frame still under the rectangle. Watch puts
 * the motion back.
 */
@Composable
internal fun KenBurnsSection(
  state: SampleAppState,
  clip: ClipEdit,
) {
  ToolSection("Pan and zoom") {
    SwitchRow(
      label = "Ken Burns",
      supporting = "Travels from one region of the frame to another over the clip",
      checked = clip.panEnabled,
      onCheckedChange = {
        clip.panEnabled = it
        if (!it) state.panEnd = null
        state.onEditChanged()
      },
    )
    if (!clip.panEnabled) return@ToolSection

    Note("Drag a rectangle on the picture. It keeps the frame's shape, so the picture is never stretched")
    ChipGroup(
      options = listOf("Watch" to null, "Start frame" to PanEnd.Start, "End frame" to PanEnd.End),
      selected = state.panEnd,
      onSelect = { end ->
        if (end != null) state.focusClip(clip.id)
        state.panEnd = end
      },
    )
    ChipGroup(
      options =
        listOf(
          "Linear" to Easing.Linear,
          "Ease in" to Easing.EaseIn,
          "Ease out" to Easing.EaseOut,
          "Ease in and out" to Easing.EaseInOut,
        ),
      selected = clip.panEasing,
      onSelect = {
        clip.panEasing = it
        state.onEditChanged()
      },
    )
    Chip(
      label = "Swap the ends",
      selected = false,
      role = Role.Button,
      onClick = {
        val from = clip.panFrom
        clip.panFrom = clip.panTo
        clip.panTo = from
        state.onEditChanged()
      },
    )
  }
}

/**
 * The rectangle of the selected clip's pan that [end] names, dragged over the stage, with the other end drawn
 * as a dashed outline under it.
 */
@Composable
internal fun VideoStageScope.PanOverlay(
  state: SampleAppState,
  end: PanEnd,
) {
  val clip = state.sequence.selectedClip ?: return
  val other = if (end == PanEnd.Start) clip.panTo else clip.panFrom
  val outline = MaterialTheme.colorScheme.onSurfaceVariant
  val stage = frame

  Canvas(Modifier.fillMaxSize()) {
    val view = stage.toView(other)
    drawRect(
      color = outline,
      topLeft = view.topLeft,
      size = view.size,
      style =
        Stroke(
          width = OUTLINE_WIDTH.toPx(),
          pathEffect = PathEffect.dashPathEffect(floatArrayOf(DASH.toPx(), DASH.toPx())),
        ),
    )
  }
  CropOverlay(
    rect = if (end == PanEnd.Start) clip.panFrom else clip.panTo,
    onRectChange = { rect: NormalizedRect ->
      if (end == PanEnd.Start) clip.panFrom = rect else clip.panTo = rect
      state.onEditChanged()
    },
    constraint = FrameShaped,
    colors = sampleCropColors(),
  )
}

// A ratio of one in frame fractions is the frame's own shape, whatever that is in pixels.
private val FrameShaped = CropConstraint.FixedAspect(ratio = 1f, minWidth = CropOverlayDefaults.MinWidth)
private val OUTLINE_WIDTH = 2.dp
private val DASH = 8.dp

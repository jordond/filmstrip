package dev.jordond.filmstrip.docs.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.compose.overlay.ComposedOverlayState
import dev.jordond.filmstrip.compose.overlay.OverlayBaker
import dev.jordond.filmstrip.compose.overlay.OverlayCanvas
import dev.jordond.filmstrip.compose.overlay.OverlayCaptureHost
import dev.jordond.filmstrip.compose.overlay.OverlayCapturer
import dev.jordond.filmstrip.compose.overlay.OverlayTransform
import dev.jordond.filmstrip.compose.overlay.planWithOverlays
import dev.jordond.filmstrip.compose.overlay.rememberComposedOverlayState
import dev.jordond.filmstrip.compose.rememberFilmstripPlayer
import dev.jordond.filmstrip.compose.ui.ComposedOverlayHandles
import dev.jordond.filmstrip.compose.ui.VideoStage
import dev.jordond.filmstrip.compose.ui.component.ComposedOverlay
import dev.jordond.filmstrip.compose.ui.interaction.composedOverlayKeys
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.fade
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.VideoPlayer
import kotlin.time.Duration.Companion.seconds

// region title
@Composable
internal fun TitleCard() {
  BasicText(
    text = "Day one",
    style = TextStyle(color = Color.White, fontSize = 32.sp),
    modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(12.dp),
  )
}
// endregion

@Composable
internal fun StoryEditor(
  filmstrip: Filmstrip,
  source: MediaSource,
  aspect: Float,
) {
  // region live
  val composition = remember(source) { compositionOf { clip(source) } }
  val player = rememberFilmstripPlayer(filmstrip, composition)
  val overlays = rememberComposedOverlayState()
  var elements by remember {
    mutableStateOf(listOf(OverlayTransform(center = Anchor(0.5f, 0.3f)), OverlayTransform(center = Anchor(0.5f, 0.7f))))
  }
  var selected by remember { mutableStateOf(0) }

  VideoStage(player = player, outputAspect = aspect, overlays = overlays) {
    elements.forEachIndexed { index, transform ->
      ComposedOverlay(
        transform = transform,
        onTransformChange = { next -> elements = elements.toMutableList().also { it[index] = next } },
        selected = index == selected,
        onSelect = { selected = index },
      ) { TitleCard() }
    }
  }
  // endregion
}

@Composable
internal fun KeyedStage(
  transform: OverlayTransform,
  aspect: Float,
  onTransformChange: (OverlayTransform) -> Unit,
) {
  // region keys
  val focus = remember { FocusRequester() }

  Box(Modifier.focusRequester(focus).composedOverlayKeys(transform, onTransformChange)) {
    VideoStage(player = null, outputAspect = aspect) {
      ComposedOverlay(
        transform = transform,
        onTransformChange = onTransformChange,
        selected = true,
        onSelect = { focus.requestFocus() },
        handles = ComposedOverlayHandles.Auto,
      ) { TitleCard() }
    }
  }
  // endregion
}

@Composable
internal fun TimedStage(
  player: VideoPlayer,
  overlays: ComposedOverlayState,
  title: OverlayTransform,
  aspect: Float,
  onTitleChange: (OverlayTransform) -> Unit,
) {
  // region timed
  var selected by remember { mutableStateOf(false) }

  VideoStage(player = player, outputAspect = aspect, overlays = overlays) {
    ComposedOverlay(
      transform = title,
      onTransformChange = onTitleChange,
      visibleDuring = TimeRange(1.seconds, 4.seconds),
      animation = fade(inDuration = 0.5.seconds),
      selected = selected,
      onSelect = { selected = true },
    ) { TitleCard() }
  }
  // endregion
}

@Composable
internal fun WritingStage(
  overlays: ComposedOverlayState,
  caption: OverlayTransform,
  text: String,
  aspect: Float,
  onTextChange: (String) -> Unit,
) {
  // region writing
  VideoStage(player = null, outputAspect = aspect, overlays = overlays) {
    ComposedOverlay(transform = caption, onTransformChange = { }, baked = false, enabled = false) {
      BasicTextField(value = text, onValueChange = onTextChange)
    }
  }
  // endregion
}

internal suspend fun exportWithTitles(
  filmstrip: Filmstrip,
  composition: Composition,
  overlays: ComposedOverlayState,
): ExportPlan? {
  // region export
  val plan =
    when (val verdict = filmstrip.planWithOverlays(composition, ExportSpec.Upload, overlays)) {
      is Verdict.Capable -> {
        verdict.plan
      }
      is Verdict.Degraded -> {
        verdict.adjustments.forEach { println("Changed: ${it.message}") }
        verdict.plan
      }
      is Verdict.Incapable -> {
        verdict.reasons.forEach { println("Can't export: ${it.message}") }
        null
      }
    }
  // endregion
  return plan
}

// region viewmodel
class StoryExporter(
  private val filmstrip: Filmstrip,
  private val overlays: OverlayBaker,
) {
  suspend fun plan(composition: Composition): Verdict =
    filmstrip.planWithOverlays(composition, ExportSpec.Upload, overlays)
}

class StoryViewModel(
  filmstrip: Filmstrip,
) {
  // The screen passes this to its VideoStage.
  val overlays = ComposedOverlayState()

  val exporter = StoryExporter(filmstrip, overlays)
}
// endregion

internal suspend fun planWithAFakeBaker(
  filmstrip: Filmstrip,
  composition: Composition,
  picture: ImageOverlay,
): Verdict {
  // region test
  val exporter = StoryExporter(filmstrip, overlays = { output -> listOf(picture) })

  val verdict = exporter.plan(composition)
  // endregion
  return verdict
}

@Composable
internal fun TemplateHost(capturer: OverlayCapturer) {
  // region host
  OverlayCaptureHost(capturer)
  // endregion
}

internal suspend fun withTitle(
  capturer: OverlayCapturer,
  composition: Composition,
  title: OverlayTransform,
  canvas: OverlayCanvas,
  output: Size,
): Composition {
  // region capture
  val baked =
    capturer.capture(
      transform = title,
      canvas = canvas,
      output = output,
      visibleDuring = TimeRange(0.seconds, 4.seconds),
    ) { TitleCard() }

  val export =
    if (baked == null) composition else composition.withEffects(composition.effects + baked)
  // endregion
  return export
}

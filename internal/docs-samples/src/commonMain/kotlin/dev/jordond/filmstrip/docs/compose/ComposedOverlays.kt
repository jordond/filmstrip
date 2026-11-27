package dev.jordond.filmstrip.docs.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.compose.overlay.OverlayCanvas
import dev.jordond.filmstrip.compose.overlay.OverlayCaptureHost
import dev.jordond.filmstrip.compose.overlay.OverlayCapturer
import dev.jordond.filmstrip.compose.overlay.OverlayTransform
import dev.jordond.filmstrip.compose.overlay.rememberOverlayCapturer
import dev.jordond.filmstrip.compose.rememberFilmstripPlayer
import dev.jordond.filmstrip.compose.ui.VideoStage
import dev.jordond.filmstrip.compose.ui.component.ComposedOverlay
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSource
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
  val info by player.previewInfo.collectAsState()
  val output = info.outputSize
  val canvas = remember { OverlayCanvas() }
  var elements by remember {
    mutableStateOf(listOf(OverlayTransform(center = Anchor(0.5f, 0.3f)), OverlayTransform(center = Anchor(0.5f, 0.7f))))
  }
  var selected by remember { mutableStateOf(0) }

  val capturer = rememberOverlayCapturer()
  OverlayCaptureHost(capturer)

  VideoStage(player = player, outputAspect = aspect) {
    elements.forEachIndexed { index, transform ->
      ComposedOverlay(
        transform = transform,
        canvas = canvas,
        output = output,
        onTransformChange = { next -> elements = elements.toMutableList().also { it[index] = next } },
        selected = index == selected,
        onSelect = { selected = index },
      ) { TitleCard() }
    }
  }
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

internal suspend fun exportWithTitles(
  filmstrip: Filmstrip,
  capturer: OverlayCapturer,
  composition: Composition,
  titles: List<OverlayTransform>,
  canvas: OverlayCanvas,
): ExportPlan? {
  // region export
  val first =
    when (val verdict = filmstrip.plan(composition, ExportSpec.Upload)) {
      is Verdict.Capable -> verdict.plan
      is Verdict.Degraded -> verdict.plan
      is Verdict.Incapable -> return null
    }

  val baked =
    titles.mapNotNull { title ->
      capturer.capture(transform = title, canvas = canvas, output = first.output.size) { TitleCard() }
    }
  if (baked.isEmpty()) return first

  val plan =
    when (val verdict = filmstrip.plan(composition.withEffects(composition.effects + baked), ExportSpec.Upload)) {
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

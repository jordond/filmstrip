package dev.jordond.filmstrip.compose.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effects.overlay.OverlayAnimation
import dev.jordond.filmstrip.geometry.Size
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// Frames only come when the test clock moves, and the encode runs on a real thread, so this steps both.
@OptIn(ExperimentalTestApi::class)
internal suspend fun <T> ComposeUiTest.pumped(
  work: Deferred<T>,
  onFrame: (Int) -> Unit = {},
): T {
  var frame = 0
  while (!work.isCompleted) {
    pumpOnce()
    onFrame(++frame)
  }
  return work.await()
}

@OptIn(ExperimentalTestApi::class)
internal suspend fun ComposeUiTest.pumpOnce() {
  mainClock.advanceTimeByFrame()
  awaitIdle()
  withContext(Dispatchers.Default) { delay(1) }
}

// 200 by 60 dp, which is 600 by 180 layout pixels at the overlay layout density.
@Composable
internal fun Plate(color: Color) {
  Box(Modifier.size(200.dp, 60.dp).background(color))
}

// Stands in for a stage, which hosts the state's bakes beside the elements it draws.
@OptIn(InternalFilmstripApi::class)
@Composable
internal fun TestStage(
  state: ComposedOverlayState,
  content: @Composable () -> Unit,
) {
  Box {
    state.BakeHost()
    content()
  }
}

// Lays an element out the way the stage's own does, on the canvas for [output] and drawn through a turned layer.
@OptIn(InternalFilmstripApi::class)
@Composable
internal fun TestElement(
  state: ComposedOverlayState,
  output: Size,
  transform: OverlayTransform,
  modifier: Modifier = Modifier,
  shown: Boolean = true,
  opacity: Float = 1f,
  visibleDuring: TimeRange? = null,
  animation: OverlayAnimation? = null,
  content: @Composable () -> Unit,
) {
  val recording = state.rememberRecording(transform, opacity, visibleDuring, animation)
  val constraints = state.canvas.constraintsFor(output)
  Layout(
    content = {
      CompositionLocalProvider(LocalDensity provides OverlayLayoutDensity) {
        Box(Modifier.overlayRecord(recording, shown)) { content() }
      }
    },
    modifier = Modifier.overlayDrawOrder(recording).then(modifier),
  ) { list, _ ->
    val placeable = list.single().measure(constraints)
    layout(placeable.width, placeable.height) {
      placeable.placeWithLayer(0, 0) { rotationZ = transform.rotationDegrees }
    }
  }
}

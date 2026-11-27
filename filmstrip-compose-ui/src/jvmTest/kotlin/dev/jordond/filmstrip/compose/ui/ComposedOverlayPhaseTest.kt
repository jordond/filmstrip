package dev.jordond.filmstrip.compose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.compose.overlay.OverlayCanvas
import dev.jordond.filmstrip.compose.overlay.OverlayTransform
import dev.jordond.filmstrip.compose.ui.component.ComposedOverlay
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Size
import io.kotest.matchers.shouldBe
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ComposedOverlayPhaseTest {
  @Test
  fun theElementLaysOutOnceWhenItFirstShows() =
    runComposeUiTest {
      var measures = 0
      setContent {
        Box(Modifier.size(320.dp, 180.dp)) {
          VideoStage(player = null, outputAspect = 16f / 9f, Modifier.fillMaxSize()) {
            ComposedOverlay(
              transform = TRANSFORM,
              canvas = OverlayCanvas(),
              output = OUTPUT,
              onTransformChange = { },
              modifier = Modifier.counting { measures++ },
              selected = true,
            ) { Box(Modifier.size(80.dp, 24.dp).background(Color.White)) }
          }
        }
      }
      waitForIdle()

      measures shouldBe 1
    }

  @Test
  fun contentChangingSizeLaysTheElementOutOnce() =
    runComposeUiTest {
      var measures = 0
      var wide by mutableStateOf(false)
      setContent {
        Box(Modifier.size(320.dp, 180.dp)) {
          VideoStage(player = null, outputAspect = 16f / 9f, Modifier.fillMaxSize()) {
            ComposedOverlay(
              transform = TRANSFORM,
              canvas = OverlayCanvas(),
              output = OUTPUT,
              onTransformChange = { },
              modifier = Modifier.counting { measures++ },
              selected = true,
            ) { Box(Modifier.size(if (wide) 160.dp else 80.dp, 24.dp).background(Color.White)) }
          }
        }
      }
      waitForIdle()
      measures = 0

      wide = true
      waitForIdle()

      measures shouldBe 1
    }

  private fun Modifier.counting(onMeasure: () -> Unit): Modifier =
    layout { measurable, constraints ->
      onMeasure()
      val placeable = measurable.measure(constraints)
      layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

  private companion object {
    val TRANSFORM = OverlayTransform(Anchor(0.5f, 0.5f))
    val OUTPUT = Size(1920, 1080)
  }
}

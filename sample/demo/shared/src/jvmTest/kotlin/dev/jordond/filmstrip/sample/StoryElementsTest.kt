package dev.jordond.filmstrip.sample

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.compose.overlay.OverlayTransform
import dev.jordond.filmstrip.compose.ui.VideoStage
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.sample.ui.SampleTheme
import dev.jordond.filmstrip.sample.ui.screen.StoryElements
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class StoryElementsTest {
  @Test
  fun aDragKeepsItsElementWhenAnotherIsDeleted() =
    runComposeUiTest {
      val state = SampleAppState(Filmstrip { autoRegister(false) }, scope = CoroutineScope(Dispatchers.Unconfined))
      val stories = state.edit.stories
      stories.addCaption("Left")
      val first = stories.selected!!.id
      stories.addCaption("Right")
      val dragged = stories.selected!!.id
      stories.update(dragged) { it.copy(transform = OverlayTransform(Anchor(0.5f, 0.2f))) }
      setContent {
        SampleTheme {
          val size = with(LocalDensity.current) { Modifier.size(WIDTH.toDp(), HEIGHT.toDp()) }
          Box(size.testTag(STAGE)) {
            VideoStage(
              player = null,
              outputAspect = WIDTH / HEIGHT,
              Modifier.fillMaxSize(),
              overlays = stories.overlays,
            ) {
              StoryElements(state, interactive = true)
            }
          }
        }
      }
      waitForIdle()

      onNodeWithTag(STAGE).performTouchInput {
        down(Offset(WIDTH * 0.5f, HEIGHT * 0.2f))
        repeat(STEPS) { moveBy(Offset(0f, STEP)) }
      }
      waitForIdle()
      stories.remove(first)
      waitForIdle()
      onNodeWithTag(STAGE).performTouchInput {
        repeat(STEPS) { moveBy(Offset(0f, STEP)) }
        up()
      }
      waitForIdle()

      val moved =
        stories.elements
          .single { it.id == dragged }
          .transform.center.y
      moved shouldBe (0.2f + 2 * STEPS * STEP / HEIGHT plusOrMinus 1e-3f)
    }

  private companion object {
    const val STAGE = "stage"
    const val WIDTH = 360f
    const val HEIGHT = 640f
    const val STEPS = 5
    const val STEP = 10f
  }
}

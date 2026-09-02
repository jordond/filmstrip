package dev.jordond.filmstrip.compose.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.compose.ui.RecordingThumbnailSource
import dev.jordond.filmstrip.compose.ui.filmstripWith
import dev.jordond.filmstrip.compose.ui.testComposition
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Every image here is laid out on a density of one, so a size in dp is the size in pixels the source is asked for.
 */
@OptIn(ExperimentalTestApi::class)
class FilmstripImageTest {
  @Test
  fun `the frame is rendered at the height the image is laid out at`() =
    runComposeUiTest {
      val source = RecordingThumbnailSource()

      setContent {
        val filmstrip = remember { filmstripWith(source) }
        FilmstripImage(
          renderer = filmstrip,
          composition = testComposition(),
          at = AT,
          modifier = Modifier.size(width = 160.dp, height = 90.dp).testTag(IMAGE),
        )
      }
      waitForIdle()

      val request = source.requests.single()
      request.position shouldBe AT
      request.heightPx shouldBe 90
      request.precise shouldBe true

      onNodeWithTag(IMAGE).assertWidthIsEqualTo(160.dp).assertHeightIsEqualTo(90.dp)
    }

  @Test
  fun `the placeholder gives way to the frame`() =
    runComposeUiTest {
      val source = RecordingThumbnailSource()

      setContent {
        val filmstrip = remember { filmstripWith(source) }
        FilmstripImage(
          renderer = filmstrip,
          composition = testComposition(),
          at = AT,
          modifier = Modifier.size(90.dp),
          placeholder = { Box(Modifier.fillMaxSize().testTag(PLACEHOLDER)) },
        )
      }
      waitForIdle()

      onNodeWithTag(PLACEHOLDER).assertDoesNotExist()
    }

  @Test
  fun `an image with no height shows its placeholder and asks for nothing`() =
    runComposeUiTest {
      val source = RecordingThumbnailSource()

      setContent {
        val filmstrip = remember { filmstripWith(source) }
        FilmstripImage(
          renderer = filmstrip,
          composition = testComposition(),
          at = AT,
          placeholder = { Box(Modifier.testTag(PLACEHOLDER)) },
        )
      }
      waitForIdle()

      source.requests shouldBe emptyList()
      onNodeWithTag(PLACEHOLDER).assertExists()
    }

  @Test
  fun `a resize renders the frame again at the new height`() =
    runComposeUiTest {
      val source = RecordingThumbnailSource()
      var height by mutableStateOf(90.dp)

      setContent {
        val filmstrip = remember { filmstripWith(source) }
        FilmstripImage(
          renderer = filmstrip,
          composition = testComposition(),
          at = AT,
          modifier = Modifier.size(width = 160.dp, height = height),
        )
      }
      waitForIdle()
      source.requests.map { it.heightPx } shouldBe listOf(90)

      height = 180.dp
      waitForIdle()

      source.requests.map { it.heightPx } shouldBe listOf(90, 180)
    }

  private companion object {
    const val IMAGE = "image"
    const val PLACEHOLDER = "placeholder"
    val AT = 2.seconds
  }
}

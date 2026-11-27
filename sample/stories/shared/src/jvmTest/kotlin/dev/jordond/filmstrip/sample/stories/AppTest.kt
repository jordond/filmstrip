package dev.jordond.filmstrip.sample.stories

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class AppTest {
  @Test
  fun theAppOpensOnTheClipPicker() =
    runComposeUiTest {
      setContent { App() }
      waitForIdle()

      onNodeWithText("New story").assertExists()
      onNodeWithText("Open a clip").assertExists()
    }
}

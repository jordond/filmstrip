package dev.jordond.filmstrip.sample.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.height
import dev.jordond.filmstrip.sample.ui.screen.ImportKey
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeGreaterThanOrEqualTo
import io.kotest.matchers.shouldBe
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class PartsTest {
  @Test
  fun `the LED switch toggles and its word changes`() =
    runComposeUiTest {
      setContent {
        Themed {
          var on by remember { mutableStateOf(false) }
          LedSwitch("Loop", on, { on = it }, Modifier.testTag("switch"))
        }
      }

      onNodeWithTag("switch").assertIsOff()
      onNodeWithText("OFF").assertExists()

      onNodeWithTag("switch").performClick()

      onNodeWithTag("switch").assertIsOn()
      onNodeWithText("ON").assertExists()
      onNodeWithText("OFF").assertDoesNotExist()
    }

  @Test
  fun `a selected chip reports selected`() =
    runComposeUiTest {
      setContent {
        Themed { Chip("16:9", selected = true, onClick = {}, modifier = Modifier.testTag("chip")) }
      }

      onNodeWithTag("chip").assertIsSelected()
    }

  @Test
  fun `a chip on a phone is at least the touch size`() =
    runSkikoComposeUiTest(size = Size(400f, 800f)) {
      setContent {
        Themed { Chip("A", selected = false, onClick = {}, modifier = Modifier.testTag("chip")) }
      }

      onNodeWithTag("chip").getBoundsInRoot().height shouldBeGreaterThanOrEqualTo Tokens.sizeTouch
    }

  @Test
  fun `an LED switch on a phone is at least the touch size`() =
    runSkikoComposeUiTest(size = Size(400f, 800f)) {
      setContent {
        Themed { LedSwitch("Loop", checked = false, onCheckedChange = {}, modifier = Modifier.testTag("switch")) }
      }

      onNodeWithTag("switch").getBoundsInRoot().height shouldBeGreaterThanOrEqualTo Tokens.sizeTouch
    }

  @Test
  fun `the import key reports a button and fires onImport`() =
    runComposeUiTest {
      var imports = 0
      setContent {
        Themed { ImportKey(probing = false, onImport = { imports++ }, modifier = Modifier.testTag("import")) }
      }

      onNodeWithTag("import").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
      onNodeWithTag("import").performClick()

      imports shouldBe 1
    }

  @Test
  fun `a blink steps on and off`() =
    runComposeUiTest {
      val seen = mutableSetOf<Boolean>()
      mainClock.autoAdvance = false
      setContent {
        Themed {
          CompositionLocalProvider(LocalReducedMotion provides false) {
            seen += rememberBlink()
          }
        }
      }

      mainClock.advanceTimeBy(2_000)
      waitForIdle()

      seen shouldBe setOf(true, false)
    }

  @Test
  fun `reduced motion freezes the blink lit`() =
    runComposeUiTest {
      val seen = mutableSetOf<Boolean>()
      mainClock.autoAdvance = false
      setContent {
        Themed {
          CompositionLocalProvider(LocalReducedMotion provides true) {
            seen += rememberBlink()
          }
        }
      }

      mainClock.advanceTimeBy(2_000)
      waitForIdle()

      seen shouldContainExactly setOf(true)
    }

  @Composable
  private fun Themed(content: @Composable () -> Unit) {
    SampleTheme(content)
  }
}

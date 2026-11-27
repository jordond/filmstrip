package dev.jordond.filmstrip.compose.ui.interaction

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.compose.overlay.OverlayTransform
import dev.jordond.filmstrip.compose.ui.ComposedOverlayDefaults
import dev.jordond.filmstrip.compose.ui.component.stepped
import dev.jordond.filmstrip.geometry.Anchor
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class ComposedOverlayKeysTest {
  @Test
  fun anArrowMovesTheCentreByTheNudge() =
    runComposeUiTest {
      val keyed = keyed()

      onRoot().performKeyInput { pressKey(Key.DirectionRight) }
      waitForIdle()

      keyed.moves shouldBe listOf(START.stepped(pan = Offset(ComposedOverlayDefaults.NUDGE, 0f)))
      keyed.moves
        .last()
        .center.x shouldBe (START.center.x + ComposedOverlayDefaults.NUDGE plusOrMinus EPSILON)
      keyed.moves
        .last()
        .center.y shouldBe START.center.y
    }

  @Test
  fun aShiftedArrowMovesTenNudges() =
    runComposeUiTest {
      val keyed = keyed()

      onRoot().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionUp) } }
      waitForIdle()

      ComposedOverlayDefaults.COARSE_NUDGE shouldBe (ComposedOverlayDefaults.NUDGE * 10 plusOrMinus EPSILON)
      keyed.moves
        .last()
        .center.y shouldBe (START.center.y - ComposedOverlayDefaults.COARSE_NUDGE plusOrMinus EPSILON)
    }

  @Test
  fun anArrowAtTheEdgeHoldsTheCentreOnTheFrame() =
    runComposeUiTest {
      val keyed = keyed(OverlayTransform(Anchor(0.998f, 0.5f)))

      onRoot().performKeyInput { repeat(3) { pressKey(Key.DirectionRight) } }
      waitForIdle()

      keyed.moves
        .last()
        .center.x shouldBe 1f
    }

  @Test
  fun pressesStepOneAfterAnother() =
    runComposeUiTest {
      val keyed = keyed()

      onRoot().performKeyInput { repeat(PRESSES) { pressKey(Key.DirectionDown) } }
      waitForIdle()

      keyed.moves.size shouldBe PRESSES
      keyed.moves
        .last()
        .center.y shouldBe
        (START.center.y + PRESSES * ComposedOverlayDefaults.NUDGE plusOrMinus EPSILON)
    }

  @Test
  fun plusAndMinusStepTheScaleByTheWheelStep() =
    runComposeUiTest {
      val keyed = keyed()

      onRoot().performKeyInput { pressKey(Key.Equals) }
      waitForIdle()
      keyed.moves.last() shouldBe START.stepped(zoom = ComposedOverlayDefaults.WHEEL_STEP)
      keyed.moves.last().scale shouldBe (START.scale * ComposedOverlayDefaults.WHEEL_STEP plusOrMinus EPSILON)

      onRoot().performKeyInput { repeat(2) { pressKey(Key.Minus) } }
      waitForIdle()
      keyed.moves.last().scale shouldBe (START.scale / ComposedOverlayDefaults.WHEEL_STEP plusOrMinus EPSILON)
    }

  @Test
  fun aBracketTurnsOneDegreeAndFifteenWithShift() =
    runComposeUiTest {
      val keyed = keyed()

      onRoot().performKeyInput { pressKey(Key.RightBracket) }
      waitForIdle()
      keyed.moves.last().rotationDegrees shouldBe START.rotationDegrees + ComposedOverlayDefaults.KEY_TURN_STEP

      onRoot().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.LeftBracket) } }
      waitForIdle()
      keyed.moves.last().rotationDegrees shouldBe
        START.rotationDegrees + ComposedOverlayDefaults.KEY_TURN_STEP - ComposedOverlayDefaults.COARSE_KEY_TURN_STEP
    }

  @Test
  fun aDisabledModifierLetsTheKeyThrough() =
    runComposeUiTest {
      val keyed = keyed(enabled = false)

      onRoot().performKeyInput { pressKey(Key.DirectionRight) }
      waitForIdle()

      keyed.moves shouldBe emptyList()
      keyed.passed shouldBe listOf(Key.DirectionRight)
    }

  @Test
  fun anUnboundKeyIsNotTakenAndABoundOneIs() =
    runComposeUiTest {
      val keyed = keyed()

      onRoot().performKeyInput {
        pressKey(Key.Delete)
        pressKey(Key.DirectionLeft)
        pressKey(Key.Escape)
      }
      waitForIdle()

      keyed.passed shouldBe listOf(Key.Delete, Key.Escape)
      keyed.moves.size shouldBe 1
    }

  private fun ComposeUiTest.keyed(
    start: OverlayTransform = START,
    enabled: Boolean = true,
  ): Keyed {
    val keyed = Keyed()
    setContent {
      var transform by remember { mutableStateOf(start) }
      val focus = remember { FocusRequester() }
      Box(
        Modifier
          .size(100.dp)
          .onKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown) keyed.passed += event.key
            false
          }.focusRequester(focus)
          // The modifier brings its own focus target, so only a disabled one needs a stand-in to take the key.
          .then(if (enabled) Modifier else Modifier.focusable())
          .composedOverlayKeys(
            transform = transform,
            onTransformChange = {
              keyed.moves += it
              transform = it
            },
            enabled = enabled,
          ),
      )
      LaunchedEffect(Unit) { focus.requestFocus() }
    }
    waitForIdle()
    return keyed
  }

  private class Keyed {
    val moves = mutableListOf<OverlayTransform>()
    val passed = mutableListOf<Key>()
  }

  private companion object {
    val START = OverlayTransform(Anchor(0.5f, 0.5f), scale = 1.5f, rotationDegrees = 30f)
    const val PRESSES = 4
    const val EPSILON = 1e-4f
  }
}

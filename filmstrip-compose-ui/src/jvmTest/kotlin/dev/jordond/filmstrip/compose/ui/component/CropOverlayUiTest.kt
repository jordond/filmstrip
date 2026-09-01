package dev.jordond.filmstrip.compose.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.compose.ui.interaction.CropConstraint
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.math.absoluteValue
import kotlin.test.Test

/**
 * A 400x400 box laid out over a square output, so the content rect fills the box exactly and a pixel offset is a
 * four-hundredth of the normalized frame.
 */
@OptIn(ExperimentalTestApi::class)
class CropOverlayUiTest {
  @Test
  fun `a corner drag reports the expected rectangle`() =
    runComposeUiTest {
      var rect by mutableStateOf(NormalizedRect(0.2f, 0.2f, 0.8f, 0.8f))

      setContent {
        Box(Modifier.size(BOX_SIZE)) {
          CropOverlay(rect = rect, onRectChange = { rect = it }, output = OUTPUT)
        }
      }

      // The top-left corner sits at (80, 80). The first move only clears touch slop, landing
      // close enough to the corner to still be recognised as it, so only the delta added after
      // it is measured, the same way TrimOverlayTest measures a drag.
      onRoot().performTouchInput {
        down(Offset(80f, 80f))
        advanceEventTime(16L)
        moveTo(Offset(95f, 95f))
      }
      waitForIdle()
      val afterSlop = rect

      onRoot().performTouchInput {
        advanceEventTime(16L)
        moveBy(Offset(20f, 20f))
        up()
      }
      waitForIdle()

      (rect.left - afterSlop.left) shouldBeCloseTo 0.05f
      (rect.top - afterSlop.top) shouldBeCloseTo 0.05f
      rect.right shouldBeCloseTo 0.8f
      rect.bottom shouldBeCloseTo 0.8f
    }

  @Test
  fun `a body drag translates the rectangle without resizing it`() =
    runComposeUiTest {
      var rect by mutableStateOf(NormalizedRect(0.3f, 0.3f, 0.6f, 0.6f))

      setContent {
        Box(Modifier.size(BOX_SIZE)) {
          CropOverlay(rect = rect, onRectChange = { rect = it }, output = OUTPUT)
        }
      }

      // (180, 180) is well inside the rectangle (120..240 on both axes) and away from its edges.
      onRoot().performTouchInput {
        down(Offset(180f, 180f))
        advanceEventTime(16L)
        moveTo(Offset(200f, 200f))
      }
      waitForIdle()
      val afterSlop = rect

      onRoot().performTouchInput {
        advanceEventTime(16L)
        moveBy(Offset(30f, 10f))
        up()
      }
      waitForIdle()

      (rect.left - afterSlop.left) shouldBeCloseTo 0.075f
      (rect.top - afterSlop.top) shouldBeCloseTo 0.025f
      rect.width shouldBeCloseTo afterSlop.width
      rect.height shouldBeCloseTo afterSlop.height
    }

  @Test
  fun `a drag past the minimum stops at the minimum instead of inverting`() =
    runComposeUiTest {
      var rect by mutableStateOf(NormalizedRect(0.3f, 0.3f, 0.6f, 0.6f))

      setContent {
        Box(Modifier.size(BOX_SIZE)) {
          CropOverlay(
            rect = rect,
            onRectChange = { rect = it },
            output = OUTPUT,
            constraint = CropConstraint.Free(minWidth = 0.2f, minHeight = 0.2f),
          )
        }
      }

      // The right edge's midpoint sits at (240, 180). Dragging it far past the left edge must
      // stop the rectangle at the minimum width rather than crossing over it.
      onRoot().performTouchInput {
        down(Offset(240f, 180f))
        advanceEventTime(16L)
        moveTo(Offset(220f, 180f))
      }
      waitForIdle()

      onRoot().performTouchInput {
        advanceEventTime(16L)
        moveBy(Offset(-400f, 0f))
        up()
      }
      waitForIdle()

      rect.left shouldBeCloseTo 0.3f
      rect.right shouldBeCloseTo 0.5f
    }

  @Test
  fun `a constraint picked after the rectangle was drawn corrects it once`() =
    runComposeUiTest {
      var rect by mutableStateOf(NormalizedRect(0.1f, 0.2f, 0.9f, 0.6f))
      var constraint: CropConstraint by mutableStateOf(CropConstraint.Free(minWidth = 0.1f, minHeight = 0.1f))
      var reports = 0

      setContent {
        Box(Modifier.size(BOX_SIZE)) {
          CropOverlay(
            rect = rect,
            onRectChange = {
              reports++
              rect = it
            },
            output = OUTPUT,
            constraint = constraint,
          )
        }
      }
      waitForIdle()

      // The rectangle already satisfies the free constraint, so nothing has been asked for yet.
      reports shouldBe 0

      constraint = CropConstraint.FixedAspect(ratio = 1f, minWidth = 0.1f)
      waitForIdle()

      reports shouldBe 1
      rect.width shouldBeCloseTo 0.8f
      rect.height shouldBeCloseTo 0.8f
    }

  @Test
  fun `a host that drops the correction is not asked for it again`() =
    runComposeUiTest {
      val rect = NormalizedRect(0.1f, 0.2f, 0.9f, 0.6f)
      var constraint: CropConstraint by mutableStateOf(CropConstraint.Free(minWidth = 0.1f, minHeight = 0.1f))
      var showGrid by mutableStateOf(true)
      var reports = 0

      setContent {
        Box(Modifier.size(BOX_SIZE)) {
          CropOverlay(
            rect = rect,
            onRectChange = { reports++ },
            output = OUTPUT,
            constraint = constraint,
            showGrid = showGrid,
          )
        }
      }

      constraint = CropConstraint.FixedAspect(ratio = 1f, minWidth = 0.1f)
      waitForIdle()
      reports shouldBe 1

      showGrid = false
      waitForIdle()

      reports shouldBe 1
    }

  private companion object {
    val BOX_SIZE = 400.dp
    val OUTPUT = Size(400, 400)

    // Every expected value here is hand-computed independently of the code under test, and the
    // mapping runs through Float, so the arrival is compared within a small tolerance.
    const val EPSILON = 0.001f

    infix fun Float.shouldBeCloseTo(expected: Float) {
      (this - expected).absoluteValue shouldBeLessThan EPSILON
    }
  }
}

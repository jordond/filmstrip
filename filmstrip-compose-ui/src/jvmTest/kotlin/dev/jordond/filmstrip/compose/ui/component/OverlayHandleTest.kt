package dev.jordond.filmstrip.compose.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
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
import dev.jordond.filmstrip.compose.ui.VideoStage
import dev.jordond.filmstrip.effects.OverlayPlacement
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Size
import io.kotest.matchers.comparables.shouldBeLessThan
import kotlin.math.absoluteValue
import kotlin.test.Test

/**
 * A 400x400 stage over a square output, so the stage's letterbox fills the box and a pixel of drag is a
 * four-hundredth of the normalized frame.
 */
@OptIn(ExperimentalTestApi::class)
class OverlayHandleTest {
  @Test
  fun `a drag reports the anchor the overlay is dragged to`() =
    runComposeUiTest {
      var anchor by mutableStateOf(Anchor.Center)

      setContent { HandleStage(anchor) { anchor = it } }

      // The handle is 80x40 centred on the stage, so (200, 200) is the middle of it. The first move
      // only clears touch slop, which reports the anchor unmoved, so the delta after it is measured.
      onRoot().performTouchInput {
        down(Offset(200f, 200f))
        advanceEventTime(16L)
        moveTo(Offset(215f, 215f))
      }
      waitForIdle()
      val afterSlop = anchor

      onRoot().performTouchInput {
        advanceEventTime(16L)
        moveBy(Offset(20f, 20f))
        up()
      }
      waitForIdle()

      (anchor.x - afterSlop.x) shouldBeCloseTo 0.05f
      (anchor.y - afterSlop.y) shouldBeCloseTo 0.05f
    }

  @Test
  fun `the box moving under the finger does not cut the drag short`() =
    runComposeUiTest {
      var anchor by mutableStateOf(Anchor.Center)

      setContent { HandleStage(anchor) { anchor = it } }

      onRoot().performTouchInput {
        down(Offset(200f, 200f))
        advanceEventTime(16L)
        moveTo(Offset(215f, 215f))
      }
      waitForIdle()
      val afterSlop = anchor

      // Two moves of the same size, with the handle laid out again between them. A gesture read off
      // the box itself loses the pointer the moment the box is placed away from under it, so the
      // second move would go nowhere.
      onRoot().performTouchInput {
        advanceEventTime(16L)
        moveBy(Offset(20f, 20f))
      }
      waitForIdle()
      onRoot().performTouchInput {
        advanceEventTime(16L)
        moveBy(Offset(20f, 20f))
        up()
      }
      waitForIdle()

      (anchor.x - afterSlop.x) shouldBeCloseTo 0.1f
      (anchor.y - afterSlop.y) shouldBeCloseTo 0.1f
    }

  @Test
  fun `a host that drops the change draws a handle that does not move`() =
    runComposeUiTest {
      var reported = Anchor.Center

      setContent { HandleStage(Anchor.Center) { reported = it } }

      repeat(2) {
        onRoot().performTouchInput {
          down(Offset(200f, 200f))
          advanceEventTime(16L)
          moveTo(Offset(215f, 215f))
          advanceEventTime(16L)
          moveBy(Offset(20f, 20f))
          up()
        }
        waitForIdle()

        // The same gesture over a handle that never moved asks for the same anchor both times.
        reported.x shouldBeCloseTo 0.55f
        reported.y shouldBeCloseTo 0.55f
      }
    }

  @Test
  fun `a drag past the edge stops with the overlay's centre on the frame`() =
    runComposeUiTest {
      var anchor by mutableStateOf(Anchor.Center)

      setContent { HandleStage(anchor) { anchor = it } }

      onRoot().performTouchInput {
        down(Offset(200f, 200f))
        advanceEventTime(16L)
        moveTo(Offset(215f, 215f))
        advanceEventTime(16L)
        moveBy(Offset(400f, 400f))
        up()
      }
      waitForIdle()

      // A centre-anchored overlay puts its own centre on the anchor, so the anchor stops at the corner.
      anchor.x shouldBeCloseTo 1f
      anchor.y shouldBeCloseTo 1f
    }

  @Test
  fun `an anchor inside both limits is left alone`() {
    val held = cornerPlacement().heldOnFrame(Anchor(0.7f, 0.6f), OUTPUT)

    held.x shouldBeCloseTo 0.7f
    held.y shouldBeCloseTo 0.6f
  }

  @Test
  fun `the overlay's centre is held on the frame`() {
    // Anchored at its own bottom end, and half the frame wide and tall, so a quarter of the frame
    // is as close to the start edge as the anchor may come.
    val held = cornerPlacement().heldOnFrame(Anchor(0.05f, 0.02f), OUTPUT)

    held.x shouldBeCloseTo 0.25f
    held.y shouldBeCloseTo 0.25f
  }

  @Test
  fun `the point the overlay anchors to is held on the frame`() {
    val placement =
      OverlayPlacement(
        size = Size(40, 40),
        overlayAnchor = Anchor.TopStart,
        frameAnchor = Anchor.Center,
      )

    val past = placement.heldOnFrame(Anchor(1.4f, 1.4f), OUTPUT)
    val before = placement.heldOnFrame(Anchor(-0.3f, -0.3f), OUTPUT)

    // 40 pixels is a twentieth of the frame across and a tenth of it down, and the centre rule
    // keeps half of each back from the far edge.
    past.x shouldBeCloseTo 0.975f
    past.y shouldBeCloseTo 0.95f
    // The anchor rule is what stops the drag at the near edge, since the centre rule would let it
    // go past by the same half.
    before.x shouldBeCloseTo 0f
    before.y shouldBeCloseTo 0f
  }

  private fun cornerPlacement(): OverlayPlacement =
    OverlayPlacement(
      size = Size(400, 200),
      overlayAnchor = Anchor.BottomEnd,
      frameAnchor = Anchor.BottomEnd,
    )

  private companion object {
    val OUTPUT = Size(800, 400)

    // Every expected value here is hand-computed independently of the code under test, and the
    // mapping runs through Float, so the arrival is compared within a small tolerance.
    const val EPSILON = 0.01f

    infix fun Float.shouldBeCloseTo(expected: Float) {
      (this - expected).absoluteValue shouldBeLessThan EPSILON
    }
  }
}

/**
 * A stage filling a square box, with an 80x40 handle anchored at its own centre.
 */
@Composable
private fun HandleStage(
  anchor: Anchor,
  onAnchorChange: (Anchor) -> Unit,
) {
  Box(Modifier.size(STAGE_SIZE)) {
    VideoStage(player = null, outputAspect = 1f) {
      OverlayHandle(
        placement =
          OverlayPlacement(
            size = HANDLE_SIZE,
            overlayAnchor = Anchor.Center,
            frameAnchor = anchor,
          ),
        onFrameAnchorChange = onAnchorChange,
        output = STAGE_OUTPUT,
      )
    }
  }
}

private val STAGE_SIZE = 400.dp
private val STAGE_OUTPUT = Size(400, 400)
private val HANDLE_SIZE = Size(80, 40)

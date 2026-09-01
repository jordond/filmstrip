package dev.jordond.filmstrip.compose.ui.interaction

import dev.jordond.filmstrip.compose.ui.CropOverlayDefaults
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.NormalizedRect
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.absoluteValue
import kotlin.test.Test

class CropConstraintTest {
  @Test
  fun `Free stops a TopLeft drag at the minimum and leaves the opposite corner alone`() {
    val proposed = NormalizedRect(0.65f, 0.55f, 0.7f, 0.6f)

    MIN.constrain(proposed, CropHandle.TopLeft) shouldBeCloseTo NormalizedRect(0.6f, 0.5f, 0.7f, 0.6f)
  }

  @Test
  fun `Free stops a Top drag at the minimum and leaves the other edges alone`() {
    val proposed = NormalizedRect(0.3f, 0.55f, 0.7f, 0.6f)

    MIN.constrain(proposed, CropHandle.Top) shouldBeCloseTo NormalizedRect(0.3f, 0.5f, 0.7f, 0.6f)
  }

  @Test
  fun `Free stops a TopRight drag at the minimum and leaves the opposite corner alone`() {
    val proposed = NormalizedRect(0.3f, 0.55f, 0.35f, 0.6f)

    MIN.constrain(proposed, CropHandle.TopRight) shouldBeCloseTo NormalizedRect(0.3f, 0.5f, 0.4f, 0.6f)
  }

  @Test
  fun `Free stops a Right drag at the minimum and leaves the other edges alone`() {
    val proposed = NormalizedRect(0.3f, 0.3f, 0.35f, 0.6f)

    MIN.constrain(proposed, CropHandle.Right) shouldBeCloseTo NormalizedRect(0.3f, 0.3f, 0.4f, 0.6f)
  }

  @Test
  fun `Free stops a BottomRight drag at the minimum and leaves the opposite corner alone`() {
    val proposed = NormalizedRect(0.3f, 0.3f, 0.35f, 0.35f)

    MIN.constrain(proposed, CropHandle.BottomRight) shouldBeCloseTo NormalizedRect(0.3f, 0.3f, 0.4f, 0.4f)
  }

  @Test
  fun `Free stops a Bottom drag at the minimum and leaves the other edges alone`() {
    val proposed = NormalizedRect(0.3f, 0.3f, 0.7f, 0.35f)

    MIN.constrain(proposed, CropHandle.Bottom) shouldBeCloseTo NormalizedRect(0.3f, 0.3f, 0.7f, 0.4f)
  }

  @Test
  fun `Free stops a BottomLeft drag at the minimum and leaves the opposite corner alone`() {
    val proposed = NormalizedRect(0.65f, 0.3f, 0.7f, 0.35f)

    MIN.constrain(proposed, CropHandle.BottomLeft) shouldBeCloseTo NormalizedRect(0.6f, 0.3f, 0.7f, 0.4f)
  }

  @Test
  fun `Free stops a Left drag at the minimum and leaves the other edges alone`() {
    val proposed = NormalizedRect(0.65f, 0.3f, 0.7f, 0.6f)

    MIN.constrain(proposed, CropHandle.Left) shouldBeCloseTo NormalizedRect(0.6f, 0.3f, 0.7f, 0.6f)
  }

  @Test
  fun `Free never lets the result leave the frame`() {
    val pastTheRightEdge = NormalizedRect(0.6f, 0.2f, 1.4f, 0.8f)
    MIN.constrain(pastTheRightEdge, CropHandle.Right) shouldBeCloseTo NormalizedRect(0.6f, 0.2f, 1f, 0.8f)

    val pastTheLeftEdge = NormalizedRect(-0.5f, 0.2f, 0.4f, 0.8f)
    MIN.constrain(pastTheLeftEdge, CropHandle.Left) shouldBeCloseTo NormalizedRect(0f, 0.2f, 0.4f, 0.8f)
  }

  @Test
  fun `Free translates the Body without resizing, and stops it at the frame edge`() {
    // A rect dragged 0.5 right and 0.1 down from a mid-range position, which would run its right
    // edge past the frame.
    val proposed = NormalizedRect(0.8f, 0.4f, 1.1f, 0.6f)

    val result = MIN.constrain(proposed, CropHandle.Body)

    result shouldBeCloseTo NormalizedRect(0.7f, 0.4f, 1f, 0.6f)
    result.width shouldBeCloseTo 0.3f
    result.height shouldBeCloseTo 0.2f
  }

  @Test
  fun `FixedAspect sizes a corner drag from the width and keeps the ratio`() {
    val constraint = CropConstraint.FixedAspect(ratio = 2f, minWidth = 0.1f)

    // The drag also moved bottom to 0.9, which the ratio overrides: the corner drag is sized by
    // the horizontal component alone.
    val proposed = NormalizedRect(0.2f, 0.2f, 0.8f, 0.9f)

    constraint.constrain(proposed, CropHandle.BottomRight) shouldBeCloseTo NormalizedRect(0.2f, 0.2f, 0.8f, 0.5f)
  }

  @Test
  fun `FixedAspect holds the opposite edge fixed and grows the other axis around its centre`() {
    val constraint = CropConstraint.FixedAspect(ratio = 2f, minWidth = 0.1f)

    // Only the right edge is dragged. Left must stay exactly where it was, and the vertical axis
    // must grow symmetrically around its original centre (0.4) to keep the ratio.
    val proposed = NormalizedRect(0.2f, 0.3f, 0.9f, 0.5f)

    val result = constraint.constrain(proposed, CropHandle.Right)

    result.left shouldBeCloseTo 0.2f
    result shouldBeCloseTo NormalizedRect(0.2f, 0.225f, 0.9f, 0.575f)
    (result.width / result.height) shouldBeCloseTo 2f
  }

  @Test
  fun `FixedAspect stops a shrink at the smallest rectangle the ratio and minimum allow`() {
    val constraint = CropConstraint.FixedAspect(ratio = 2f, minWidth = 0.1f)

    // Already at the minimum: width 0.1, height 0.05, ratio 2. A further shrink must not move it.
    val atMinimum = NormalizedRect(0.4f, 0.3f, 0.5f, 0.35f)
    val proposed = NormalizedRect(0.4f, 0.3f, 0.42f, 0.35f)

    constraint.constrain(proposed, CropHandle.Right) shouldBeCloseTo atMinimum
  }

  @Test
  fun `FixedAspect never lets a corner drag leave the frame`() {
    val constraint = CropConstraint.FixedAspect(ratio = 2f, minWidth = 0.1f)

    // TopLeft dragged far past both edges of the frame, anchored at (0.5, 0.5).
    val proposed = NormalizedRect(-0.5f, -0.5f, 0.5f, 0.5f)

    constraint.constrain(proposed, CropHandle.TopLeft) shouldBeCloseTo NormalizedRect(0f, 0.25f, 0.5f, 0.5f)
  }

  @Test
  fun `FixedAspect translates the Body without disturbing the ratio`() {
    val constraint = CropConstraint.FixedAspect(ratio = 2f, minWidth = 0.1f)
    val proposed = NormalizedRect(0.8f, 0.7f, 1.2f, 0.9f)

    val result = constraint.constrain(proposed, CropHandle.Body)

    result shouldBeCloseTo NormalizedRect(0.6f, 0.7f, 1f, 0.9f)
    (result.width / result.height) shouldBeCloseTo 2f
  }

  @Test
  fun `lockedTo divides the picture ratio by the frame's own aspect`() {
    // 4:3 held on a 16:9 frame. Both readings agree at 1:1 on a square frame, so the ratio tested
    // here is neither the picture's nor the frame's.
    val constraint = CropConstraint.lockedTo(AspectRatio.Classic, frameAspect = 16f / 9f)

    constraint.shouldBeInstanceOf<CropConstraint.FixedAspect>().ratio shouldBeCloseTo 0.75f
  }

  @Test
  fun `a rectangle lockedTo a ratio has that ratio once the frame is multiplied back in`() {
    val frameAspect = 16f / 9f
    val constraint = CropConstraint.lockedTo(AspectRatio.Classic, frameAspect)
    val proposed = NormalizedRect(0.1f, 0.1f, 0.7f, 0.5f)

    val result = constraint.constrain(proposed, CropHandle.BottomRight)

    (result.width / result.height * frameAspect) shouldBeCloseTo AspectRatio.Classic.value
  }

  @Test
  fun `lockedTo carries a minimum width through to the constraint it builds`() {
    val constraint = CropConstraint.lockedTo(AspectRatio.Portrait, frameAspect = 1f, minWidth = 0.3f)

    constraint.shouldBeInstanceOf<CropConstraint.FixedAspect>().minWidth shouldBeCloseTo 0.3f
  }

  @Test
  fun `a frame with no aspect has nothing to divide by and answers the free default`() {
    CropConstraint.lockedTo(AspectRatio.Square, frameAspect = 0f) shouldBe CropOverlayDefaults.Constraint
  }

  private companion object {
    val MIN = CropConstraint.Free(minWidth = 0.1f, minHeight = 0.1f)

    // Every expected value here is hand-computed independently of the code under test, and a
    // handful of subtractions land a float a bit off a literal that names the same number.
    const val EPSILON = 0.0001f

    infix fun Float.shouldBeCloseTo(expected: Float) {
      (this - expected).absoluteValue shouldBeLessThan EPSILON
    }

    infix fun NormalizedRect.shouldBeCloseTo(expected: NormalizedRect) {
      left shouldBeCloseTo expected.left
      top shouldBeCloseTo expected.top
      right shouldBeCloseTo expected.right
      bottom shouldBeCloseTo expected.bottom
    }
  }
}

package dev.jordond.filmstrip.effects.geometry

import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.test.Test

/**
 * The retained rectangle, computed once in shared code so both backends cut the same region.
 */
class CropGeometryTest {
  @Test
  fun landscapeToPortraitCutsWidth() {
    val rect = Crop(AspectRatio.Portrait).retainedRect(Size(1920, 1080))

    // A 16:9 frame reframed to 9:16 keeps a slice whose width is 9/16 of its height.
    assertClose(0.31640625f, rect.width)
    rect.height shouldBe 1f
    rect.isValid shouldBe true
  }

  @Test
  fun anchorMovesTheRetainedRegion() {
    val centred = Crop(AspectRatio.Portrait, anchor = Anchor.Center).retainedRect(Size(1920, 1080))
    val leftmost =
      Crop(AspectRatio.Portrait, anchor = Anchor.CenterStart).retainedRect(Size(1920, 1080))
    val rightmost =
      Crop(AspectRatio.Portrait, anchor = Anchor.CenterEnd).retainedRect(Size(1920, 1080))

    leftmost.left shouldBe 0f
    assertClose(1f, rightmost.right)
    (leftmost.left < centred.left && centred.left < rightmost.left) shouldBe true
    // The anchor moves the window. It never changes its size.
    assertClose(centred.width, leftmost.width)
    assertClose(centred.width, rightmost.width)
  }

  @Test
  fun portraitToLandscapeCutsHeight() {
    val rect = Crop(AspectRatio.Landscape).retainedRect(Size(1080, 1920))

    rect.width shouldBe 1f
    assertClose(0.31640625f, rect.height)
  }

  @Test
  fun containAndStretchRemoveNothing() {
    val contained = Crop(AspectRatio.Portrait, fit = Fit.Contain).retainedRect(Size(1920, 1080))
    val stretched = Crop(AspectRatio.Portrait, fit = Fit.Stretch).retainedRect(Size(1920, 1080))

    // Neither removes pixels: one letterboxes into its canvas and the other stretches.
    contained.width shouldBe 1f
    contained.height shouldBe 1f
    stretched.width shouldBe 1f
    stretched.height shouldBe 1f
  }

  @Test
  fun aMatchingAspectKeepsEverything() {
    val rect = Crop(AspectRatio.Landscape).retainedRect(Size(1920, 1080))

    assertClose(1f, rect.width)
    assertClose(1f, rect.height)
  }

  @Test
  fun aDegenerateFrameIsRefusedRatherThanDividedBy() {
    val rect = Crop(AspectRatio.Portrait).retainedRect(Size(0, 0))

    rect.width shouldBe 1f
    rect.height shouldBe 1f
  }

  private fun assertClose(
    expected: Float,
    actual: Float,
  ) {
    withClue("expected $expected but was $actual") { (abs(expected - actual) < TOLERANCE) shouldBe true }
  }

  private companion object {
    const val TOLERANCE = 1e-5f
  }
}

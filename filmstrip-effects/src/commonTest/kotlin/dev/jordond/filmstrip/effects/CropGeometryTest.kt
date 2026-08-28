package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The retained rectangle, computed once in shared code so both backends cut the same region.
 */
class CropGeometryTest {
  @Test
  fun landscapeToPortraitCutsWidth() {
    val rect = Crop(AspectRatio.Portrait).retainedRect(Size(1920, 1080))

    // A 16:9 frame reframed to 9:16 keeps a slice whose width is 9/16 of its height.
    assertClose(0.31640625f, rect.width)
    assertEquals(1f, rect.height)
    assertTrue(rect.isValid)
  }

  @Test
  fun anchorMovesTheRetainedRegion() {
    val centred = Crop(AspectRatio.Portrait, anchor = Anchor.Center).retainedRect(Size(1920, 1080))
    val leftmost =
      Crop(AspectRatio.Portrait, anchor = Anchor.CenterStart).retainedRect(Size(1920, 1080))
    val rightmost =
      Crop(AspectRatio.Portrait, anchor = Anchor.CenterEnd).retainedRect(Size(1920, 1080))

    assertEquals(0f, leftmost.left)
    assertClose(1f, rightmost.right)
    assertTrue(leftmost.left < centred.left && centred.left < rightmost.left)
    // The anchor moves the window. It never changes its size.
    assertClose(centred.width, leftmost.width)
    assertClose(centred.width, rightmost.width)
  }

  @Test
  fun portraitToLandscapeCutsHeight() {
    val rect = Crop(AspectRatio.Landscape).retainedRect(Size(1080, 1920))

    assertEquals(1f, rect.width)
    assertClose(0.31640625f, rect.height)
  }

  @Test
  fun containAndStretchRemoveNothing() {
    val contained = Crop(AspectRatio.Portrait, fit = Fit.Contain).retainedRect(Size(1920, 1080))
    val stretched = Crop(AspectRatio.Portrait, fit = Fit.Stretch).retainedRect(Size(1920, 1080))

    // Neither removes pixels: one writes bars at the size stage and the other distorts.
    assertEquals(1f, contained.width)
    assertEquals(1f, contained.height)
    assertEquals(1f, stretched.width)
    assertEquals(1f, stretched.height)
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

    assertEquals(1f, rect.width)
    assertEquals(1f, rect.height)
  }

  private fun assertClose(
    expected: Float,
    actual: Float,
  ) {
    assertTrue(abs(expected - actual) < TOLERANCE, "expected $expected but was $actual")
  }

  private companion object {
    const val TOLERANCE = 1e-5f
  }
}

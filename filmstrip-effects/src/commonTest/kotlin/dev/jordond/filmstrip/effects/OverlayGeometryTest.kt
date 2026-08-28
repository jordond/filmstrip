package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Overlay placement, computed once in shared code so both backends land a watermark in the same
 * relative spot.
 */
class OverlayGeometryTest {
  @Test
  fun scaleIsAFractionOfFrameWidthAndHeightFollowsTheImage() {
    val placement = watermark(scale = 0.2f).placedOn(LANDSCAPE, Size(100, 50))

    assertEquals(Size(384, 192), placement.size)
  }

  @Test
  fun aTallImageStaysTall() {
    val placement = watermark(scale = 0.1f).placedOn(LANDSCAPE, Size(50, 200))

    // A quarter as wide as it is tall, before and after.
    assertEquals(Size(192, 768), placement.size)
  }

  @Test
  fun marginIsMeasuredOffTheShorterSide() {
    val landscape = watermark(corner = Corner.TopStart).placedOn(LANDSCAPE, SQUARE_IMAGE)
    val portrait = watermark(corner = Corner.TopStart).placedOn(PORTRAIT, SQUARE_IMAGE)

    // Both frames are 1080 on their short side, so the inset is the same number of pixels in each
    // even though it is a different fraction of each axis.
    assertClose(43.2f, landscape.frameAnchor.x * LANDSCAPE.width)
    assertClose(43.2f, landscape.frameAnchor.y * LANDSCAPE.height)
    assertClose(43.2f, portrait.frameAnchor.x * PORTRAIT.width)
    assertClose(43.2f, portrait.frameAnchor.y * PORTRAIT.height)
  }

  @Test
  fun eachCornerAnchorsToItselfAndInsetsInward() {
    val corners =
      mapOf(
        Corner.TopStart to Anchor.TopStart,
        Corner.TopEnd to Anchor.TopEnd,
        Corner.BottomStart to Anchor.BottomStart,
        Corner.BottomEnd to Anchor.BottomEnd,
      )

    corners.forEach { (corner, expected) ->
      val placement = watermark(corner = corner).placedOn(LANDSCAPE, SQUARE_IMAGE)

      // The overlay meets the frame at the matching point, which is what stops a corner watermark
      // hanging half outside the frame.
      assertEquals(expected, placement.overlayAnchor)
      // The margin always moves the meeting point towards the middle, never past an edge.
      assertTrue(placement.frameAnchor.x > 0f && placement.frameAnchor.x < 1f)
      assertTrue(placement.frameAnchor.y > 0f && placement.frameAnchor.y < 1f)
      assertTrue(abs(placement.frameAnchor.x - expected.x) > 0f)
    }
  }

  @Test
  fun aMarginWiderThanTheFrameStopsAtTheMiddle() {
    val placement = watermark(corner = Corner.TopStart, margin = 5f).placedOn(LANDSCAPE, SQUARE_IMAGE)

    assertEquals(Anchor(0.5f, 0.5f), placement.frameAnchor)
  }

  @Test
  fun noMarginPutsTheOverlayFlushWithTheCorner() {
    val placement = watermark(corner = Corner.BottomEnd, margin = 0f).placedOn(LANDSCAPE, SQUARE_IMAGE)

    assertEquals(Anchor.BottomEnd, placement.overlayAnchor)
    assertEquals(Anchor.BottomEnd, placement.frameAnchor)
  }

  @Test
  fun textMeetsTheFrameAtTheSamePointInBoth() {
    val placement = Text("caption", anchor = Anchor.BottomCenter).placedOn(Size(300, 80))

    // Text carries no margin, so the block's own bottom-centre sits on the frame's.
    assertEquals(Anchor.BottomCenter, placement.overlayAnchor)
    assertEquals(Anchor.BottomCenter, placement.frameAnchor)
    // Rasterised at the size it will occupy, so nothing rescales it.
    assertEquals(Size(300, 80), placement.size)
  }

  @Test
  fun aDegenerateImageStillPlaces() {
    val placement = watermark(scale = 0.2f).placedOn(LANDSCAPE, Size(0, 0))

    assertEquals(384, placement.size.width)
    assertTrue(placement.size.height >= 1)
  }

  private fun watermark(
    corner: Corner = Corner.BottomEnd,
    margin: Float = Watermark.DEFAULT_MARGIN,
    scale: Float = Watermark.DEFAULT_SCALE,
  ): Watermark = Watermark(ImageSource.of("/logo.png"), corner, margin, scale)

  private fun assertClose(
    expected: Float,
    actual: Float,
  ) {
    assertTrue(abs(expected - actual) < TOLERANCE, "expected $expected but was $actual")
  }

  private companion object {
    val LANDSCAPE = Size(1920, 1080)
    val PORTRAIT = Size(1080, 1920)
    val SQUARE_IMAGE = Size(100, 100)
    const val TOLERANCE = 0.001f
  }
}

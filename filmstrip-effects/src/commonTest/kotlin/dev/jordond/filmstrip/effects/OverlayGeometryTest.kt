package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import kotlin.math.abs
import kotlin.math.min
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

  @Test
  fun `a rectangle is the overlay anchor brought to the frame anchor`() {
    // Off-centre on both axes, a non-square overlay and a non-square frame, so an additive reading
    // of the anchors and a multiplicative one give different answers.
    val placement =
      OverlayPlacement(
        size = Size(300, 100),
        overlayAnchor = Anchor(0.25f, 0.75f),
        frameAnchor = Anchor(0.4f, 0.3f),
      )

    val rect = placement.rectOn(LANDSCAPE)

    // 0.4 * 1920 - 0.25 * 300 = 693, and 0.3 * 1080 - 0.75 * 100 = 249.
    assertClose(693f, rect.left * LANDSCAPE.width)
    assertClose(249f, rect.top * LANDSCAPE.height)
    assertClose(993f, rect.right * LANDSCAPE.width)
    assertClose(349f, rect.bottom * LANDSCAPE.height)
  }

  @Test
  fun `a rectangle reaches outside the frame when the overlay hangs off it`() {
    val placement =
      OverlayPlacement(
        size = Size(400, 200),
        overlayAnchor = Anchor.Center,
        frameAnchor = Anchor(0.02f, 0.5f),
      )

    val rect = placement.rectOn(LANDSCAPE)

    assertTrue(rect.left < 0f, "expected the overlay to hang off the start edge, was ${rect.left}")
    assertTrue(!rect.isValid)
  }

  @Test
  fun `a watermark's rectangle sits the authored margin off both edges`() {
    val watermark = watermark(corner = Corner.BottomEnd)

    val rect = watermark.placedOn(LANDSCAPE, SQUARE_IMAGE).rectOn(LANDSCAPE)

    val inset = Watermark.DEFAULT_MARGIN * min(LANDSCAPE.width, LANDSCAPE.height)
    assertClose(inset, (1f - rect.right) * LANDSCAPE.width)
    assertClose(inset, (1f - rect.bottom) * LANDSCAPE.height)
  }

  @Test
  fun `text sits its own block on the point it names`() {
    val rect = Text("caption").placedOn(Size(400, 80)).rectOn(Size(1280, 720))

    // Bottom centre in both, so the block's bottom edge is the frame's and it is centred on width.
    assertClose(1f, rect.bottom)
    assertClose(440f, rect.left * 1280)
    assertClose(840f, rect.right * 1280)
  }

  @Test
  fun `a corner and margin survive a round trip through the frame anchor`() {
    // Mid-range on both, on a frame whose axes are far enough apart that reading the margin off
    // the wrong side would show.
    val margin = 0.037f

    Corner.entries.forEach { corner ->
      val anchor = watermark(corner = corner, margin = margin).placedOn(PORTRAIT, SQUARE_IMAGE).frameAnchor
      val inset = anchor.nearestCornerInset(PORTRAIT)

      assertEquals(corner, inset.corner)
      assertClose(margin, inset.margin)
    }
  }

  @Test
  fun `a point takes the corner of the quadrant it falls in`() {
    assertEquals(Corner.TopStart, Anchor(0.3f, 0.2f).nearestCornerInset(LANDSCAPE).corner)
    assertEquals(Corner.TopEnd, Anchor(0.8f, 0.2f).nearestCornerInset(LANDSCAPE).corner)
    assertEquals(Corner.BottomStart, Anchor(0.3f, 0.8f).nearestCornerInset(LANDSCAPE).corner)
    assertEquals(Corner.BottomEnd, Anchor(0.8f, 0.8f).nearestCornerInset(LANDSCAPE).corner)
  }

  @Test
  fun `a point off the diagonal takes the margin midway between the two insets`() {
    // 0.1 of 1920 is 192 across and 0.2 of 1080 is 216 down, so the pair averages 204 pixels,
    // which is 204 / 1080 of the shorter side.
    val inset = Anchor(0.1f, 0.2f).nearestCornerInset(LANDSCAPE)

    assertEquals(Corner.TopStart, inset.corner)
    assertClose(204f / 1080f, inset.margin)
  }

  @Test
  fun `the margin read back depends on the frame's shape and not its size`() {
    val full = Anchor(0.12f, 0.28f).nearestCornerInset(LANDSCAPE)
    val quarter = Anchor(0.12f, 0.28f).nearestCornerInset(Size(480, 270))

    assertEquals(full.corner, quarter.corner)
    assertClose(full.margin, quarter.margin)
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

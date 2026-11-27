package dev.jordond.filmstrip.compose.overlay

import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayPosition
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ElementBoundsTest {
  @Test
  fun anElementWithNoTurnCoversItsShareOfTheCanvas() {
    val bounds = OverlayTransform(Anchor.Center).boundsOn(CONTENT, CANVAS, LANDSCAPE).shouldNotBeNull()

    assertClose(CONTENT.width / CANVAS_PX, bounds.box.width)
    assertClose(CONTENT.height / canvasHeightPx(LANDSCAPE), bounds.box.height)
  }

  @Test
  fun aQuarterTurnSwapsTheBoxSides() {
    val flat = OverlayTransform(Anchor.Center).boundsOn(CONTENT, CANVAS, LANDSCAPE).shouldNotBeNull()
    val turned = OverlayTransform(Anchor.Center, rotationDegrees = 90f).boundsOn(CONTENT, CANVAS, LANDSCAPE)

    turned.shouldNotBeNull()
    assertClose(flat.box.width * LANDSCAPE.width, turned.box.height * LANDSCAPE.height)
    assertClose(flat.box.height * LANDSCAPE.height, turned.box.width * LANDSCAPE.width)
  }

  @Test
  fun aThirtyDegreeTurnAndAScaleOfOneAndAHalfGiveTheRotatedExtent() {
    val transform = OverlayTransform(Anchor.Center, scale = 1.5f, rotationDegrees = 30f)

    val bounds = transform.boundsOn(CONTENT, CANVAS, LANDSCAPE).shouldNotBeNull()

    val perPx = 1.5f * LANDSCAPE.width / CANVAS_PX
    val w = CONTENT.width * perPx
    val h = CONTENT.height * perPx
    val radians = 30.0 * PI / 180.0
    val expectedWidth = (abs(w * cos(radians)) + abs(h * sin(radians))).toFloat()
    val expectedHeight = (abs(w * sin(radians)) + abs(h * cos(radians))).toFloat()
    assertClose(expectedWidth, bounds.box.width * LANDSCAPE.width, PIXEL)
    assertClose(expectedHeight, bounds.box.height * LANDSCAPE.height, PIXEL)
  }

  @Test
  fun aPortraitOutputReadsTheSameCanvasShareAsALandscapeOne() {
    val transform = OverlayTransform(Anchor(0.4f, 0.4f), scale = 1.5f)

    val landscape = transform.boundsOn(CONTENT, CANVAS, LANDSCAPE).shouldNotBeNull()
    val portrait = transform.boundsOn(CONTENT, CANVAS, PORTRAIT).shouldNotBeNull()

    assertClose(landscape.box.width, portrait.box.width)
    assertClose(1.5f * CONTENT.height / canvasHeightPx(PORTRAIT), portrait.box.height)
    assertClose(1.5f * CONTENT.height / canvasHeightPx(LANDSCAPE), landscape.box.height)
  }

  @Test
  fun theRasterRoundsTheBoxOutWithAPixelOfPaddingASide() {
    val transform = OverlayTransform(Anchor(0.3f, 0.6f), scale = 1.5f, rotationDegrees = 30f)

    val bounds = transform.boundsOn(CONTENT, CANVAS, LANDSCAPE).shouldNotBeNull()

    val left = bounds.box.left * LANDSCAPE.width
    val top = bounds.box.top * LANDSCAPE.height
    val right = bounds.box.right * LANDSCAPE.width
    val bottom = bounds.box.bottom * LANDSCAPE.height
    bounds.raster.left shouldBe kotlin.math.floor(left).toInt() - 1
    bounds.raster.top shouldBe kotlin.math.floor(top).toInt() - 1
    bounds.raster.right shouldBe kotlin.math.ceil(right).toInt() + 1
    bounds.raster.bottom shouldBe kotlin.math.ceil(bottom).toInt() + 1
  }

  @Test
  fun anElementHalfOffTheEndEdgeIsClippedThereAndItsCentreMovesIn() {
    val transform = OverlayTransform(Anchor(1f, 0.5f), rotationDegrees = 30f)

    val bounds = transform.boundsOn(CONTENT, CANVAS, LANDSCAPE).shouldNotBeNull()

    withClue("the box reaches past the edge") { (bounds.box.right > 1f) shouldBe true }
    bounds.visible.right shouldBe 1f
    assertClose(bounds.box.left, bounds.visible.left)
    bounds.raster.right shouldBe LANDSCAPE.width
    withClue("the centre ${bounds.center.x} sits left of the transform's") { (bounds.center.x < 1f) shouldBe true }
    assertClose(0.5f, bounds.center.y, 1f / LANDSCAPE.height)
  }

  @Test
  fun anElementWhollyOffTheFrameHasNoBounds() {
    OverlayTransform(Anchor(2f, 0.5f)).boundsOn(CONTENT, CANVAS, LANDSCAPE).shouldBeNull()
    OverlayTransform(Anchor(0.5f, -1f), rotationDegrees = 30f).boundsOn(CONTENT, CANVAS, PORTRAIT).shouldBeNull()
  }

  @Test
  fun anOverlayAtTheRasterCentreAndWidthPlacesBackOnTheRastersPixels() {
    listOf(LANDSCAPE, PORTRAIT).forEach { output ->
      val transform = OverlayTransform(Anchor(0.3f, 0.6f), scale = 1.5f, rotationDegrees = 30f)
      val bounds = transform.boundsOn(CONTENT, CANVAS, output).shouldNotBeNull()
      val raster = Size(bounds.raster.width, bounds.raster.height)

      val overlay =
        ImageOverlay(
          ImageSource.of("/element.png"),
          OverlayPosition.At(bounds.center),
          scale = bounds.raster.width.toFloat() / output.width,
        )
      val placement = overlay.placedOn(output, raster)
      val rect = placement.rectOn(output)

      withClue("$output") {
        placement.size shouldBe raster
        assertClose(bounds.raster.left.toFloat(), rect.left * output.width)
        assertClose(bounds.raster.top.toFloat(), rect.top * output.height)
      }
    }
  }

  @Test
  fun aScaleThatIsNotPositiveAndFiniteIsRefused() {
    listOf(0f, -1f, Float.POSITIVE_INFINITY, Float.NaN).forEach { scale ->
      assertFailsWith<IllegalArgumentException> { OverlayTransform(Anchor.Center, scale = scale) }
    }
    assertFailsWith<IllegalArgumentException> { OverlayTransform(Anchor.Center, rotationDegrees = Float.NaN) }
  }

  private fun canvasHeightPx(output: Size): Float = CANVAS_PX * output.height / output.width

  private fun assertClose(
    expected: Float,
    actual: Float,
    tolerance: Float = TOLERANCE,
  ) {
    withClue("expected $expected but was $actual") { (abs(expected - actual) <= tolerance) shouldBe true }
  }

  private companion object {
    val CONTENT = IntSize(400, 120)
    val CANVAS = OverlayCanvas(360.dp)
    val CANVAS_PX = 360f * OVERLAY_LAYOUT_DENSITY
    val LANDSCAPE = Size(1920, 1080)
    val PORTRAIT = Size(1080, 1920)
    const val TOLERANCE = 0.001f
    const val PIXEL = 0.01f
  }
}

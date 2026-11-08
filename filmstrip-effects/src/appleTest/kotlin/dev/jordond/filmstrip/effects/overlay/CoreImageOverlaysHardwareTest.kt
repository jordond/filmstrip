package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.effects.renderingContext
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIColor
import platform.CoreImage.CIImage
import kotlin.test.Test

/**
 * The overlay placement arithmetic, on the cases that tell a Y flip from its absence.
 *
 * A symmetric placement lands in the same spot under either Y direction and proves nothing, so
 * every case here is asymmetric. Two opposite corners, unequal margins on the two axes, and an
 * overlay whose own aspect differs from the frame's.
 */
@OptIn(ExperimentalForeignApi::class)
class CoreImageOverlaysHardwareTest {
  @Test
  fun `puts a bottom-end watermark the authored margin from both edges`() {
    val frame = Size(1920, 1080)
    val raster = Size(100, 50)
    val placement = ImageOverlay(image = NO_IMAGE, corner = Corner.BottomEnd).placedOn(frame, raster)

    placement.transformOnto(frame, raster).useContents {
      // Scaled from the raster's own pixels to the drawn size.
      a shouldBe (placement.size.width.toDouble() / raster.width plusOrMinus TOLERANCE)
      d shouldBe (placement.size.height.toDouble() / raster.height plusOrMinus TOLERANCE)
      tx shouldBe (leftOf(placement, frame) plusOrMinus TOLERANCE)
      ty shouldBe (flippedBottomOf(placement, frame) plusOrMinus TOLERANCE)
      // The margin is a fraction of the shorter side, so the right and bottom insets match on a wide frame.
      ty shouldBe (frame.width - tx - placement.size.width plusOrMinus TOLERANCE)
    }
  }

  // The overlay's own anchor flips against the overlay's height, not the frame's. Getting that
  // wrong moves a top-start badge by its own height, which a bottom-end one does not show.
  @Test
  fun `puts a top-start watermark the same distance from the top`() {
    val frame = Size(1920, 1080)
    val raster = Size(100, 50)
    val placement = ImageOverlay(image = NO_IMAGE, corner = Corner.TopStart).placedOn(frame, raster)

    placement.transformOnto(frame, raster).useContents {
      tx shouldBe (leftOf(placement, frame) plusOrMinus TOLERANCE)
      ty shouldBe (flippedBottomOf(placement, frame) plusOrMinus TOLERANCE)
      // The top edge sits as far down from the frame's top as the left edge sits in from its side.
      frame.height - (ty + placement.size.height) shouldBe (tx plusOrMinus TOLERANCE)
    }
  }

  @Test
  fun `keeps the two corners a mirror of each other on a portrait frame`() {
    val frame = Size(1080, 1920)
    val raster = Size(200, 200)
    val start = ImageOverlay(image = NO_IMAGE, corner = Corner.TopStart).placedOn(frame, raster)
    val end = ImageOverlay(image = NO_IMAGE, corner = Corner.BottomEnd).placedOn(frame, raster)

    val startTransform = start.transformOnto(frame, raster).useContents { tx to ty }
    val endTransform = end.transformOnto(frame, raster).useContents { tx to ty }

    startTransform.first shouldBe (frame.width - endTransform.first - end.size.width plusOrMinus TOLERANCE)
    startTransform.second shouldBe (frame.height - endTransform.second - end.size.height plusOrMinus TOLERANCE)
  }

  // TextOverlay carries no margin. The same point is taken in the block and in the frame, so a bottom
  // centre anchor puts the block's bottom edge on the frame's.
  @Test
  fun `sits a bottom-centre text block on the frame's bottom edge`() {
    val frame = Size(1280, 720)
    val raster = Size(400, 80)
    val placement = TextOverlay("caption").placedOn(raster)

    placement.transformOnto(frame, raster).useContents {
      a shouldBe (1.0 plusOrMinus TOLERANCE)
      tx shouldBe (440.0 plusOrMinus TOLERANCE)
      ty shouldBe (0.0 plusOrMinus TOLERANCE)
    }
  }

  @Test
  fun `leaves a fully opaque overlay untouched`() {
    val image = solid(WHITE)

    image.withAlpha(1f) shouldBeSameInstanceAs image
    image.withAlpha(1.5f) shouldBeSameInstanceAs image
  }

  // The color matrix unpremultiplies and premultiplies around the alpha row, so a half alpha overlay
  // reads half its alpha in the premultiplied buffer and keeps its own color once divided back out.
  // Scaling the color rows as well would read a quarter of the color here.
  @Test
  fun `scales only the alpha and keeps the color`() {
    val pixel = centreOf(solid(RED).withAlpha(HALF).imageByCompositingOverImage(clear()))

    pixel[ALPHA].toDouble() shouldBe (HALF.toDouble() plusOrMinus CODE_DRIFT)
    (pixel[RED_CHANNEL] / pixel[ALPHA]).toDouble() shouldBe (redCode().toDouble() plusOrMinus CODE_DRIFT)
  }

  @Test
  fun `holds an alpha below zero at transparent`() {
    centreOf(solid(RED).withAlpha(-0.5f).imageByCompositingOverImage(clear()))[ALPHA] shouldBe 0f
  }

  // White at half opacity over black lands halfway between the two codes on SDR, and halfway between
  // the two lights on HDR, which reads well above halfway once carried back into codes.
  @Test
  fun `blends an sdr frame on its codes and an hdr frame in light`() {
    val sdr = overlayCentreOf(composite(transfer = null))[0]
    val hdr = overlayCentreOf(composite(transfer = HdrTransfer.Pq))[0]

    sdr.toDouble() shouldBe (HALF.toDouble() plusOrMinus CODE_DRIFT)
    withClue("the hdr blend read $hdr against the sdr blend's $sdr") { (hdr > sdr + LIGHT_GAP) shouldBe true }
  }

  // The far corner from the badge, well clear of the soft edge an upscaled raster carries past its own.
  @Test
  fun `leaves the frame outside the placed overlay as it was`() {
    val corner = pixelAt(composite(transfer = null), FRAME.width - 1f, FRAME.height - 1f)

    corner[0] shouldBe 0f
    corner[ALPHA] shouldBe 1f
  }

  // A top-start badge, a fifth of the frame wide.
  private fun composite(transfer: HdrTransfer?): CIImage =
    solid(WHITE, RASTER).compositedOnto(solid(BLACK), badge(), FRAME, HALF, transfer, ColorSpace.Bt709)

  private fun badge(): OverlayPlacement =
    ImageOverlay(image = NO_IMAGE, corner = Corner.TopStart, opacity = HALF).placedOn(FRAME, RASTER)

  private fun overlayCentreOf(image: CIImage): FloatArray {
    val rect = badge().rectOn(FRAME)
    return pixelAt(image, (rect.left + rect.width / 2) * FRAME.width, (rect.top + rect.height / 2) * FRAME.height)
  }

  // The overlay's left edge in pixels, as the shared placement puts it from the frame's top-left.
  private fun leftOf(
    placement: OverlayPlacement,
    frame: Size,
  ): Double = placement.rectOn(frame).left.toDouble() * frame.width

  // The overlay's bottom edge in Core Image's bottom-up pixels, flipped from the shared placement's top edge.
  private fun flippedBottomOf(
    placement: OverlayPlacement,
    frame: Size,
  ): Double = frame.height - placement.rectOn(frame).top.toDouble() * frame.height - placement.size.height

  private fun centreOf(image: CIImage): FloatArray = pixelAt(image, FRAME.width / 2f, FRAME.height / 2f)

  // The pixel at x and y from the frame's top-left, four floats in the frame's own codes.
  private fun pixelAt(
    image: CIImage,
    x: Float,
    y: Float,
  ): FloatArray {
    val pixels = OverlayFixtures.pixels(image, renderingContext(floatFormat = true))
    val offset = (y.toInt() * FRAME.width + x.toInt()) * OverlayFixtures.CHANNELS
    return pixels.copyOfRange(offset, offset + OverlayFixtures.CHANNELS)
  }

  // Pure red as the frame's own codes read it, which is what an unpremultiplied red channel comes back as.
  private fun redCode(): Float = centreOf(solid(RED))[RED_CHANNEL]

  private fun solid(
    color: CIColor,
    size: Size = FRAME,
  ): CIImage =
    CIImage(color = color).imageByCroppingToRect(CGRectMake(0.0, 0.0, size.width.toDouble(), size.height.toDouble()))

  private fun clear(): CIImage = solid(CIColor.colorWithRed(0.0, green = 0.0, blue = 0.0, alpha = 0.0))

  private companion object {
    val NO_IMAGE = ImageSource.ofBytes(ByteArray(0))
    const val TOLERANCE = 0.001
    val FRAME = Size(640, 360)
    val RASTER = Size(4, 2)
    const val HALF = 0.5f
    const val RED_CHANNEL = 0
    const val ALPHA = 3

    // A code value or two of a half float working format.
    const val CODE_DRIFT = 2.0 / 255.0

    // How far above the code blend a blend in linear light lands for white over black at half.
    const val LIGHT_GAP = 0.1f
    val WHITE: CIColor = CIColor.colorWithRed(1.0, green = 1.0, blue = 1.0)
    val BLACK: CIColor = CIColor.colorWithRed(0.0, green = 0.0, blue = 0.0)
    val RED: CIColor = CIColor.colorWithRed(0.8, green = 0.0, blue = 0.0)
  }
}

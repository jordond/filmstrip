package dev.jordond.filmstrip.effects.overlay

import androidx.core.graphics.createBitmap
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.RenderFeature
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.PictureCrop
import dev.jordond.filmstrip.media.PictureOrientation
import dev.jordond.filmstrip.style.TextStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertIsNot
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How long a side an overlay raster may have on Android before it is refused by name.
 *
 * Text and pictures are both uploaded to an OpenGL ES texture, so each is drawn at the device's texture side and
 * refused one pixel past it, with the reason worked from its own size. Text laid out far past any raster is refused
 * rather than drawn or thrown.
 */
class OverlaySideTest {
  @Test
  fun textAsLongAsTheTextureSideDrawsAndOnePastIsRefused() {
    val size = drawnSize()
    val side = maxOf(size.width, size.height)

    assertIs<TextRaster.Drawn<TextSamples>>(rasterizeText(TEXT, STYLE, FRAME, side))
    val refused = assertIs<TextRaster.Refused>(rasterizeText(TEXT, STYLE, FRAME, side - 1))

    assertEquals(textPastTextureSide(size.width, size.height, side - 1, OPEN_GL_ES), refused.reason)
  }

  // The resolver hands the raster the side its capabilities give, so text past it never reaches media3.
  @Test
  fun theResolverRefusesTextPastTheTextureSideItIsGiven() {
    val size = drawnSize()
    val side = maxOf(size.width, size.height) - 1

    val resolved = BuiltInEffectResolver().resolve(TextOverlay(TEXT, STYLE), capabilities(side), PREVIEW)

    val refused = assertIs<EffectResolution.Unsupported>(resolved)
    assertEquals(textPastTextureSide(size.width, size.height, side, OPEN_GL_ES), refused.message)
  }

  @Test
  fun aPictureAsLongAsTheTextureSideResolvesAndOnePastIsRefused() {
    val overlay = ImageOverlay(ImageSource.Bytes(ByteArray(0)), Corner.TopStart, 0f, OVERLAY_SCALE)
    val picture = DecodedPicture(createBitmap(PICTURE.width, PICTURE.height), colour = null)

    val drawn = with(BuiltInEffectResolver()) { overlay.toOverlay(picture, PREVIEW, PICTURE.width) }
    val past = with(BuiltInEffectResolver()) { overlay.toOverlay(picture, PREVIEW, PICTURE.width - 1) }

    assertIsNot<EffectResolution.Unsupported>(drawn)
    val refused = assertIs<EffectResolution.Unsupported>(past)
    assertEquals(picturePastTextureSide(PICTURE.width, PICTURE.height, PICTURE.width - 1, OPEN_GL_ES), refused.message)
  }

  // The raster media3 uploads is the picture cut and turned, so the side is read off the shown size. The stored width
  // is past the side that resolves, which a check on the stored bitmap would refuse.
  @Test
  fun aCutAndTurnedPictureIsMeasuredAtTheSizeItIsShown() {
    val overlay = ImageOverlay(ImageSource.Bytes(ByteArray(0)), Corner.TopStart, 0f, OVERLAY_SCALE)
    val bitmap = createBitmap(PICTURE.width, PICTURE.height)
    val picture = DecodedPicture(bitmap, colour = null, orientation = PictureOrientation.Rotate90, crop = CUT)
    val shown = PictureOrientation.Rotate90.orientedSize(CUT.size)
    val side = maxOf(shown.width, shown.height)

    val drawn = with(BuiltInEffectResolver()) { overlay.toOverlay(picture, PREVIEW, side) }
    val past = with(BuiltInEffectResolver()) { overlay.toOverlay(picture, PREVIEW, side - 1) }

    assertIsNot<EffectResolution.Unsupported>(drawn)
    val refused = assertIs<EffectResolution.Unsupported>(past)
    assertEquals(picturePastTextureSide(shown.width, shown.height, side - 1, OPEN_GL_ES), refused.message)
  }

  // A font size far past the documented range lays out past any side an Int holds, which is refused by its size.
  @Test
  fun hugeTextWithAPlateIsRefusedAndNeverThrows() {
    val style = TextStyle(fontSize = HUGE, backgroundColor = PLATE)
    val (width, height) = assertNotNull(textSidesPastLayout(TEXT, style, FRAME), "the huge text laid out in an Int")
    val padding = 2L * style.platePaddingOn(FRAME)

    val refused = assertIs<TextRaster.Refused>(rasterizeText(TEXT, style, FRAME, Int.MAX_VALUE))

    val reason = textTooLargeToDraw(width + padding, height + padding, TEXT_TEXEL_BYTES, textCeiling())
    assertEquals(reason, refused.reason)
  }

  // StaticLayout places its lines in Int pixels, so the block lays out at every size whose sides an Int holds and at
  // none past that, where the sides are measured instead.
  @Test
  fun theBlockLaysOutExactlyWhereTheSidesFitAnInt() {
    var sizes = 0
    var past = 0
    var fontSize = SWEEP_FROM
    while (fontSize <= SWEEP_TO) {
      val style = TextStyle(fontSize = fontSize)
      val block = textBlock(TEXT, style, FRAME)
      val sides = textSidesPastLayout(TEXT, style, FRAME)
      val laidOut = block?.let { "${it.width} by ${it.height}" }
      assertEquals(sides != null, block == null, "at $fontSize the block laid out $laidOut and the sides $sides")
      sizes++
      if (sides != null) past++
      fontSize *= SWEEP_STEP
    }
    assertTrue(past in 1 until sizes, "$past of $sizes font sizes measured past an Int, so the sweep missed the edge")
  }

  @Test
  fun ordinaryTextLaysOutInsideWhatAnIntHolds() {
    assertNull(textSidesPastLayout(TEXT, STYLE, FRAME))
  }

  private fun drawnSize(): Size =
    assertIs<TextRaster.Drawn<TextSamples>>(rasterizeText(TEXT, STYLE, FRAME, Int.MAX_VALUE)).pixels.size

  private fun capabilities(maxTextureSize: Int): RenderCapabilities =
    RenderCapabilities(
      api = RenderApi.OpenGlEs,
      supportsFragmentShader = true,
      supportsComputeShader = false,
      supportsHdr = false,
      colorSpaces = setOf(ColorSpace.Bt709),
      maxTextureSize = maxTextureSize,
      features = setOf(RenderFeature.TextRendering),
    )

  private companion object {
    val FRAME = Size(1280, 720)
    val STYLE = TextStyle(color = 0xFFC8C8C8.toInt(), backgroundColor = 0x40C8C8C8)

    // Wider than it is tall, so the side refused is the width.
    val PICTURE = Size(12, 8)

    // Ten of the picture's twelve columns, which a quarter turn shows eight wide and ten tall.
    val CUT = PictureCrop(1, 0, Size(10, 8))
    val PREVIEW =
      Attributes(
        inputSize = FRAME,
        outputSize = FRAME,
        layoutSize = FRAME,
        colorSpace = ColorSpace.Bt709,
        hdrTransfer = null,
        frameRate = 30f,
        span = TimeRange.of(Duration.ZERO, 1.seconds),
      )

    const val TEXT = "Filmstrip"
    const val PLATE = 0x80808080.toInt()
    const val HUGE = 1e7f

    // Three percent apart from well inside an Int to past it, so a few sizes land on each side of the edge.
    const val SWEEP_FROM = 1e4f
    const val SWEEP_TO = 1e7f
    const val SWEEP_STEP = 1.03f
    const val OVERLAY_SCALE = 0.2f
  }
}

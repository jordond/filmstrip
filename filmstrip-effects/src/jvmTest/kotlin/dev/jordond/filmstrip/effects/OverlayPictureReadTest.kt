package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayOffset
import dev.jordond.filmstrip.effects.overlay.OverlayPictureDecoder
import dev.jordond.filmstrip.effects.overlay.OverlayPictureRead
import dev.jordond.filmstrip.effects.overlay.slideIn
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import java.awt.image.BufferedImage
import java.awt.image.Raster
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How the filter graph catalogue reads an overlay picture, through the JDK and through a backend's
 * [OverlayPictureDecoder].
 */
class OverlayPictureReadTest {
  // The JDK tags a grey PNG as linear light, so a converted read lifts every mid grey. ffmpeg draws the stored samples,
  // and so does the grade, which puts a grey texel where the same grey stored as RGB lands.
  @Test
  fun `a grey PNG on an HDR grade lands where the same grey stored as RGB does`() {
    val levels = intArrayOf(0, 64, 128, 192, 230, 255)
    val grey = BufferedImage(levels.size, 1, BufferedImage.TYPE_BYTE_GRAY)
    grey.raster.setPixels(0, 0, levels.size, 1, levels)
    val rgb = BufferedImage(levels.size, 1, BufferedImage.TYPE_INT_ARGB)
    levels.forEachIndexed { x, level -> rgb.setRGB(x, 0, OPAQUE or (level shl 16) or (level shl 8) or level) }

    for (transfer in HdrTransfer.entries) {
      val fromGrey = mappedRaster(pngOf(grey), transfer)
      val fromRgb = mappedRaster(pngOf(rgb), transfer)
      levels.indices.forEach { x ->
        assertContentEquals(
          fromRgb.getPixel(x, 0, IntArray(4)),
          fromGrey.getPixel(x, 0, IntArray(4)),
          "$transfer grey ${levels[x]}",
        )
      }
    }
  }

  @Test
  fun `an animated overlay the JDK cannot read is sized by the decoder`() {
    val measured = mutableListOf<ImageSource>()
    val decoder = FakeDecoder(size = SQUARE, measured = measured)
    val spec = sliding(ImageSource.ofBytes(UNREADABLE))

    assertIs<EffectResolution.Resolved>(BuiltInEffectResolver(decoder).resolve(spec, capabilities(), attributes(null)))
    assertEquals(listOf(spec.image), measured)
  }

  @Test
  fun `an animated overlay the JDK reads is not handed to the decoder`() {
    val measured = mutableListOf<ImageSource>()
    val spec = sliding(ImageSource.ofBytes(pngOf(BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB))))

    val resolver = BuiltInEffectResolver(FakeDecoder(size = SQUARE, measured = measured))
    assertIs<EffectResolution.Resolved>(resolver.resolve(spec, capabilities(), attributes(null)))
    assertTrue(measured.isEmpty(), "the decoder measured a picture the JDK reads")
  }

  @Test
  fun `an animated overlay neither can read is refused with the decoder's reason`() {
    val spec = sliding(ImageSource.ofBytes(UNREADABLE))
    val resolver = BuiltInEffectResolver(FakeDecoder(size = null, reason = REASON))

    val refused = assertIs<EffectResolution.Unsupported>(resolver.resolve(spec, capabilities(), attributes(null)))
    assertTrue("header" in refused.message && REASON in refused.message, refused.message)
  }

  @Test
  fun `an overlay on an HDR grade neither can decode is refused with the decoder's reason`() {
    val spec = ImageOverlay(ImageSource.ofBytes(UNREADABLE), Corner.TopStart)
    val resolver = BuiltInEffectResolver(FakeDecoder(size = null, reason = REASON))

    val refused =
      assertIs<EffectResolution.Unsupported>(resolver.resolve(spec, capabilities(), attributes(HdrTransfer.Pq)))
    assertTrue(REASON in refused.message, refused.message)
  }

  private fun sliding(image: ImageSource): ImageOverlay =
    ImageOverlay(image, Corner.TopStart, animation = slideIn(OverlayOffset(SLIDE, 0f), 1.seconds))

  private fun mappedRaster(
    png: ByteArray,
    transfer: HdrTransfer,
  ): Raster {
    val spec = ImageOverlay(ImageSource.ofBytes(png), Corner.TopStart)
    val resolved =
      assertIs<EffectResolution.Resolved>(BuiltInEffectResolver().resolve(spec, capabilities(), attributes(transfer)))
    val mapped =
      assertIs<ImageSource.Bytes>(
        resolved.effect.fragment.auxInputs
          .first()
          .image,
      ).bytes
    return ImageIO.read(ByteArrayInputStream(mapped)).raster
  }

  private fun pngOf(image: BufferedImage): ByteArray =
    ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()

  private fun attributes(transfer: HdrTransfer?): Attributes =
    Attributes(
      inputSize = FRAME,
      outputSize = FRAME,
      layoutSize = FRAME,
      colorSpace = if (transfer == null) ColorSpace.Bt709 else ColorSpace.Bt2020,
      hdrTransfer = transfer,
      frameRate = RATE,
      span = TimeRange.of(Duration.ZERO, 2.seconds),
    )

  private fun capabilities(): RenderCapabilities =
    RenderCapabilities(
      api = RenderApi.FilterGraph,
      supportsFragmentShader = false,
      supportsComputeShader = false,
      supportsHdr = true,
      colorSpaces = setOf(ColorSpace.Bt709, ColorSpace.Bt2020),
      maxTextureSize = 16_384,
      features = emptySet(),
    )

  private class FakeDecoder(
    private val size: Size?,
    private val reason: String = "",
    private val measured: MutableList<ImageSource> = mutableListOf(),
  ) : OverlayPictureDecoder {
    override fun decode(image: ImageSource): OverlayPictureRead<BufferedImage> = OverlayPictureRead.Refused(reason)

    override fun measure(image: ImageSource): OverlayPictureRead<Size> {
      measured += image
      return size?.let { OverlayPictureRead.Read(it) } ?: OverlayPictureRead.Refused(reason)
    }
  }

  private companion object {
    const val OPAQUE = 0xFF shl 24
    const val SLIDE = 0.25f
    const val REASON = "Invalid data found when processing input"
    val UNREADABLE = "not a picture the JDK reads".encodeToByteArray()
    val SQUARE = Size(64, 64)
    val FRAME = Size(640, 360)
    const val RATE = 30f
  }
}

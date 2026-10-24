package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.overlay.DecodedPicture
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayPictureRead
import dev.jordond.filmstrip.effects.overlay.StoredSamples
import dev.jordond.filmstrip.ffmpeg.HdrRender.CAPABILITIES
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegPictureDecoder
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.pictureColour
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.SIXTEEN_BIT_MAX
import dev.jordond.filmstrip.media.pictureLight
import dev.jordond.filmstrip.media.pictureSrgbCodes
import dev.jordond.filmstrip.media.signalFromNits
import dev.jordond.filmstrip.test.AuthoredColours.MID_GREEN
import dev.jordond.filmstrip.test.IccPictures
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * JPEG overlays carrying an EXIF orientation, which this ffmpeg turns when it decodes them.
 *
 * The SDR graph opens an untagged JPEG itself and draws it turned, so every read this process makes of a picture goes
 * through the same decoder: a tagged picture's SDR codes, every picture on an HDR grade, and the size an animation is
 * laid out against. Each is held against the decoder's own turned samples.
 */
class FfmpegOverlayOrientationTest {
  private val fixture = FfmpegExportFixture()

  @Test
  fun `a tagged JPEG with an EXIF orientation is drawn turned the way this ffmpeg turns it`() {
    if (!fixture.available()) return
    val picture = IccPictures.LINEAR_SRGB
    val oriented = orientedJpeg(jpegOf(wide(picture.texel)), ROTATED_CLOCKWISE)
    val turned = turnedDecode(oriented)

    val tagged = taggedJpeg(oriented, picture.profile)
    val spec = ImageOverlay(ImageSource.ofBytes(tagged), Corner.TopStart)
    val drawn = ImageIO.read(ByteArrayInputStream(assertIs<ImageSource.Bytes>(sdrImage(spec)).bytes))
    assertEquals(turned.size, Size(drawn.width, drawn.height), "the tagged JPEG was drawn unturned")
    val argb = drawn.getRGB(0, 0, drawn.width, drawn.height, null, 0, drawn.width)
    assertContentEquals(pictureSrgbCodes(pictureColour(tagged), argbOf(turned)), argb)
  }

  @Test
  fun `an untagged JPEG with an EXIF orientation is laid out and drawn turned on SDR and HDR grades`() {
    if (!fixture.available()) return
    val oriented = orientedJpeg(jpegOf(wide(IccPictures.LINEAR_SRGB.texel)), ROTATED_CLOCKWISE)
    val turned = turnedDecode(oriented)

    val spec = ImageOverlay(ImageSource.ofBytes(oriented), Corner.TopStart)
    assertSame(spec.image, sdrImage(spec), "the SDR graph does not open the JPEG itself")
    val measured = decoder.measure(spec.image)
    val laidOut = assertIs<OverlayPictureRead.Read<Size>>(measured).value
    assertEquals(turned.size, laidOut, "an animation is laid out unturned")

    val argb = argbOf(turned)
    for (transfer in HdrTransfer.entries) {
      val graded = ImageIO.read(ByteArrayInputStream(assertIs<ImageSource.Bytes>(hdrImage(spec, transfer)).bytes))
      assertEquals(turned.size, Size(graded.width, graded.height), "$transfer drew the JPEG unturned")
      for ((x, y) in listOf(0 to 0, turned.size.width / 2 to turned.size.height - 1)) {
        val signal = transfer.signalFromNits(pictureLight(null, intArrayOf(argb[y * turned.size.width + x])).copyOf(3))
        val expected = signal.map { (it.coerceIn(0f, 1f) * SIXTEEN_BIT_MAX).roundToInt() }
        assertEquals(
          expected,
          graded.raster
            .getPixel(x, y, IntArray(4))
            .copyOf(3)
            .toList(),
          "$transfer at $x, $y",
        )
      }
    }
  }

  // This ffmpeg's decode of [jpeg], held to have turned the wide picture upright.
  private fun turnedDecode(jpeg: ByteArray): DecodedPicture {
    val read = decoder.decode(ImageSource.ofBytes(jpeg))
    val decoded = assertNotNull((read as? OverlayPictureRead.Read)?.value, "this ffmpeg decoded nothing")
    assertEquals(Size(WIDE.height, WIDE.width), decoded.size, "this ffmpeg did not turn the JPEG")
    assertTrue(argbOf(decoded).toSet().size > 1, "the turned picture is one colour, so its turn cannot be read")
    return decoded
  }

  private fun argbOf(decoded: DecodedPicture): IntArray = assertIs<StoredSamples.Argb>(decoded.samples).argb

  // A picture wider than it is tall, its left quarter [texel] and the rest mid green, so a turn moves the quarter.
  private fun wide(texel: Int): BufferedImage =
    BufferedImage(WIDE.width, WIDE.height, BufferedImage.TYPE_INT_RGB).apply {
      for (x in 0 until width) {
        for (y in 0 until height) setRGB(x, y, if (x < width / 4) texel else MID_GREEN)
      }
    }

  private fun jpegOf(image: BufferedImage): ByteArray =
    ByteArrayOutputStream().also { assertTrue(ImageIO.write(image, "jpg", it), "the JDK wrote no JPEG") }.toByteArray()

  private fun sdrImage(spec: ImageOverlay): ImageSource {
    val attributes =
      Attributes(
        inputSize = FRAME,
        outputSize = FRAME,
        layoutSize = FRAME,
        colorSpace = ColorSpace.Bt709,
        hdrTransfer = null,
        frameRate = RATE,
        span = TimeRange.of(Duration.ZERO, 2.seconds),
      )
    return resolvedImage(spec, attributes)
  }

  private fun hdrImage(
    spec: ImageOverlay,
    transfer: HdrTransfer,
  ): ImageSource = resolvedImage(spec, HdrRender.attributes(transfer, 2.seconds))

  private fun resolvedImage(
    spec: ImageOverlay,
    attributes: Attributes,
  ): ImageSource {
    val resolution = BuiltInEffectResolver(decoder).resolve(spec, CAPABILITIES, attributes)
    val resolved = assertIs<EffectResolution.Resolved>(resolution)
    return resolved.effect.fragment.auxInputs
      .first()
      .image
  }

  private companion object {
    val decoder = FfmpegPictureDecoder("ffmpeg")
    val FRAME = Size(640, 360)
    const val RATE = 30f

    // A picture wider than it is tall, and the EXIF orientation that turns it a quarter clockwise to stand upright.
    val WIDE = Size(32, 16)
    const val ROTATED_CLOCKWISE = 6
  }
}

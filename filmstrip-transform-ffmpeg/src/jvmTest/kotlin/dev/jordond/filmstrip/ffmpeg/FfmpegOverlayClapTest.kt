package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.InternalFilmstripApi
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
import dev.jordond.filmstrip.internal.pictureCropOf
import dev.jordond.filmstrip.internal.pictureOrientationOf
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.PictureOrientation
import dev.jordond.filmstrip.media.UnturnedHeif
import dev.jordond.filmstrip.media.croppedSamples
import dev.jordond.filmstrip.media.heifWithoutOrientation
import dev.jordond.filmstrip.media.orientedSamples
import dev.jordond.filmstrip.test.CicpTag
import dev.jordond.filmstrip.test.HeicSubsampledPictures
import dev.jordond.filmstrip.test.OrientedPictures
import org.junit.Assume.assumeTrue
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * HEICs with a `clap` on their primary item, drawn on the ffmpeg backend at the crop core reads.
 *
 * ffmpeg 8.0.1 and 9.0.2 crop an AVIF or HEIC by its `clap` themselves, with `-noautorotate` or without, so the decoder
 * is handed the file with its `clap` dropped and decodes the whole stored frame. The SDR graph then opens a PNG of
 * that decode cut at [pictureCropOf]'s rectangle and laid out by [pictureOrientationOf], held texel by texel against
 * the same picture without a `clap` cut and laid out by the shared functions.
 */
@OptIn(InternalFilmstripApi::class)
class FfmpegOverlayClapTest {
  @Test
  fun `the decoder decodes every clapped picture uncropped once the drop takes its clap`() {
    val stored = storedArgb()
    for ((name, properties) in CLAPPED) {
      val clapped = OrientedPictures.heifWithProperties(still, properties)
      val handed = assertIs<UnturnedHeif.Dropped>(heifWithoutOrientation(clapped), name).bytes

      val decoded = decodedOf(handed)

      assertEquals(SIZE, decoded.size, name)
      assertContentEquals(stored, assertIs<StoredSamples.Argb>(decoded.samples).argb, name)
    }
  }

  @Test
  fun `an SDR graph opens the still cut at core's crop and then turned`() {
    val stored = openedCodes(still)
    assertEquals(SIZE, stored.first)
    for ((name, properties) in CLAPPED) {
      val clapped = OrientedPictures.heifWithProperties(still, properties)
      val crop = assertNotNull(pictureCropOf(clapped), name)
      val orientation = pictureOrientationOf(clapped)
      val expected = orientedSamples(orientation, croppedSamples(crop, stored.second, SIZE), crop.size)

      val (size, codes) = openedCodes(clapped)

      assertEquals(orientation.orientedSize(crop.size), size, name)
      assertContentEquals(expected, codes, name)
    }
  }

  // ffmpeg's limit: a data URI only ffmpeg opens leaves core no bytes to read a tag or a clap from, so a PNG tagged 6
  // is drawn as stored and a clapped HEIC at the size ffmpeg decodes it to, cut by nothing here.
  @Test
  fun `a picture only ffmpeg opens is drawn as ffmpeg decodes it`() {
    val png = OrientedPictures.png()
    val tagged = dataUri(OrientedPictures.taggedPng(png, PictureOrientation.Rotate90.exif))
    val stored = openedCodes(ImageSource.ofBytes(png))

    assertEquals(OrientedPictures.SIZE, stored.first)
    val drawn = openedCodes(ImageSource.ofUri(tagged))
    assertEquals(stored.first, drawn.first)
    assertContentEquals(stored.second, drawn.second)

    decodedOf(still)
    val clapped = OrientedPictures.heifWithProperties(still, CLAPPED.getValue("offset"))
    val uri = ImageSource.ofUri(dataUri(clapped))
    val decoded = assertIs<DecodedPicture>(assertIs<OverlayPictureRead.Read<*>>(decoder.decode(uri)).value)
    assertEquals(decoded.size, openedCodes(uri).first)
  }

  private fun storedArgb(): IntArray = assertIs<StoredSamples.Argb>(decodedOf(still).samples).argb

  private fun decodedOf(bytes: ByteArray): DecodedPicture {
    val read = decoder.decode(ImageSource.ofBytes(bytes))
    assumeTrue("this ffmpeg opens no HEIC", read is OverlayPictureRead.Read<*>)
    return assertIs<DecodedPicture>(assertIs<OverlayPictureRead.Read<*>>(read).value)
  }

  // The size and packed codes of the PNG an SDR graph opens for an overlay of bytes.
  private fun openedCodes(bytes: ByteArray): Pair<Size, IntArray> {
    decodedOf(still)
    return openedCodes(ImageSource.ofBytes(bytes))
  }

  // The size and packed codes of the PNG an SDR graph opens for an overlay of image, which has to be one it decodes.
  private fun openedCodes(image: ImageSource): Pair<Size, IntArray> {
    val spec = ImageOverlay(image, Corner.TopStart)
    val resolved = assertIs<EffectResolution.Resolved>(BuiltInEffectResolver(decoder).resolve(spec, CAPABILITIES, SDR))
    val png =
      assertIs<ImageSource.Bytes>(
        resolved.effect.fragment.auxInputs
          .single()
          .image,
      )
    val image = ImageIO.read(ByteArrayInputStream(png.bytes))
    val size = Size(image.width, image.height)
    return size to image.getRGB(0, 0, size.width, size.height, null, 0, size.width)
  }

  private fun dataUri(bytes: ByteArray): String =
    "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(bytes)

  private companion object {
    val decoder = FfmpegPictureDecoder("ffmpeg")
    val still = HeicSubsampledPictures.EIGHT_BIT_420.bytes(CicpTag(1, 13, 1, true))
    val SIZE = Size(64, 64)
    val FRAME = Size(640, 360)
    val SDR =
      Attributes(
        inputSize = FRAME,
        outputSize = FRAME,
        layoutSize = FRAME,
        colorSpace = ColorSpace.Bt709,
        hdrTransfer = null,
        frameRate = 30f,
        span = TimeRange.of(Duration.ZERO, 2.seconds),
      )

    // A centred and an offset clean aperture, and an offset one listed after a quarter turn, which crops the turned
    // frame and which core carries back to the stored picture.
    val CLAPPED =
      mapOf(
        "centred" to listOf(OrientedPictures.cleanAperture(40, 24)),
        "offset" to listOf(OrientedPictures.cleanAperture(40, 24, -9, 7, offsetDenominator = 2)),
        "after a turn" to
          listOf(
            OrientedPictures.quarterTurn(1),
            OrientedPictures.cleanAperture(40, 24, horizontalOffset = 5, verticalOffset = -3),
          ),
      )
  }
}

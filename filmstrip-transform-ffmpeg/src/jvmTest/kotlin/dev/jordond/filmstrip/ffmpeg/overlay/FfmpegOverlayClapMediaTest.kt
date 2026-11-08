package dev.jordond.filmstrip.ffmpeg.overlay

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.overlay.DecodedPicture
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayPictureRead
import dev.jordond.filmstrip.effects.overlay.StoredSamples
import dev.jordond.filmstrip.ffmpeg.internal.picture.FfmpegPictureDecoder
import dev.jordond.filmstrip.ffmpeg.support.HdrRender.CAPABILITIES
import dev.jordond.filmstrip.ffmpeg.support.hostFfmpeg
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.picture.PictureOrientation
import dev.jordond.filmstrip.picture.croppedSamples
import dev.jordond.filmstrip.picture.heif.UnturnedHeif
import dev.jordond.filmstrip.picture.heif.heifWithoutOrientation
import dev.jordond.filmstrip.picture.orientedSamples
import dev.jordond.filmstrip.picture.pictureCropOf
import dev.jordond.filmstrip.picture.pictureOrientationOf
import dev.jordond.filmstrip.test.CicpTag
import dev.jordond.filmstrip.test.HeicSubsampledPictures
import dev.jordond.filmstrip.test.OrientedPictures
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Assume.assumeTrue
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
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
class FfmpegOverlayClapMediaTest {
  @Test
  fun `the decoder decodes every clapped picture uncropped once the drop takes its clap`() {
    val stored = storedArgb()
    for ((name, properties) in CLAPPED) {
      val clapped = OrientedPictures.heifWithProperties(still, properties)
      val handed = withClue(name) { heifWithoutOrientation(clapped).shouldBeInstanceOf<UnturnedHeif.Dropped>() }.bytes

      val decoded = decodedOf(handed)

      withClue(name) { decoded.size shouldBe SIZE }
      withClue(name) { decoded.samples.shouldBeInstanceOf<StoredSamples.Argb>().argb shouldBe stored }
    }
  }

  @Test
  fun `an SDR graph opens the still cut at core's crop and then turned`() {
    val stored = openedCodes(still)
    stored.first shouldBe SIZE
    for ((name, properties) in CLAPPED) {
      val clapped = OrientedPictures.heifWithProperties(still, properties)
      val crop = withClue(name) { pictureCropOf(clapped).shouldNotBeNull() }
      val orientation = pictureOrientationOf(clapped)
      val expected = orientedSamples(orientation, croppedSamples(crop, stored.second, SIZE), crop.size)

      val (size, codes) = openedCodes(clapped)

      withClue(name) { size shouldBe orientation.orientedSize(crop.size) }
      withClue(name) { codes shouldBe expected }
    }
  }

  // ffmpeg's limit: a data URI only ffmpeg opens leaves core no bytes to read a tag or a clap from, so a PNG tagged 6
  // is drawn as stored and a clapped HEIC at the size ffmpeg decodes it to, cut by nothing here.
  @Test
  fun `a picture only ffmpeg opens is drawn as ffmpeg decodes it`() {
    val png = OrientedPictures.png()
    val tagged = dataUri(OrientedPictures.taggedPng(png, PictureOrientation.Rotate90.exif))
    val stored = openedCodes(ImageSource.ofBytes(png))

    stored.first shouldBe OrientedPictures.SIZE
    val drawn = openedCodes(ImageSource.ofUri(tagged))
    drawn.first shouldBe stored.first
    drawn.second shouldBe stored.second

    decodedOf(still)
    val clapped = OrientedPictures.heifWithProperties(still, CLAPPED.getValue("offset"))
    val uri = ImageSource.ofUri(dataUri(clapped))
    val decoded =
      (
        decoder
          .decode(
            uri,
          ).shouldBeInstanceOf<OverlayPictureRead.Read<*>>()
          .value
      ).shouldBeInstanceOf<DecodedPicture>()
    openedCodes(uri).first shouldBe decoded.size
  }

  private fun storedArgb(): IntArray = decodedOf(still).samples.shouldBeInstanceOf<StoredSamples.Argb>().argb

  private fun decodedOf(bytes: ByteArray): DecodedPicture {
    val read = decoder.decode(ImageSource.ofBytes(bytes))
    assumeTrue("this ffmpeg opens no HEIC", read is OverlayPictureRead.Read<*>)
    return (read.shouldBeInstanceOf<OverlayPictureRead.Read<*>>().value).shouldBeInstanceOf<DecodedPicture>()
  }

  // The size and packed codes of the PNG an SDR graph opens for an overlay of bytes.
  private fun openedCodes(bytes: ByteArray): Pair<Size, IntArray> {
    decodedOf(still)
    return openedCodes(ImageSource.ofBytes(bytes))
  }

  // The size and packed codes of the PNG an SDR graph opens for an overlay of image, which has to be one it decodes.
  private fun openedCodes(image: ImageSource): Pair<Size, IntArray> {
    val spec = ImageOverlay(image, Corner.TopStart)
    val resolved =
      BuiltInEffectResolver(
        decoder,
      ).resolve(spec, CAPABILITIES, SDR).shouldBeInstanceOf<EffectResolution.Resolved>()
    val png =
      resolved.effect.fragment.auxInputs
        .single()
        .image
        .shouldBeInstanceOf<ImageSource.Bytes>()
    val image = ImageIO.read(ByteArrayInputStream(png.bytes))
    val size = Size(image.width, image.height)
    return size to image.getRGB(0, 0, size.width, size.height, null, 0, size.width)
  }

  private fun dataUri(bytes: ByteArray): String =
    "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(bytes)

  private companion object {
    val decoder = FfmpegPictureDecoder(hostFfmpeg)
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

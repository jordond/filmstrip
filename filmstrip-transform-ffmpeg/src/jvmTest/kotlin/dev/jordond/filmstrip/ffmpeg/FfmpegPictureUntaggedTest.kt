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
import dev.jordond.filmstrip.ffmpeg.OverlayConstants.OVERLAY_SIZE
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegPictureDecoder
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.pictureColour
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.SIXTEEN_BIT_MAX
import dev.jordond.filmstrip.media.pictureLight
import dev.jordond.filmstrip.media.signalFromNits
import dev.jordond.filmstrip.test.ColourPictures
import dev.jordond.filmstrip.test.HeifStills
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Pictures core reads as untagged whose decode carries a description anyway, drawn by this ffmpeg as untagged.
 *
 * ffmpeg 9.0 writes `cICP` 1/4 into the PNG it decodes a `gAMA` 45455 and Rec. 709 `cHRM` picture to, which is gamma
 * 2.2 where core reads sRGB, and `cICP` 12/13 for an AVIF with no `colr` box whose AV1 sequence header signals
 * Display P3. Neither is the picture's own description, so each lands on SDR, PQ and HLG grades exactly where an
 * untagged picture of the same decoded texel does.
 */
class FfmpegPictureUntaggedTest {
  private val fixture = FfmpegExportFixture()

  @Test
  fun `a gAMA sRGB PNG lands untagged whatever its decode carries`() {
    if (!fixture.available()) return
    val png = ColourPictures.GAMMA_SRGB.pngOf(OVERLAY_SIZE)
    assertNull(pictureColour(png), "core reads the gAMA sRGB PNG as tagged")
    val spec = ImageOverlay(ImageSource.ofBytes(png), Corner.TopStart)

    assertSame(spec.image, resolvedImage(spec, SDR), "the SDR graph does not open the PNG as it is")
    assertLandsUntagged("gAMA sRGB", spec, decoded(png))
  }

  @Test
  fun `an AVIF whose only colour is its codestream's lands untagged whatever its decode carries`() {
    if (!fixture.available()) return
    val avif = p3CodestreamAvif()
    assertNull(pictureColour(avif), "core reads the codestream's colour as the AVIF's own")
    val spec = ImageOverlay(ImageSource.ofBytes(avif), Corner.TopStart)
    val decoded = decoded(avif)
    val stored = assertIs<StoredSamples.Argb>(decoded.samples).argb

    val drawn = assertIs<ImageSource.Bytes>(resolvedImage(spec, SDR), "the AVIF reached the SDR graph undecoded")
    assertNull(pictureColour(drawn.bytes), "the PNG handed to the graph describes its colour")
    val image = ImageIO.read(ByteArrayInputStream(drawn.bytes))
    assertContentEquals(stored, image.getRGB(0, 0, image.width, image.height, null, 0, image.width), "SDR codes")
    assertLandsUntagged("P3 codestream AVIF", spec, decoded)
  }

  // [spec]'s middle on PQ and HLG grades against the untagged light of [decoded]'s middle texel, and, where this
  // ffmpeg's decode carried a description, a check that taking it would have landed elsewhere.
  private fun assertLandsUntagged(
    name: String,
    spec: ImageOverlay,
    decoded: DecodedPicture,
  ) {
    val argb = assertIs<StoredSamples.Argb>(decoded.samples).argb
    val texel = intArrayOf(argb[decoded.size.height / 2 * decoded.size.width + decoded.size.width / 2])
    decoded.colour?.let { carried ->
      assertFalse(pictureLight(carried, texel).contentEquals(pictureLight(null, texel)), "$name's decode moves nothing")
    }
    for (transfer in HdrTransfer.entries) {
      val mapped = assertIs<ImageSource.Bytes>(resolvedImage(spec, HdrRender.attributes(transfer, 2.seconds))).bytes
      val graded = ImageIO.read(ByteArrayInputStream(mapped))
      val signal = transfer.signalFromNits(pictureLight(null, texel).copyOf(3))
      val expected = signal.map { (it.coerceIn(0f, 1f) * SIXTEEN_BIT_MAX).roundToInt() }
      val landed =
        graded.raster
          .getPixel(graded.width / 2, graded.height / 2, IntArray(4))
          .copyOf(3)
          .toList()
      assertEquals(expected, landed, "$transfer $name")
    }
  }

  private fun decoded(bytes: ByteArray): DecodedPicture {
    val read = decoder.decode(ImageSource.ofBytes(bytes))
    return assertNotNull((read as? OverlayPictureRead.Read)?.value, "this ffmpeg decoded nothing: $read")
  }

  private fun resolvedImage(
    spec: ImageOverlay,
    attributes: Attributes,
  ): ImageSource {
    val resolution = BuiltInEffectResolver(decoder).resolve(spec, CAPABILITIES, attributes)
    return assertIs<EffectResolution.Resolved>(resolution)
      .effect.fragment.auxInputs
      .first()
      .image
  }

  // An eight-by-eight AVIF with no colr box around an AV1 still whose sequence header signals Display P3.
  private fun p3CodestreamAvif(): ByteArray =
    HeifStills.still(
      brand = "avif",
      itemType = "av01",
      configuration = AV1_CONFIGURATION_BOX.hex(),
      codestream = P3_CODESTREAM.hex(),
      size = Size(8, 8),
    )

  private fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

  private companion object {
    val decoder = FfmpegPictureDecoder("ffmpeg")
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

    // The mdat payload of an AVIF from ffmpeg 6.1.1 with libaom, on Ubuntu 24.04:
    //   ffmpeg -f lavfi -i "color=c=black:s=8x8,format=yuv420p,geq=lum=128:cb=96:cr=176" -frames:v 1 \
    //     -color_range pc -color_primaries smpte432 -color_trc iec61966-2-1 -colorspace smpte170m \
    //     -c:v libaom-av1 -aom-params lossless=1 -still-picture 1 -f avif p3.avif
    // Its sequence header signals primaries 12, transfer 13 and matrix 6 at full range.
    const val P3_CODESTREAM = "0a081808bf6c86068342320c10000002dd90816283ea9354"

    // The same file's whole av1C box, Main profile 4:2:0 with no config OBUs.
    const val AV1_CONFIGURATION_BOX = "0000000c" + "61763143" + "81000c00"
  }
}

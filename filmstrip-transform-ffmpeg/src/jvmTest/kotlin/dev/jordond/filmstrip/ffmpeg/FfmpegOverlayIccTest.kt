package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayPictureDecoder
import dev.jordond.filmstrip.effects.overlay.OverlayPictureRead
import dev.jordond.filmstrip.effects.overlay.StoredSamples
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.ExportConstants.CODE_TOLERANCE
import dev.jordond.filmstrip.ffmpeg.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.OverlayConstants.CHANNEL_DRIFT
import dev.jordond.filmstrip.ffmpeg.OverlayConstants.OVERLAY_SIZE
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegExportEngine
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegPictureDecoder
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegPreviewStream
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegRuntime
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.pictureColour
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.PictureColour
import dev.jordond.filmstrip.media.SIXTEEN_BIT_MAX
import dev.jordond.filmstrip.media.authoredHdrNits
import dev.jordond.filmstrip.media.pictureLight
import dev.jordond.filmstrip.media.pictureSrgbCodes
import dev.jordond.filmstrip.media.signalFromNits
import dev.jordond.filmstrip.media.tenBitCodesFromSignal
import dev.jordond.filmstrip.test.IccPicture
import dev.jordond.filmstrip.test.IccPictures
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Overlay pictures tagged with an ICC profile, drawn by this ffmpeg in the colour their profile gives them.
 *
 * Each picture is drawn over a flat fill in a letterbox bar, one to a corner. On an SDR export it is held against the
 * codes [pictureSrgbCodes] gives it, and on a kept HDR grade against where [pictureLight]'s display light lands, both
 * read through the shared helpers rather than a copied number. Pictures only ffmpeg reads, a WebP, an AVIF, a HEIC and
 * a JPEG XL, are checked at the resolve, and so is a tagged JPEG through either reader.
 * A codec this ffmpeg lacks skips its test as an assumption. One preview frame checks the preview draws what the
 * export does.
 */
class FfmpegOverlayIccTest {
  private val fixture = FfmpegExportFixture()

  @Test
  fun `every tagged picture lands on an SDR export where its profile's sRGB codes put it`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest
      fixture.assertSdrLandings(landings(), "filmstrip-icc")
    }

  // Linear sRGB's mid grey is the picture whose stored codes sit furthest from its colour, so it is the one that
  // tells a tagged read from an untagged one on an SDR export.
  @Test
  fun `the linear sRGB picture's colour sits too far from its stored codes for the drift to hide`() {
    val picture = IccPictures.LINEAR_SRGB
    val stored = channelsOf(picture.texel).toList()
    val colour = srgbOf(picture).toList()

    assertTrue(stored.indices.all { abs(stored[it] - colour[it]) > 2 * CHANNEL_DRIFT }, "$stored and $colour")
  }

  @Test
  fun `every tagged picture lands on a kept PQ grade where its profile's light puts it`() =
    runTest(timeout = TIMEOUT) { fixture.assertHdrLandings(landings(), HdrTransfer.Pq, "filmstrip-icc") }

  @Test
  fun `every tagged picture lands on a kept HLG grade where its profile's light puts it`() =
    runTest(timeout = TIMEOUT) { fixture.assertHdrLandings(landings(), HdrTransfer.Hlg, "filmstrip-icc") }

  // The PQ export above reads Display P3 orange within the bound of its own light. Its light sits outside BT.709, far
  // enough from the colour clipped to BT.709 first that the same bound tells the two apart.
  @Test
  fun `Display P3 orange on PQ lands outside the BT709 gamut by more than the bound`() {
    val picture = IccPictures.DISPLAY_P3
    val light = pictureLight(colourOf(picture), intArrayOf(picture.texel))
    val bt709 = authoredHdrNits(pictureSrgbCodes(colourOf(picture), intArrayOf(picture.texel)).single())
    val wide = hdrCodes(HdrTransfer.Pq, light.copyOf(3))
    val clipped = hdrCodes(HdrTransfer.Pq, bt709)

    assertTrue(wide.indices.any { abs(wide[it] - clipped[it]) > 2 * CODE_TOLERANCE }, "$wide and $clipped")
  }

  @Test
  fun `a tagged WebP only ffmpeg reads is drawn from the profile its decode carries`() {
    if (!fixture.available()) return
    val picture = IccPictures.LINEAR_SRGB
    val lossless = listOf("-c:v", "libwebp", "-lossless", "1")
    val webp = encoded(pngOf(picture.texel, OVERLAY_SIZE), "webp", lossless)
    assumeTrue("this ffmpeg has no libwebp encoder", webp != null)
    val tagged = taggedWebp(checkNotNull(webp).readBytes().also { webp.delete() }, picture.profile)
    assertNull(ImageIO.read(ByteArrayInputStream(tagged)), "the JDK reads WebP here, so this does not reach ffmpeg")
    val colour = assertNotNull(pictureColour(tagged), "the WebP does not carry the profile")

    assertResolvesTo(ImageSource.ofBytes(tagged), colour, picture.texel, "tagged WebP")
  }

  // ffmpeg's JPEG XL encoder writes the profile a PNG carries into the codestream, where only the decode finds it. The
  // encoder may store the profile as a colour encoding of its own, so the colour is what is held, not the bytes.
  @Test
  fun `a tagged JPEG XL is drawn from the profile its decode carries`() {
    if (!fixture.available()) return
    val picture = IccPictures.LINEAR_SRGB
    val jxl = encoded(picture.pngOf(OVERLAY_SIZE), "jxl", listOf("-c:v", "libjxl", "-distance", "0"))
    assumeTrue("this ffmpeg has no libjxl encoder", jxl != null)
    val bytes = checkNotNull(jxl).readBytes().also { jxl.delete() }
    assertNull(pictureColour(bytes), "a JPEG XL keeps its profile out of reach of the container read")
    assumeTrue(
      "this ffmpeg cannot decode JPEG XL",
      decoder.decode(ImageSource.ofBytes(bytes)) is OverlayPictureRead.Read,
    )

    assertResolvesTo(ImageSource.ofBytes(bytes), colourOf(picture), picture.texel, "tagged JPEG XL", tolerance = 1)
  }

  // An AVIF from ffmpeg carries no profile, so one is written into its item properties here, and the texel expected is
  // the one this ffmpeg decodes out of the same AV1 payload.
  @Test
  fun `a tagged AVIF only ffmpeg reads is drawn from the profile its container carries`() {
    if (!fixture.available()) return
    val picture = IccPictures.DISPLAY_P3
    val png = pngOf(picture.texel, OVERLAY_SIZE)
    val avif = AV1_ENCODERS.firstNotNullOfOrNull { encoded(png, "avif", listOf("-c:v", it)) }
    assumeTrue("this ffmpeg has no AV1 encoder that writes AVIF", avif != null)
    val untagged = checkNotNull(avif).readBytes().also { avif.delete() }

    assertTaggedHeif(untagged, picture.profile, "tagged AVIF")
  }

  @Test
  fun `a tagged HEIC only ffmpeg reads is drawn from the profile its container carries`() {
    if (!fixture.available()) return
    val heic = fixture.heicFixture()
    assertTrue(heic.isFile, "no $HEIC_FIXTURE among the downloaded fixtures")
    val untagged = heic.readBytes()
    assumeTrue(
      "this ffmpeg cannot decode a HEIC",
      decoder.decode(ImageSource.ofBytes(untagged)) is OverlayPictureRead.Read,
    )

    assertTaggedHeif(untagged, IccPictures.LINEAR_SRGB.profile, "tagged HEIC")
  }

  // The JDK's JPEG reader converts through an APP2 profile on its own. A tagged JPEG is held against the samples each
  // reader stores, the JDK's without a decoder and this ffmpeg's with one.
  @Test
  fun `a tagged JPEG is drawn from the samples it stores whichever reader decodes it`() {
    if (!fixture.available()) return
    for (picture in listOf(IccPictures.DISPLAY_P3, IccPictures.LINEAR_SRGB)) {
      val jpeg = jpegOf(flat(picture.texel, OVERLAY_SIZE))
      val bytes = taggedJpeg(jpeg, picture.profile)
      val tagged = ImageSource.ofBytes(bytes)
      val colour = assertNotNull(pictureColour(bytes), "the ${picture.name} JPEG does not carry the profile")
      val read = ImageIO.read(ByteArrayInputStream(jpeg))
      val jdk = read.getRGB(read.width / 2, read.height / 2)
      val what = "${picture.name} JPEG"
      assertResolvesTo(tagged, colour, jdk, "$what read by the JDK", reader = null, transfers = PQ_ONLY)
      val ffmpeg = decodedCentre(jpeg)
      assertResolvesTo(tagged, colour, ffmpeg, "$what read by this ffmpeg", transfers = PQ_ONLY)
    }
  }

  @Test
  fun `an sRGB tagged picture reaches an SDR graph as the bytes it was given`() {
    if (!fixture.available()) return
    val spec = ImageOverlay(ImageSource.ofBytes(IccPictures.SRGB.pngOf(OVERLAY_SIZE)), Corner.TopStart)

    assertSame(spec.image, auxImage(spec, null, decoder), "read by this ffmpeg")
    assertSame(spec.image, auxImage(spec, null, null), "read by the JDK")
  }

  @Test
  fun `a tagged picture in a preview frame lands where its profile's sRGB codes put it`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest
      val picture = IccPictures.LINEAR_SRGB
      val overlay = ImageOverlay(ImageSource.ofBytes(picture.pngOf(OVERLAY_SIZE)), Corner.TopStart)
      val composition =
        EditComposition(
          tracks = listOf(Track(listOf(Clip(MediaSource.of(fixture.landscape.absolutePath))))),
          effects = listOf(overlay),
        )
      val components = ComponentRegistry.Builder().add(BuiltInEffectResolver()).build()
      val engine = FfmpegExportEngine(components, FfmpegRuntime.of(FfmpegConfig()))
      val opened = assertIs<PreviewStreamResult.Opened>(engine.openPreview(composition, ExportSpec()))
      val stream = assertIs<FfmpegPreviewStream>(opened.stream)
      val frame =
        try {
          assertNotNull(stream.next(), "the preview delivered no frame")
        } finally {
          stream.close()
        }

      val size = stream.size
      val (x, y) = centreOf(drawnRect(overlay, size, TimeRange.of(Duration.ZERO, 1.seconds), Duration.ZERO), size)
      val at = (y * size.width + x) * RGBA
      val read = Triple(frame[at].toInt() and 0xFF, frame[at + 1].toInt() and 0xFF, frame[at + 2].toInt() and 0xFF)
      assertNear(read, srgbOf(picture), "${picture.name} in a preview frame")
    }

  // [profile] written into [heif], which only ffmpeg reads, and the result resolved against the texel this ffmpeg
  // decodes at the middle of the untagged picture.
  private fun assertTaggedHeif(
    heif: ByteArray,
    profile: ByteArray,
    what: String,
  ) {
    val tagged = taggedHeif(heif, profile)
    assertNull(
      ImageIO.read(ByteArrayInputStream(tagged)),
      "the JDK reads the $what here, so this does not reach ffmpeg",
    )
    val colour = assertNotNull(pictureColour(tagged), "the $what does not carry the profile")

    assertResolvesTo(ImageSource.ofBytes(tagged), colour, decodedCentre(heif), what)
  }

  // [image] resolved on an SDR grade and on [transfers] through [reader], held at the picture's middle against the
  // codes and the light [colour] gives the [stored] texel.
  private fun assertResolvesTo(
    image: ImageSource,
    colour: PictureColour?,
    stored: Int,
    what: String,
    reader: OverlayPictureDecoder? = decoder,
    transfers: List<HdrTransfer> = HdrTransfer.entries,
    tolerance: Int = 0,
  ) {
    val spec = ImageOverlay(image, Corner.TopStart)
    val srgb = channelsOf(pictureSrgbCodes(colour, intArrayOf(stored)).single())
    val drawn = assertIs<ImageSource.Bytes>(auxImage(spec, null, reader), "the $what reached the SDR graph untouched")
    assertNull(pictureColour(drawn.bytes), "the $what reached the SDR graph still tagged")
    val read = channelsOf(ImageIO.read(ByteArrayInputStream(drawn.bytes)).centre())
    assertTrue(read.isNear(srgb, tolerance), "the $what on an SDR grade read $read, not $srgb")

    for (transfer in transfers) {
      val mapped = assertIs<ImageSource.Bytes>(auxImage(spec, transfer, reader)).bytes
      val graded = ImageIO.read(ByteArrayInputStream(mapped))
      val signal = transfer.signalFromNits(pictureLight(colour, intArrayOf(stored)).copyOf(3))
      val expected = signal.map { (it.coerceIn(0f, 1f) * SIXTEEN_BIT_MAX).roundToInt() }
      val texel =
        graded.raster
          .getPixel(graded.width / 2, graded.height / 2, IntArray(RGBA))
          .copyOf(3)
          .toList()
      assertTrue(
        texel.indices.all { abs(texel[it] - expected[it]) <= tolerance * SIXTEEN_BIT_MAX / BYTE_MAX },
        "$transfer $what read $texel, expected $expected",
      )
    }
  }

  // The texel this ffmpeg decodes at the middle of [picture].
  private fun decodedCentre(picture: ByteArray): Int {
    val read = decoder.decode(ImageSource.ofBytes(picture))
    val decoded = assertNotNull((read as? OverlayPictureRead.Read)?.value, "this ffmpeg decoded nothing")
    val argb = assertIs<StoredSamples.Argb>(decoded.samples, "this ffmpeg decoded deeper than eight bits").argb
    return argb[decoded.size.height / 2 * decoded.size.width + decoded.size.width / 2]
  }

  private fun BufferedImage.centre(): Int = getRGB(width / 2, height / 2)

  private fun flat(
    rgb: Int,
    size: Size,
  ): BufferedImage =
    BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_RGB).apply {
      for (x in 0 until width) {
        for (y in 0 until height) setRGB(x, y, rgb)
      }
    }

  private fun jpegOf(image: BufferedImage): ByteArray =
    ByteArrayOutputStream().also { assertTrue(ImageIO.write(image, "jpg", it), "the JDK wrote no JPEG") }.toByteArray()

  private fun auxImage(
    spec: ImageOverlay,
    transfer: HdrTransfer?,
    reader: OverlayPictureDecoder?,
  ): ImageSource {
    val attributes =
      Attributes(
        inputSize = FRAME,
        outputSize = FRAME,
        layoutSize = FRAME,
        colorSpace = if (transfer == null) ColorSpace.Bt709 else ColorSpace.Bt2020,
        hdrTransfer = transfer,
        frameRate = RATE,
        span = TimeRange.of(Duration.ZERO, 2.seconds),
      )
    val resolver = reader?.let(::BuiltInEffectResolver) ?: BuiltInEffectResolver()
    val resolution = resolver.resolve(spec, CAPABILITIES, attributes)
    val resolved = assertIs<EffectResolution.Resolved>(resolution)
    return resolved.effect.fragment.auxInputs
      .first()
      .image
  }

  // Where every tagged picture lands, from the colour its PNG describes.
  private fun landings(): List<ColourLanding> =
    IccPictures.ALL.map { picture ->
      val colour = colourOf(picture)
      val texel = intArrayOf(picture.texel)
      val srgb = pictureSrgbCodes(colour, texel).single()
      ColourLanding(picture.name, picture.pngOf(OVERLAY_SIZE), srgb, pictureLight(colour, texel))
    }

  private fun srgbOf(picture: IccPicture): Triple<Int, Int, Int> =
    channelsOf(pictureSrgbCodes(colourOf(picture), intArrayOf(picture.texel)).single())

  // How [picture]'s PNG describes its colour, read the way every backend reads it.
  private fun colourOf(picture: IccPicture): PictureColour? = pictureColour(picture.pngOf(OVERLAY_SIZE))

  private fun hdrCodes(
    transfer: HdrTransfer,
    nits: FloatArray,
  ): IntArray = tenBitCodesFromSignal(transfer.signalFromNits(nits))

  private companion object {
    val decoder = FfmpegPictureDecoder("ffmpeg")
    val FRAME = Size(640, 360)
    const val RATE = 30f
    const val RGBA = 4
    const val ALPHA_SHIFT = 24
    const val BYTE_MAX = 0xFF
    val PQ_ONLY = listOf(HdrTransfer.Pq)

    // The AV1 encoders either build may have, cerebro's 6.1.1 the first two and the Mac's 9.0.2 the last.
    val AV1_ENCODERS = listOf("libaom-av1", "librav1e", "libsvtav1")
    val CAPABILITIES =
      RenderCapabilities(
        api = RenderApi.FilterGraph,
        supportsFragmentShader = false,
        supportsComputeShader = false,
        supportsHdr = true,
        colorSpaces = setOf(ColorSpace.Bt709, ColorSpace.Bt2020),
        maxTextureSize = 16_384,
        features = emptySet(),
      )
  }
}

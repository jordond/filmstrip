package dev.jordond.filmstrip.ffmpeg.overlay

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.color.PictureColor
import dev.jordond.filmstrip.color.SIXTEEN_BIT_MAX
import dev.jordond.filmstrip.color.authoredHdrNits
import dev.jordond.filmstrip.color.pictureLight
import dev.jordond.filmstrip.color.pictureSrgbCodes
import dev.jordond.filmstrip.color.signalFromNits
import dev.jordond.filmstrip.color.tenBitCodesFromSignal
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
import dev.jordond.filmstrip.ffmpeg.FfmpegConfig
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegExportEngine
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegRuntime
import dev.jordond.filmstrip.ffmpeg.internal.picture.FfmpegPictureDecoder
import dev.jordond.filmstrip.ffmpeg.internal.preview.FfmpegPreviewStream
import dev.jordond.filmstrip.ffmpeg.internal.preview.PreviewStreamResult
import dev.jordond.filmstrip.ffmpeg.internal.preview.openPreview
import dev.jordond.filmstrip.ffmpeg.support.ColorLanding
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.CODE_TOLERANCE
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.HEIC_FIXTURE
import dev.jordond.filmstrip.ffmpeg.support.OverlayConstants.CHANNEL_DRIFT
import dev.jordond.filmstrip.ffmpeg.support.OverlayConstants.OVERLAY_SIZE
import dev.jordond.filmstrip.ffmpeg.support.assertHdrLandings
import dev.jordond.filmstrip.ffmpeg.support.assertNear
import dev.jordond.filmstrip.ffmpeg.support.assertSdrLandings
import dev.jordond.filmstrip.ffmpeg.support.centreOf
import dev.jordond.filmstrip.ffmpeg.support.channelsOf
import dev.jordond.filmstrip.ffmpeg.support.drawnRect
import dev.jordond.filmstrip.ffmpeg.support.heicFixture
import dev.jordond.filmstrip.ffmpeg.support.isNear
import dev.jordond.filmstrip.ffmpeg.support.pngOf
import dev.jordond.filmstrip.ffmpeg.support.taggedHeif
import dev.jordond.filmstrip.ffmpeg.support.taggedJpeg
import dev.jordond.filmstrip.ffmpeg.support.taggedWebp
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.picture.pictureColor
import dev.jordond.filmstrip.test.IccPicture
import dev.jordond.filmstrip.test.IccPictures
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Overlay pictures tagged with an ICC profile, drawn by this ffmpeg in the color their profile gives them.
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

  // Linear sRGB's mid grey is the picture whose stored codes sit furthest from its color, so it is the one that
  // tells a tagged read from an untagged one on an SDR export.
  @Test
  fun `the linear sRGB picture's color sits too far from its stored codes for the drift to hide`() {
    val picture = IccPictures.LINEAR_SRGB
    val stored = channelsOf(picture.texel).toList()
    val color = srgbOf(picture).toList()

    withClue("$stored and $color") {
      stored.indices.all { abs(stored[it] - color[it]) > 2 * CHANNEL_DRIFT }.shouldBeTrue()
    }
  }

  @Test
  fun `every tagged picture lands on a kept PQ grade where its profile's light puts it`() =
    runTest(timeout = TIMEOUT) { fixture.assertHdrLandings(landings(), HdrTransfer.Pq, "filmstrip-icc") }

  @Test
  fun `every tagged picture lands on a kept HLG grade where its profile's light puts it`() =
    runTest(timeout = TIMEOUT) { fixture.assertHdrLandings(landings(), HdrTransfer.Hlg, "filmstrip-icc") }

  // The PQ export above reads Display P3 orange within the bound of its own light. Its light sits outside BT.709, far
  // enough from the color clipped to BT.709 first that the same bound tells the two apart.
  @Test
  fun `Display P3 orange on PQ lands outside the BT709 gamut by more than the bound`() {
    val picture = IccPictures.DISPLAY_P3
    val light = pictureLight(colorOf(picture), intArrayOf(picture.texel))
    val bt709 = authoredHdrNits(pictureSrgbCodes(colorOf(picture), intArrayOf(picture.texel)).single())
    val wide = hdrCodes(HdrTransfer.Pq, light.copyOf(3))
    val clipped = hdrCodes(HdrTransfer.Pq, bt709)

    withClue("$wide and $clipped") {
      wide.indices.any { abs(wide[it] - clipped[it]) > 2 * CODE_TOLERANCE }.shouldBeTrue()
    }
  }

  @Test
  fun `a tagged WebP only ffmpeg reads is drawn from the profile its decode carries`() {
    if (!fixture.available()) return
    val picture = IccPictures.LINEAR_SRGB
    val lossless = listOf("-c:v", "libwebp", "-lossless", "1")
    val webp = encoded(pngOf(picture.texel, OVERLAY_SIZE), "webp", lossless)
    assumeTrue("this ffmpeg has no libwebp encoder", webp != null)
    val tagged = taggedWebp(checkNotNull(webp).readBytes().also { webp.delete() }, picture.profile)
    withClue("the JDK reads WebP here, so this does not reach ffmpeg") {
      ImageIO.read(ByteArrayInputStream(tagged)).shouldBeNull()
    }
    val color = withClue("the WebP does not carry the profile") { pictureColor(tagged).shouldNotBeNull() }

    assertResolvesTo(ImageSource.ofBytes(tagged), color, picture.texel, "tagged WebP")
  }

  // ffmpeg's JPEG XL encoder writes the profile a PNG carries into the codestream, where only the decode finds it. The
  // encoder may store the profile as a color encoding of its own, so the color is what is held, not the bytes.
  @Test
  fun `a tagged JPEG XL is drawn from the profile its decode carries`() {
    if (!fixture.available()) return
    val picture = IccPictures.LINEAR_SRGB
    val jxl = encoded(picture.pngOf(OVERLAY_SIZE), "jxl", listOf("-c:v", "libjxl", "-distance", "0"))
    assumeTrue("this ffmpeg has no libjxl encoder", jxl != null)
    val bytes = checkNotNull(jxl).readBytes().also { jxl.delete() }
    withClue("a JPEG XL keeps its profile out of reach of the container read") { pictureColor(bytes).shouldBeNull() }
    assumeTrue(
      "this ffmpeg cannot decode JPEG XL",
      decoder.decode(ImageSource.ofBytes(bytes)) is OverlayPictureRead.Read,
    )

    assertResolvesTo(ImageSource.ofBytes(bytes), colorOf(picture), picture.texel, "tagged JPEG XL", tolerance = 1)
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
    withClue("no $HEIC_FIXTURE among the downloaded fixtures") { heic.isFile.shouldBeTrue() }
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
      val color =
        withClue("the ${picture.name} JPEG does not carry the profile") {
          pictureColor(bytes).shouldNotBeNull()
        }
      val read = ImageIO.read(ByteArrayInputStream(jpeg))
      val jdk = read.getRGB(read.width / 2, read.height / 2)
      val what = "${picture.name} JPEG"
      assertResolvesTo(tagged, color, jdk, "$what read by the JDK", reader = null, transfers = PQ_ONLY)
      val ffmpeg = decodedCentre(jpeg)
      assertResolvesTo(tagged, color, ffmpeg, "$what read by this ffmpeg", transfers = PQ_ONLY)
    }
  }

  @Test
  fun `an sRGB tagged picture reaches an SDR graph as the bytes it was given`() {
    if (!fixture.available()) return
    val spec = ImageOverlay(ImageSource.ofBytes(IccPictures.SRGB.pngOf(OVERLAY_SIZE)), Corner.TopStart)

    withClue("read by this ffmpeg") { auxImage(spec, null, decoder) shouldBeSameInstanceAs spec.image }
    withClue("read by the JDK") { auxImage(spec, null, null) shouldBeSameInstanceAs spec.image }
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
      val components = ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build()
      val engine = FfmpegExportEngine(components, FfmpegRuntime.of(FfmpegConfig()))
      val opened = engine.openPreview(composition, ExportSpec()).shouldBeInstanceOf<PreviewStreamResult.Opened>()
      val stream = opened.stream.shouldBeInstanceOf<FfmpegPreviewStream>()
      val frame =
        try {
          withClue("the preview delivered no frame") { stream.next().shouldNotBeNull() }
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
    withClue("the JDK reads the $what here, so this does not reach ffmpeg") {
      ImageIO.read(ByteArrayInputStream(tagged)).shouldBeNull()
    }
    val color = withClue("the $what does not carry the profile") { pictureColor(tagged).shouldNotBeNull() }

    assertResolvesTo(ImageSource.ofBytes(tagged), color, decodedCentre(heif), what)
  }

  // [image] resolved on an SDR grade and on [transfers] through [reader], held at the picture's middle against the
  // codes and the light [color] gives the [stored] texel.
  private fun assertResolvesTo(
    image: ImageSource,
    color: PictureColor?,
    stored: Int,
    what: String,
    reader: OverlayPictureDecoder? = decoder,
    transfers: List<HdrTransfer> = HdrTransfer.entries,
    tolerance: Int = 0,
  ) {
    val spec = ImageOverlay(image, Corner.TopStart)
    val srgb = channelsOf(pictureSrgbCodes(color, intArrayOf(stored)).single())
    val drawn =
      withClue("the $what reached the SDR graph untouched") {
        auxImage(spec, null, reader).shouldBeInstanceOf<ImageSource.Bytes>()
      }
    withClue("the $what reached the SDR graph still tagged") { pictureColor(drawn.bytes).shouldBeNull() }
    val read = channelsOf(ImageIO.read(ByteArrayInputStream(drawn.bytes)).centre())
    withClue("the $what on an SDR grade read $read, not $srgb") { read.isNear(srgb, tolerance).shouldBeTrue() }

    for (transfer in transfers) {
      val mapped = auxImage(spec, transfer, reader).shouldBeInstanceOf<ImageSource.Bytes>().bytes
      val graded = ImageIO.read(ByteArrayInputStream(mapped))
      val signal = transfer.signalFromNits(pictureLight(color, intArrayOf(stored)).copyOf(3))
      val expected = signal.map { (it.coerceIn(0f, 1f) * SIXTEEN_BIT_MAX).roundToInt() }
      val texel =
        graded.raster
          .getPixel(graded.width / 2, graded.height / 2, IntArray(RGBA))
          .copyOf(3)
          .toList()
      withClue("$transfer $what read $texel, expected $expected") {
        texel.indices.all { abs(texel[it] - expected[it]) <= tolerance * SIXTEEN_BIT_MAX / BYTE_MAX }.shouldBeTrue()
      }
    }
  }

  // The texel this ffmpeg decodes at the middle of [picture].
  private fun decodedCentre(picture: ByteArray): Int {
    val read = decoder.decode(ImageSource.ofBytes(picture))
    val decoded =
      withClue("this ffmpeg decoded nothing") {
        (read as? OverlayPictureRead.Read)?.value.shouldNotBeNull()
      }
    val argb =
      withClue("this ffmpeg decoded deeper than eight bits") {
        decoded.samples.shouldBeInstanceOf<StoredSamples.Argb>()
      }.argb
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
    ByteArrayOutputStream()
      .also {
        withClue("the JDK wrote no JPEG") {
          ImageIO.write(image, "jpg", it).shouldBeTrue()
        }
      }.toByteArray()

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
    val resolved = resolution.shouldBeInstanceOf<EffectResolution.Resolved>()
    return resolved.effect.fragment.auxInputs
      .first()
      .image
  }

  // Where every tagged picture lands, from the color its PNG describes.
  private fun landings(): List<ColorLanding> =
    IccPictures.ALL.map { picture ->
      val color = colorOf(picture)
      val texel = intArrayOf(picture.texel)
      val srgb = pictureSrgbCodes(color, texel).single()
      ColorLanding(picture.name, picture.pngOf(OVERLAY_SIZE), srgb, pictureLight(color, texel))
    }

  private fun srgbOf(picture: IccPicture): Triple<Int, Int, Int> =
    channelsOf(pictureSrgbCodes(colorOf(picture), intArrayOf(picture.texel)).single())

  // How [picture]'s PNG describes its color, read the way every backend reads it.
  private fun colorOf(picture: IccPicture): PictureColor? = pictureColor(picture.pngOf(OVERLAY_SIZE))

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

    // The AV1 encoders a build may have, whichever of them it was configured with.
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

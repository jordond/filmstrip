package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayOffset
import dev.jordond.filmstrip.effects.overlay.slideIn
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.picture.pictureColor
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Which color description a decoded overlay picture is drawn with, how an AVIF or HEIC reaches an SDR graph, how a
 * picture whose bytes this process cannot read is drawn, and where an animated overlay's size comes from when its
 * picture is decoded anyway.
 */
class OverlayPictureDecodeTest {
  // Only a JPEG XL keeps its color where the container reader cannot see it. Any other picture's decode infers a
  // description core reads differently, so it is not taken.
  @Test
  fun `a picture other than a JPEG XL lands untagged on an HDR grade whatever its decode carries`() {
    for (bytes in listOf(UNREADABLE, HEIF)) {
      val decoder = FakeDecoder(size = null, decoded = decodedOf(rgbOf(TEXELS), LINEAR_COLOR))
      for (transfer in HdrTransfer.entries) {
        val raster = mappedRaster(overlayOf(bytes), transfer, decoder)
        TEXELS.indices.forEach { x ->
          withClue("$transfer $x") { raster.getPixel(x, 0, IntArray(4)) shouldBe signalOf(null, TEXELS[x], transfer) }
        }
      }
    }
  }

  @Test
  fun `an untagged AVIF or HEIC reaches an SDR graph as an untagged PNG of its decode`() {
    val decoder = FakeDecoder(size = null, decoded = decodedOf(rgbOf(TEXELS), LINEAR_COLOR))

    val drawn = auxImage(overlayOf(HEIF), decoder).shouldBeInstanceOf<ImageSource.Bytes>()
    withClue("the PNG handed to the graph describes its color") { pictureColor(drawn.bytes).shouldBeNull() }
    argbOf(drawn.bytes) shouldBe TEXELS
    withClue("the AVIF or HEIC was not decoded once") { decoder.decodes.size shouldBe 1 }
  }

  // The graph repeats a still with -loop, which ffmpeg refuses for the demuxer that opens an AVIF or HEIC.
  @Test
  fun `an AVIF or HEIC without a decoder is refused on an SDR grade rather than handed to the graph`() {
    val resolution = resolverOf(null).resolve(overlayOf(HEIF), capabilities(), attributes(null))

    val refused = resolution.shouldBeInstanceOf<EffectResolution.Unsupported>()
    withClue(refused.message) {
      ("AVIF or HEIC" in refused.message && "without a picture decoder" in refused.message) shouldBe true
    }
  }

  @Test
  fun `a picture whose bytes this process cannot read is drawn untagged from a decode that describes no color`() {
    val decoder = FakeDecoder(size = null, decoded = decodedOf(rgbOf(TEXELS), null))
    val spec = ImageOverlay(UNREACHABLE, Corner.TopStart)

    val drawn = auxImage(spec, decoder).shouldBeInstanceOf<ImageSource.Bytes>()
    withClue("the PNG handed to the graph describes its color") { pictureColor(drawn.bytes).shouldBeNull() }
    argbOf(drawn.bytes) shouldBe TEXELS
    for (transfer in HdrTransfer.entries) {
      val raster = mappedRaster(spec, transfer, decoder)
      TEXELS.indices.forEach { x ->
        withClue("$transfer $x") { raster.getPixel(x, 0, IntArray(4)) shouldBe signalOf(null, TEXELS[x], transfer) }
      }
    }
    decoder.decodes.distinct() shouldBe listOf(spec.image)
  }

  // Its container's description is out of reach, and a decode infers descriptions core reads differently.
  @Test
  fun `a picture whose bytes this process cannot read is refused on every grade when its decode describes color`() {
    val decoder = FakeDecoder(size = null, decoded = decodedOf(rgbOf(TEXELS), LINEAR_COLOR))
    val spec = ImageOverlay(UNREACHABLE, Corner.TopStart)

    for (transfer in listOf(null) + HdrTransfer.entries) {
      val resolution = BuiltInEffectResolver(decoder).resolve(spec, capabilities(), attributes(transfer))
      val refused = withClue("$transfer") { resolution.shouldBeInstanceOf<EffectResolution.Unsupported>() }
      withClue("$transfer: ${refused.message}") { ("cannot read the picture's bytes" in refused.message) shouldBe true }
    }
  }

  @Test
  fun `an animation with no output rate is refused before its picture is decoded`() {
    for (transfer in listOf(null) + HdrTransfer.entries) {
      val decoder = FakeDecoder(size = SQUARE, decoded = decodedOf(rgbOf(TEXELS), null))
      val paced = attributes(transfer)
      val unpaced =
        Attributes(
          inputSize = paced.inputSize,
          outputSize = paced.outputSize,
          layoutSize = paced.layoutSize,
          colorSpace = paced.colorSpace,
          hdrTransfer = transfer,
          frameRate = null,
          span = paced.span,
        )

      val resolution = resolverOf(decoder).resolve(sliding(ImageSource.ofBytes(HEIF)), capabilities(), unpaced)
      withClue("$transfer") { resolution.shouldBeInstanceOf<EffectResolution.Unsupported>() }
      withClue("$transfer decoded") { decoder.decodes shouldBe emptyList() }
      withClue("$transfer measured") { decoder.measured shouldBe emptyList() }
    }
  }

  @Test
  fun `an untagged AVIF or HEIC the decoder refuses is refused on an SDR grade with the decoder's reason`() {
    val resolver = BuiltInEffectResolver(FakeDecoder(size = null, reason = REASON))

    val refused =
      resolver
        .resolve(
          overlayOf(HEIF),
          capabilities(),
          attributes(null),
        ).shouldBeInstanceOf<EffectResolution.Unsupported>()
    withClue(refused.message) { ("AVIF or HEIC" in refused.message && REASON in refused.message) shouldBe true }
  }

  @Test
  fun `an animated overlay decoded anyway is sized without a measure and one that is not by one measure`() {
    val picture = rgbOf(TEXELS)
    val grades = listOf(null to pngOf(picture)) + HdrTransfer.entries.map { it to pngOf(picture) } + (null to HEIF)
    for ((transfer, bytes) in grades) {
      val decoder = FakeDecoder(size = SQUARE, decoded = decodedOf(picture, null))
      val spec = sliding(ImageSource.ofBytes(bytes))

      val resolution = BuiltInEffectResolver(decoder).resolve(spec, capabilities(), attributes(transfer))
      withClue("$transfer") { resolution.shouldBeInstanceOf<EffectResolution.Resolved>() }
      val decodedHere = transfer != null || bytes === HEIF
      withClue("$transfer measured") { decoder.measured.size shouldBe (if (decodedHere) 0 else 1) }
      withClue("$transfer decodes") { decoder.decodes.size shouldBe (if (decodedHere) 1 else 0) }
    }
  }

  private fun sliding(image: ImageSource): ImageOverlay =
    ImageOverlay(image, Corner.TopStart, animation = slideIn(OverlayOffset(SLIDE, 0f), 1.seconds))

  private companion object {
    const val SLIDE = 0.25f
    const val REASON = "Invalid data found when processing input"
    val UNREADABLE = "not a picture the JDK reads".encodeToByteArray()

    // A URL ffmpeg would open and this process does not read.
    val UNREACHABLE = ImageSource.Uri("https://example.invalid/overlay.png")
    val SQUARE = Size(64, 64)

    // The opening of an AVIF's ftyp box and nothing after it, which no reader here decodes.
    val HEIF = byteArrayOf(0, 0, 0, 0x14) + "ftypavif".encodeToByteArray() + ByteArray(8)
  }
}

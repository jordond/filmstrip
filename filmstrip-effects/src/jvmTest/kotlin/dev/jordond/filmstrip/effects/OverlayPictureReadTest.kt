package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.color.pictureSrgbCodes
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
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * How the filter graph catalogue reads an overlay picture, through the JDK and through a backend's
 * [dev.jordond.filmstrip.effects.overlay.OverlayPictureDecoder].
 */
class OverlayPictureReadTest {
  @Test
  fun `an untagged PNG reaches an SDR graph as the bytes it was given`() {
    val spec = overlayOf(pngOf(rgbOf(TEXELS)))

    auxImage(spec, null) shouldBeSameInstanceAs spec.image
  }

  // The linear profile shares sRGB's primaries and drops its curve, so every mid tone moves when the profile is
  // honoured and stays put when it is not.
  @Test
  fun `a tagged PNG reaches an SDR graph as an untagged PNG of the codes its profile gives`() {
    val png = tagged(pngOf(rgbOf(TEXELS)), LINEAR)
    val expected = pictureSrgbCodes(pictureColor(png), TEXELS)

    val drawn = auxImage(overlayOf(png), null).shouldBeInstanceOf<ImageSource.Bytes>()
    withClue("the PNG handed to the graph still describes its color") { pictureColor(drawn.bytes).shouldBeNull() }
    argbOf(drawn.bytes) shouldBe expected
    withClue("the linear profile moves no texel, so this cannot tell a tagged read from an untagged one") {
      TEXELS.indices.any { channelGap(TEXELS[it], expected[it]) > MID_TONE_MOVE } shouldBe true
    }
  }

  @Test
  fun `a tagged PNG on an HDR grade lands where its profile's light puts it`() {
    val png = tagged(pngOf(rgbOf(TEXELS)), LINEAR)
    for (transfer in HdrTransfer.entries) {
      val raster = mappedRaster(png, transfer)
      TEXELS.indices.forEach { x ->
        withClue("$transfer $x") {
          raster.getPixel(x, 0, IntArray(4)) shouldBe signalOf(LINEAR_COLOR, TEXELS[x], transfer)
        }
      }
    }
  }

  // A JPEG XL keeps its color inside its codestream, so the decoder is the only one that can say what it carries.
  @Test
  fun `a JPEG XL is drawn on an SDR graph from the color its decode carries`() {
    val decoder = FakeDecoder(size = null, decoded = decodedOf(rgbOf(TEXELS), LINEAR_COLOR))

    val drawn = auxImage(overlayOf(JPEG_XL), decoder).shouldBeInstanceOf<ImageSource.Bytes>()
    argbOf(drawn.bytes) shouldBe pictureSrgbCodes(LINEAR_COLOR, TEXELS)
  }

  @Test
  fun `a JPEG XL whose decode carries no color reaches an SDR graph as the bytes it was given`() {
    val spec = overlayOf(JPEG_XL)
    val decoder = FakeDecoder(size = null, decoded = decodedOf(rgbOf(TEXELS), null))

    auxImage(spec, decoder) shouldBeSameInstanceAs spec.image
  }

  @Test
  fun `a JPEG XL lands on an HDR grade from the color its decode carries`() {
    val spec = overlayOf(JPEG_XL)
    val decoder = FakeDecoder(size = null, decoded = decodedOf(rgbOf(TEXELS), LINEAR_COLOR))
    for (transfer in HdrTransfer.entries) {
      val raster = mappedRaster(spec, transfer, decoder)
      TEXELS.indices.forEach { x ->
        withClue("$transfer $x") {
          raster.getPixel(x, 0, IntArray(4)) shouldBe signalOf(LINEAR_COLOR, TEXELS[x], transfer)
        }
      }
    }
  }

  // The decode is the toolchain's own, turned the way it turns the picture on an SDR grade, so it wins over the JDK's
  // read of the same bytes, and its color description over the decode's when the container carries one.
  @Test
  fun `every picture on an HDR grade is decoded by the decoder even when the JDK reads it`() {
    val turned = rgbOf(DECODED_TEXELS)
    for (png in listOf(pngOf(rgbOf(TEXELS)), tagged(pngOf(rgbOf(TEXELS)), LINEAR))) {
      val decoder = FakeDecoder(size = null, decoded = decodedOf(turned, null))
      val color = pictureColor(png)
      for (transfer in HdrTransfer.entries) {
        val raster = mappedRaster(overlayOf(png), transfer, decoder)
        withClue("$transfer read at the JDK's size") {
          (raster.width to raster.height) shouldBe (DECODED_TEXELS.size to 1)
        }
        DECODED_TEXELS.indices.forEach { x ->
          withClue("$transfer $x") {
            raster.getPixel(x, 0, IntArray(4)) shouldBe signalOf(color, DECODED_TEXELS[x], transfer)
          }
        }
      }
      withClue("the decoder was not asked for every grade") { decoder.decodes.size shouldBe HdrTransfer.entries.size }
    }
  }

  @Test
  fun `a tagged picture is drawn on an SDR graph from the decoder's samples and its container's color`() {
    val png = tagged(pngOf(rgbOf(TEXELS)), LINEAR)
    val decoder = FakeDecoder(size = null, decoded = decodedOf(rgbOf(DECODED_TEXELS), null))

    val drawn = auxImage(overlayOf(png), decoder).shouldBeInstanceOf<ImageSource.Bytes>()
    argbOf(drawn.bytes) shouldBe pictureSrgbCodes(pictureColor(png), DECODED_TEXELS)
  }

  @Test
  fun `an untagged picture is never decoded on an SDR grade`() {
    val decoder = FakeDecoder(size = null, decoded = decodedOf(rgbOf(DECODED_TEXELS), LINEAR_COLOR))
    val spec = overlayOf(pngOf(rgbOf(TEXELS)))

    auxImage(spec, decoder) shouldBeSameInstanceAs spec.image
    withClue("the decoder decoded an untagged picture on an SDR grade") { decoder.decodes.isEmpty() shouldBe true }
  }

  @Test
  fun `an animated overlay is sized by the decoder whenever there is one`() {
    for (bytes in listOf(UNREADABLE, pngOf(BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)))) {
      val decoder = FakeDecoder(size = SQUARE)
      val spec = sliding(ImageSource.ofBytes(bytes))

      val resolution = BuiltInEffectResolver(decoder).resolve(spec, capabilities(), attributes(null))
      resolution.shouldBeInstanceOf<EffectResolution.Resolved>()
      decoder.measured shouldBe listOf(spec.image)
    }
  }

  @Test
  fun `an animated overlay the JDK reads is sized from its header without a decoder`() {
    val spec = sliding(ImageSource.ofBytes(pngOf(BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB))))

    BuiltInEffectResolver()
      .resolve(
        spec,
        capabilities(),
        attributes(null),
      ).shouldBeInstanceOf<EffectResolution.Resolved>()
  }

  @Test
  fun `an animated overlay the decoder cannot read is refused with the decoder's reason`() {
    val spec = sliding(ImageSource.ofBytes(UNREADABLE))
    val resolver = BuiltInEffectResolver(FakeDecoder(size = null, reason = REASON))

    val refused =
      resolver
        .resolve(
          spec,
          capabilities(),
          attributes(null),
        ).shouldBeInstanceOf<EffectResolution.Unsupported>()
    withClue(refused.message) { ("header" in refused.message && REASON in refused.message) shouldBe true }
  }

  @Test
  fun `an overlay on an HDR grade the decoder cannot decode is refused with the decoder's reason`() {
    for (bytes in listOf(UNREADABLE, pngOf(rgbOf(TEXELS)))) {
      val resolver = BuiltInEffectResolver(FakeDecoder(size = null, reason = REASON))

      val resolution = resolver.resolve(overlayOf(bytes), capabilities(), attributes(HdrTransfer.Pq))
      val refused = resolution.shouldBeInstanceOf<EffectResolution.Unsupported>()
      withClue(refused.message) { (REASON in refused.message) shouldBe true }
    }
  }

  @Test
  fun `a picture whose profile does not parse reaches an SDR graph as the bytes it was given`() {
    val broken = LINEAR.copyOf().also { it.fill(0, TAG_COUNT, TAG_COUNT + 4) }
    val png = tagged(pngOf(rgbOf(TEXELS)), broken)

    val spec = overlayOf(png)
    auxImage(spec, null) shouldBeSameInstanceAs spec.image
  }

  @Test
  fun `a tagged picture the decoder cannot decode is refused on an SDR grade with the decoder's reason`() {
    val resolver = BuiltInEffectResolver(FakeDecoder(size = null, reason = REASON))

    val refused =
      resolver
        .resolve(
          overlayOf(TAGGED_WEBP),
          capabilities(),
          attributes(null),
        ).shouldBeInstanceOf<EffectResolution.Unsupported>()
    withClue(refused.message) { ("describes its color" in refused.message && REASON in refused.message) shouldBe true }
  }

  @Test
  fun `a tagged picture the JDK cannot read is refused on an SDR grade without a decoder`() {
    val refused =
      BuiltInEffectResolver()
        .resolve(
          overlayOf(TAGGED_WEBP),
          capabilities(),
          attributes(null),
        ).shouldBeInstanceOf<EffectResolution.Unsupported>()
    withClue(refused.message) { ("without a picture decoder" in refused.message) shouldBe true }
  }

  @Test
  fun `a JPEG XL reaches an SDR graph as the bytes it was given without a decoder`() {
    val spec = overlayOf(JPEG_XL)

    auxImage(spec, null) shouldBeSameInstanceAs spec.image
  }

  @Test
  fun `a JPEG XL the decoder refuses is refused on an SDR grade with the decoder's reason`() {
    val resolver = BuiltInEffectResolver(FakeDecoder(size = null, reason = REASON))

    val refused =
      resolver
        .resolve(
          overlayOf(JPEG_XL),
          capabilities(),
          attributes(null),
        ).shouldBeInstanceOf<EffectResolution.Unsupported>()
    withClue(refused.message) { ("JPEG XL" in refused.message && REASON in refused.message) shouldBe true }
  }

  private fun sliding(image: ImageSource): ImageOverlay =
    ImageOverlay(image, Corner.TopStart, animation = slideIn(OverlayOffset(SLIDE, 0f), 1.seconds))

  private companion object {
    const val SLIDE = 0.25f
    const val REASON = "Invalid data found when processing input"
    val UNREADABLE = "not a picture the JDK reads".encodeToByteArray()
    val SQUARE = Size(64, 64)

    // Texels a decoder hands back in place of the JDK's, each a mid tone the linear profile moves, and fewer of them
    // than the JDK reads, the way a turned picture's row differs from its unturned one.
    val DECODED_TEXELS = intArrayOf(0xFF808040.toInt(), 0xFF4080C0.toInt(), 0xFFC06020.toInt())

    // Where the ICC header counts the tags that follow it.
    const val TAG_COUNT = 128

    // A JPEG XL codestream's signature and nothing after it, which no reader here decodes.
    val JPEG_XL = byteArrayOf(0xFF.toByte(), 0x0A, 0, 0)

    // A WebP header carrying the linear profile and no picture, which neither reader here decodes.
    val TAGGED_WEBP: ByteArray = taggedWebpHeader(LINEAR)
  }
}

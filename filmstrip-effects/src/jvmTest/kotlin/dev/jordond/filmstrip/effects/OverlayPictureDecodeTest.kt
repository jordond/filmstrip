package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayOffset
import dev.jordond.filmstrip.effects.overlay.slideIn
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.pictureColour
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Which colour description a decoded overlay picture is drawn with, how an AVIF or HEIC reaches an SDR graph, how a
 * picture whose bytes this process cannot read is drawn, and where an animated overlay's size comes from when its
 * picture is decoded anyway.
 */
class OverlayPictureDecodeTest {
  // Only a JPEG XL keeps its colour where the container reader cannot see it. Any other picture's decode infers a
  // description core reads differently, so it is not taken.
  @Test
  fun `a picture other than a JPEG XL lands untagged on an HDR grade whatever its decode carries`() {
    for (bytes in listOf(UNREADABLE, HEIF)) {
      val decoder = FakeDecoder(size = null, decoded = decodedOf(rgbOf(TEXELS), LINEAR_COLOUR))
      for (transfer in HdrTransfer.entries) {
        val raster = mappedRaster(overlayOf(bytes), transfer, decoder)
        TEXELS.indices.forEach { x ->
          assertContentEquals(signalOf(null, TEXELS[x], transfer), raster.getPixel(x, 0, IntArray(4)), "$transfer $x")
        }
      }
    }
  }

  @Test
  fun `an untagged AVIF or HEIC reaches an SDR graph as an untagged PNG of its decode`() {
    val decoder = FakeDecoder(size = null, decoded = decodedOf(rgbOf(TEXELS), LINEAR_COLOUR))

    val drawn = assertIs<ImageSource.Bytes>(auxImage(overlayOf(HEIF), decoder))
    assertNull(pictureColour(drawn.bytes), "the PNG handed to the graph describes its colour")
    assertContentEquals(TEXELS, argbOf(drawn.bytes))
    assertEquals(1, decoder.decodes.size, "the AVIF or HEIC was not decoded once")
  }

  // The graph repeats a still with -loop, which ffmpeg refuses for the demuxer that opens an AVIF or HEIC.
  @Test
  fun `an AVIF or HEIC without a decoder is refused on an SDR grade rather than handed to the graph`() {
    val resolution = resolverOf(null).resolve(overlayOf(HEIF), capabilities(), attributes(null))

    val refused = assertIs<EffectResolution.Unsupported>(resolution)
    assertTrue("AVIF or HEIC" in refused.message && "without a picture decoder" in refused.message, refused.message)
  }

  @Test
  fun `a picture whose bytes this process cannot read is drawn untagged from a decode that describes no colour`() {
    val decoder = FakeDecoder(size = null, decoded = decodedOf(rgbOf(TEXELS), null))
    val spec = ImageOverlay(UNREACHABLE, Corner.TopStart)

    val drawn = assertIs<ImageSource.Bytes>(auxImage(spec, decoder))
    assertNull(pictureColour(drawn.bytes), "the PNG handed to the graph describes its colour")
    assertContentEquals(TEXELS, argbOf(drawn.bytes))
    for (transfer in HdrTransfer.entries) {
      val raster = mappedRaster(spec, transfer, decoder)
      TEXELS.indices.forEach { x ->
        assertContentEquals(signalOf(null, TEXELS[x], transfer), raster.getPixel(x, 0, IntArray(4)), "$transfer $x")
      }
    }
    assertEquals(listOf(spec.image), decoder.decodes.distinct())
  }

  // The graph opens a path itself and fails on one that is missing, so planning one reads nothing.
  @Test
  fun `a path this process cannot read reaches an SDR graph as it is without a decode`() {
    val decoder = FakeDecoder(size = null, decoded = decodedOf(rgbOf(TEXELS), LINEAR_COLOUR))
    val spec = ImageOverlay(ImageSource.of("/no/such/overlay.png"), Corner.TopStart)

    assertEquals(spec.image, auxImage(spec, decoder))
    assertEquals(emptyList(), decoder.decodes)
  }

  // Its container's description is out of reach, and a decode infers descriptions core reads differently.
  @Test
  fun `a picture whose bytes this process cannot read is refused on every grade when its decode describes colour`() {
    val decoder = FakeDecoder(size = null, decoded = decodedOf(rgbOf(TEXELS), LINEAR_COLOUR))
    val spec = ImageOverlay(UNREACHABLE, Corner.TopStart)

    for (transfer in listOf(null) + HdrTransfer.entries) {
      val resolution = BuiltInEffectResolver(decoder).resolve(spec, capabilities(), attributes(transfer))
      val refused = assertIs<EffectResolution.Unsupported>(resolution, "$transfer")
      assertTrue("cannot read the picture's bytes" in refused.message, "$transfer: ${refused.message}")
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
      assertIs<EffectResolution.Unsupported>(resolution, "$transfer")
      assertEquals(emptyList(), decoder.decodes, "$transfer decoded")
      assertEquals(emptyList(), decoder.measured, "$transfer measured")
    }
  }

  @Test
  fun `an untagged AVIF or HEIC the decoder refuses is refused on an SDR grade with the decoder's reason`() {
    val resolver = BuiltInEffectResolver(FakeDecoder(size = null, reason = REASON))

    val refused =
      assertIs<EffectResolution.Unsupported>(resolver.resolve(overlayOf(HEIF), capabilities(), attributes(null)))
    assertTrue("AVIF or HEIC" in refused.message && REASON in refused.message, refused.message)
  }

  @Test
  fun `an animated overlay decoded anyway is sized from that decode without a measure`() {
    val picture = rgbOf(TEXELS)
    val grades = listOf(null to pngOf(picture)) + HdrTransfer.entries.map { it to pngOf(picture) } + (null to HEIF)
    for ((transfer, bytes) in grades) {
      val decoder = FakeDecoder(size = SQUARE, decoded = decodedOf(picture, null))
      val spec = sliding(ImageSource.ofBytes(bytes))

      val resolution = BuiltInEffectResolver(decoder).resolve(spec, capabilities(), attributes(transfer))
      assertIs<EffectResolution.Resolved>(resolution, "$transfer")
      val decodedHere = transfer != null || bytes === HEIF
      assertEquals(if (decodedHere) emptyList() else listOf(spec.image), decoder.measured, "$transfer measured")
      assertEquals(if (decodedHere) 1 else 0, decoder.decodes.size, "$transfer decodes")
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

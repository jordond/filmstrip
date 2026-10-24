package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.effect.Degradation
import dev.jordond.filmstrip.effect.DegradationReason
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effects.overlay.DecodedPicture
import dev.jordond.filmstrip.effects.overlay.OverlayPictureDecode
import dev.jordond.filmstrip.effects.overlay.OverlayPictureDecoder
import dev.jordond.filmstrip.effects.overlay.OverlayPictureRead
import dev.jordond.filmstrip.effects.overlay.decodedPng
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * An overlay whose decoder reads its picture short of what the file stores resolves degraded by each limit the decode
 * names, in order, on an SDR grade and on an HDR one.
 */
class OverlayPictureLimitsTest {
  @Test
  fun `a tagged picture decoded with limits resolves degraded by them on an SDR grade`() {
    val spec = overlayOf(tagged(pngOf(rgbOf(TEXELS)), LINEAR))
    val resolution = resolverOf(LimitedDecoder).resolve(spec, capabilities(), attributes(null))
    assertEquals(LIMITS, assertIs<EffectResolution.Degraded>(resolution).causes)
  }

  @Test
  fun `a picture decoded with limits resolves degraded by them on an HDR grade`() {
    val spec = overlayOf(pngOf(rgbOf(TEXELS)))
    val resolution = resolverOf(LimitedDecoder).resolve(spec, capabilities(), attributes(HdrTransfer.Pq))
    assertEquals(LIMITS, assertIs<EffectResolution.Degraded>(resolution).causes)
  }

  @Test
  fun `a picture decoded with no limits resolves exactly`() {
    val spec = overlayOf(pngOf(rgbOf(TEXELS)))
    val resolution = resolverOf(ExactDecoder()).resolve(spec, capabilities(), attributes(HdrTransfer.Pq))
    assertIs<EffectResolution.Resolved>(resolution)
  }

  private open class ExactDecoder(
    private val limits: List<Degradation> = emptyList(),
  ) : OverlayPictureDecoder {
    override fun decode(image: ImageSource): OverlayPictureDecode<DecodedPicture> {
      val png = checkNotNull(decodedPng((image as ImageSource.Bytes).bytes))
      return OverlayPictureRead.Read(DecodedPicture(png.size, png.samples, png.colour, limits))
    }

    override fun measure(image: ImageSource): OverlayPictureRead<Size> = OverlayPictureRead.Read(Size(8, 8))
  }

  private object LimitedDecoder : ExactDecoder(LIMITS)

  private companion object {
    val LIMITS =
      listOf(
        Degradation(DegradationReason.AlphaDiscarded, "The decoder drew the picture opaque."),
        Degradation(DegradationReason.ColorSpaceConverted, "The decoder landed the picture through another matrix."),
      )
  }
}

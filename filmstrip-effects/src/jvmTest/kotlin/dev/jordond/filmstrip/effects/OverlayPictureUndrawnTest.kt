package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effects.overlay.DecodedPicture
import dev.jordond.filmstrip.effects.overlay.OverlayPictureDecode
import dev.jordond.filmstrip.effects.overlay.OverlayPictureDecoder
import dev.jordond.filmstrip.effects.overlay.OverlayPictureRead
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * An overlay whose decoder does not draw the colour the picture describes is refused with the decoder's reason as it
 * is, on an SDR grade and on an HDR one, rather than as a picture the decoder could not decode.
 */
class OverlayPictureUndrawnTest {
  @Test
  fun `a tagged picture the decoder does not draw is refused with its reason alone on an SDR grade`() {
    val spec = overlayOf(tagged(pngOf(rgbOf(TEXELS)), LINEAR))
    val resolution = resolverOf(UndrawnDecoder).resolve(spec, capabilities(), attributes(null))
    assertEquals(REASON, assertIs<EffectResolution.Unsupported>(resolution).message)
  }

  @Test
  fun `a picture the decoder does not draw is refused with its reason alone on an HDR grade`() {
    val spec = overlayOf(pngOf(rgbOf(TEXELS)))
    val resolution = resolverOf(UndrawnDecoder).resolve(spec, capabilities(), attributes(HdrTransfer.Pq))
    assertEquals(REASON, assertIs<EffectResolution.Unsupported>(resolution).message)
  }

  private object UndrawnDecoder : OverlayPictureDecoder {
    override fun decode(image: ImageSource): OverlayPictureDecode<DecodedPicture> = OverlayPictureDecode.Undrawn(REASON)

    override fun measure(image: ImageSource): OverlayPictureRead<Size> = OverlayPictureRead.Read(Size(8, 8))
  }

  private companion object {
    const val REASON = "The picture names a matrix nothing here reads."
  }
}

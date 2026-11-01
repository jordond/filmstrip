package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.overlay.OverlayFixtures.CAPABILITIES
import dev.jordond.filmstrip.effects.overlay.OverlayFixtures.SPAN
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.style.TextStyle
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * What Apple's resolver reports for a text overlay it draws nothing for, by the reason [rasterizeText] gives.
 */
@OptIn(ExperimentalForeignApi::class)
class TextOverlayRefusalTest {
  @Test
  fun `text past the picture ceiling is refused with the reason its raster gives`() {
    val size = assertNotNull(rasterSize(TALL_TEXT, TALL_STYLE), "the tall text measured nothing")
    val band = drawnBandBytes(size.width.toLong(), size.height.toLong())
    assertFalse(
      textRasterFits(size.width.toLong(), size.height.toLong(), TEXT_TEXEL_BYTES, band),
      "a ${size.width} by ${size.height} raster fits under the ceiling, so it proves nothing",
    )

    val resolved = BuiltInEffectResolver().resolve(TextOverlay(TALL_TEXT, TALL_STYLE), CAPABILITIES, ATTRIBUTES)

    val refused = assertIs<EffectResolution.Unsupported>(resolved, "the tall text resolved as $resolved")
    assertEquals(textTooLargeToDraw(size.width.toLong(), size.height.toLong(), TEXT_TEXEL_BYTES), refused.message)
  }

  @Test
  fun `text that leaves nothing to draw is refused saying so`() {
    val resolved = BuiltInEffectResolver().resolve(TextOverlay(""), CAPABILITIES, ATTRIBUTES)

    val refused = assertIs<EffectResolution.Unsupported>(resolved, "empty text resolved as $resolved")
    assertEquals("The text and its style leave nothing to draw.", refused.message)
  }

  // The size the raster would be drawn at, read where its bitmap would be made and refused there, so nothing is held.
  private fun rasterSize(
    text: String,
    style: TextStyle,
  ): Size? {
    var size: Size? = null
    val measured = { width: Int, height: Int ->
      size = Size(width, height)
      null
    }
    assertIs<TextRaster.Refused>(rasterizeText(text, style, FRAME, ceiling = Long.MAX_VALUE, bitmapOf = measured))
    return size
  }

  private companion object {
    val FRAME = Size(1280, 720)

    val ATTRIBUTES =
      Attributes(
        inputSize = FRAME,
        outputSize = FRAME,
        layoutSize = FRAME,
        colorSpace = ColorSpace.Bt709,
        hdrTransfer = null,
        frameRate = 30f,
        span = SPAN,
      )

    // Half the frame tall a capital and wrapped narrow, a hundred words stand one to a line many frames tall.
    val TALL_STYLE = TextStyle(fontSize = 0.5f, maxWidth = 0.1f)
    val TALL_TEXT = List(100) { "word" }.joinToString(" ")
  }
}

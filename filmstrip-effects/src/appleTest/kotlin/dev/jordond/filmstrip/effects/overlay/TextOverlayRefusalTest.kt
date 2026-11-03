package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.overlay.OverlayFixtures.CAPABILITIES
import dev.jordond.filmstrip.effects.overlay.OverlayFixtures.SPAN
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.style.TextStyle
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test

/**
 * What Apple's resolver reports for a text overlay it draws nothing for, by the reason [rasterizeText] gives.
 */
@OptIn(ExperimentalForeignApi::class)
class TextOverlayRefusalTest {
  @Test
  fun `text past the picture ceiling is refused with the reason its raster gives`() {
    val size = withClue("the tall text measured nothing") { rasterSize(TALL_TEXT, TALL_STYLE).shouldNotBeNull() }
    val band = drawnBandBytes(size.width.toLong(), size.height.toLong())
    withClue("a ${size.width} by ${size.height} raster fits under the ceiling, so it proves nothing") {
      textRasterFits(size.width.toLong(), size.height.toLong(), TEXT_TEXEL_BYTES, band) shouldBe false
    }

    val resolved = BuiltInEffectResolver().resolve(TextOverlay(TALL_TEXT, TALL_STYLE), CAPABILITIES, ATTRIBUTES)

    val refused =
      withClue("the tall text resolved as $resolved") {
        resolved.shouldBeInstanceOf<EffectResolution.Unsupported>()
      }
    refused.message shouldBe textTooLargeToDraw(size.width.toLong(), size.height.toLong(), TEXT_TEXEL_BYTES)
  }

  @Test
  fun `text that leaves nothing to draw is refused saying so`() {
    val resolved = BuiltInEffectResolver().resolve(TextOverlay(""), CAPABILITIES, ATTRIBUTES)

    val refused =
      withClue("empty text resolved as $resolved") {
        resolved.shouldBeInstanceOf<EffectResolution.Unsupported>()
      }
    refused.message shouldBe "The text and its style leave nothing to draw."
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
    rasterizeText(
      text,
      style,
      FRAME,
      ceiling = Long.MAX_VALUE,
      bitmapOf = measured,
    ).shouldBeInstanceOf<TextRaster.Refused>()
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

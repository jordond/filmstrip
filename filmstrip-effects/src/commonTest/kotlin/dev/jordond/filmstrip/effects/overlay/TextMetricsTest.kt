package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.style.TextStyle
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The text metrics every platform's rasteriser reads, pinned at a style off the ends of both ranges so a
 * formula that only agrees at zero or one still fails.
 */
class TextMetricsTest {
  @Test
  fun capHeightIsTheFontSizeAsAFractionOfFrameHeight() {
    assertEquals(PLATED.fontSize * FRAME.height, PLATED.capHeightOn(FRAME))
  }

  @Test
  fun wrapWidthIsTheMaxWidthAsAFractionOfFrameWidthRounded() {
    assertEquals((PLATED.maxWidth * FRAME.width).roundToInt(), PLATED.wrapWidthOn(FRAME))
  }

  @Test
  fun aWrapWidthNeverFallsBelowOnePixel() {
    assertEquals(1, TextStyle(maxWidth = 0f).wrapWidthOn(FRAME))
  }

  @Test
  fun platePaddingIsMeasuredOffTheCapHeight() {
    assertEquals((PLATED.capHeightOn(FRAME) * 0.3f).roundToInt(), PLATED.platePaddingOn(FRAME))
  }

  @Test
  fun textWithoutAPlateHasNoPadding() {
    val unplated = TextStyle(fontSize = PLATED.fontSize, maxWidth = PLATED.maxWidth)

    assertEquals(0, unplated.platePaddingOn(FRAME))
  }

  private companion object {
    val FRAME = Size(1920, 1080)
    val PLATED = TextStyle(fontSize = 0.06f, backgroundColor = 0x80000000.toInt(), maxWidth = 0.55f)
  }
}

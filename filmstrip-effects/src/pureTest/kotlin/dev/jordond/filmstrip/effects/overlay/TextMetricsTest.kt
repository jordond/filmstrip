package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.style.TextStyle
import io.kotest.matchers.shouldBe
import kotlin.math.roundToInt
import kotlin.test.Test

/**
 * The text metrics every platform's rasteriser reads, pinned at a style off the ends of both ranges so a
 * formula that only agrees at zero or one still fails.
 */
class TextMetricsTest {
  @Test
  fun capHeightIsTheFontSizeAsAFractionOfFrameHeight() {
    PLATED.capHeightOn(FRAME) shouldBe PLATED.fontSize * FRAME.height
  }

  @Test
  fun wrapWidthIsTheMaxWidthAsAFractionOfFrameWidthRounded() {
    PLATED.wrapWidthOn(FRAME) shouldBe (PLATED.maxWidth * FRAME.width).roundToInt()
  }

  @Test
  fun aWrapWidthNeverFallsBelowOnePixel() {
    TextStyle(maxWidth = 0f).wrapWidthOn(FRAME) shouldBe 1
  }

  @Test
  fun platePaddingIsMeasuredOffTheCapHeight() {
    PLATED.platePaddingOn(FRAME) shouldBe (PLATED.capHeightOn(FRAME) * 0.3f).roundToInt()
  }

  @Test
  fun textWithoutAPlateHasNoPadding() {
    val unplated = TextStyle(fontSize = PLATED.fontSize, maxWidth = PLATED.maxWidth)

    unplated.platePaddingOn(FRAME) shouldBe 0
  }

  private companion object {
    val FRAME = Size(1920, 1080)
    val PLATED = TextStyle(fontSize = 0.06f, backgroundColor = 0x80000000.toInt(), maxWidth = 0.55f)
  }
}

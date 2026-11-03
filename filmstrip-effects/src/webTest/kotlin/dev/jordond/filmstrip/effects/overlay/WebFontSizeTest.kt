package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.style.FontWeight
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * How a font size is written into the CSS shorthand, which must never carry an exponent on js or wasm.
 */
class WebFontSizeTest {
  @Test
  fun aSizeIsWrittenAsAPlainDecimalThatReadsBackAsItself() {
    for (size in SIZES) {
      val written = plainDecimalOf(size)

      withClue("$size was written $written") { written.any { it == 'e' || it == 'E' } shouldBe false }
      withClue("$size was written $written") { written.toFloat() shouldBe size }
    }
  }

  // js writes the size bare and wasm with a trailing ".0", and either reads as the same size.
  @Test
  fun aHugeFontIsWrittenWithoutAnExponent() {
    val font = fontOf(FontWeight.Bold, HUGE, "serif")
    val size = font.removePrefix("700 ").removeSuffix("px serif")

    font shouldBe "700 ${size}px serif"
    withClue("the size was written $size") { size.toFloat() shouldBe HUGE }
    withClue("the size was written $size") { size.all { it.isDigit() || it == '.' } shouldBe true }
  }

  private companion object {
    const val HUGE = 1e7f

    // Sizes Kotlin writes in exponent form on js or wasm, either side of one, and ordinary ones.
    val SIZES = listOf(HUGE, 2.5e8f, 1.5e-7f, 3e-9f, -4.25e9f, 37.5f, 0.125f, 0f)
  }
}

package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.style.FontWeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * How a font size is written into the CSS shorthand, which must never carry an exponent on js or wasm.
 */
class WebFontSizeTest {
  @Test
  fun aSizeIsWrittenAsAPlainDecimalThatReadsBackAsItself() {
    for (size in SIZES) {
      val written = plainDecimalOf(size)

      assertFalse(written.any { it == 'e' || it == 'E' }, "$size was written $written")
      assertEquals(size, written.toFloat(), "$size was written $written")
    }
  }

  // js writes the size bare and wasm with a trailing ".0", and either reads as the same size.
  @Test
  fun aHugeFontIsWrittenWithoutAnExponent() {
    val font = fontOf(FontWeight.Bold, HUGE, "serif")
    val size = font.removePrefix("700 ").removeSuffix("px serif")

    assertEquals("700 ${size}px serif", font)
    assertEquals(HUGE, size.toFloat(), "the size was written $size")
    assertTrue(size.all { it.isDigit() || it == '.' }, "the size was written $size")
  }

  private companion object {
    const val HUGE = 1e7f

    // Sizes Kotlin writes in exponent form on js or wasm, either side of one, and ordinary ones.
    val SIZES = listOf(HUGE, 2.5e8f, 1.5e-7f, 3e-9f, -4.25e9f, 37.5f, 0.125f, 0f)
  }
}

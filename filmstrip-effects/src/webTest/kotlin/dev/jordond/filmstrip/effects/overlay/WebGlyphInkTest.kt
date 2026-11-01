package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.test.GLYPH_INKS
import dev.jordond.filmstrip.test.sampleOfCode
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * How far the browser's ordinary glyph texels sit from the text colour, against the reach [platedSamples] reads ink
 * by.
 *
 * A canvas stores a glyph premultiplied on eight bits and reads it back divided out, and Chrome's premultiply drifts
 * each channel on its own by a few premultiplied steps. Every texel has to sit within [glyphInkReach], or an ordinary
 * texel would keep its eight-bit colour rather than the text's.
 */
class WebGlyphInkTest {
  @Test
  fun ordinaryGlyphsSitWithinTheInkReachOfTheirColour() {
    var outside = 0
    var worst = 0.0
    var worstAt = "nothing"
    for (ink in GLYPH_INKS) {
      for (font in FONTS) {
        val glyphs = coverageOf(ink, font)
        for (texel in glyphs.indices step CHANNELS) {
          val alpha = glyphs.codeAt(texel + ALPHA)
          if (alpha == 0) continue
          for (channel in 0 until RGB) {
            val apart = abs(glyphs.codeAt(texel + channel) - ((ink ushr RGB_SHIFTS[channel]) and BYTE))
            if (apart > glyphInkReach(alpha)) outside++
            if (apart * alpha > worst * TOP_CODE) {
              worst = apart * alpha / TOP_CODE.toDouble()
              worstAt = "${ink.hex()} in $font, channel $channel at alpha $alpha"
            }
          }
        }
      }
    }

    assertEquals(0, outside, "channels sat past the ink reach, the worst $worst premultiplied steps off at $worstAt")
  }

  @Test
  fun everyOrdinaryGlyphTexelTakesTheTextColourExactly() {
    for (ink in GLYPH_INKS) {
      for (font in FONTS) {
        val glyphs = coverageOf(ink, font)
        val samples = platedSamples(glyphs, ink, plate = null)
        val expected = RGB_SHIFTS.map { sampleOfCode((ink ushr it) and BYTE) }

        val missed =
          (glyphs.indices step CHANNELS).count { texel ->
            glyphs.codeAt(texel + ALPHA) > 0 &&
              (0 until RGB).any { (samples[texel + it].toInt() and SAMPLE_MASK) != expected[it] }
          }
        assertEquals(0, missed, "${ink.hex()} text in $font kept its own colour at $missed texels")
      }
    }
  }

  private fun coverageOf(
    ink: Int,
    font: String,
  ): ByteArray {
    val drawn =
      textCoverage(SIZE.width.toLong(), SIZE.height.toLong(), ink, Int.MAX_VALUE) { context, _ ->
        context.font = font
        context.textBaseline = ALPHABETIC
        LINES.forEachIndexed { index, line -> context.fillText(line, LINE_X, LINE_Y + index * LINE_ADVANCE) }
      }
    val glyphs = assertIs<TextRaster.Drawn<ByteArray>>(drawn).pixels
    assertTrue((glyphs.indices step CHANNELS).any { glyphs.codeAt(it + ALPHA) > 0 }, "${ink.hex()} in $font drew none")
    return glyphs
  }

  private fun ByteArray.codeAt(index: Int): Int = this[index].toInt() and BYTE

  private fun Int.hex(): String = toUInt().toString(HEX)

  private companion object {
    // Small to large in the faces a page falls back to, at sizes that land glyph edges between pixels.
    val FONTS =
      listOf(9.0, 13.0, 18.5, 27.0, 40.0, 72.0).flatMap { size ->
        listOf("${size}px sans-serif", "${size}px serif", "bold ${size}px monospace")
      }

    val SIZE = Size(640, 220)
    val LINES = listOf("Hamburgefonts quick jigsaw", "0123456789 &@ WAVY gloss", "il1| .,;:")
    const val ALPHABETIC = "alphabetic"
    const val LINE_X = 3.25
    const val LINE_Y = 70.5
    const val LINE_ADVANCE = 72.0

    const val CHANNELS = 4
    const val RGB = 3
    const val ALPHA = 3
    const val BYTE = 0xFF
    const val HEX = 16
    val RGB_SHIFTS = intArrayOf(16, 8, 0)
  }
}

@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.toByteArray
import dev.jordond.filmstrip.style.TextAlignment
import dev.jordond.filmstrip.style.TextStyle
import dev.jordond.filmstrip.test.sampleOfCode
import kotlinx.coroutines.test.runTest
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How the browser's text raster draws a colour font's glyphs, read against the same text drawn on one plain canvas.
 *
 * The glyphs come from `colr-glyphs.ttf`, a COLRv0 font the test server hands out, so no system emoji font is needed.
 * Every texel the plain canvas covers fully is inside a glyph and must keep the font's colour, and only texels at a
 * glyph's edge may take the text colour.
 */
class WebColourGlyphTest {
  @Test
  fun aColourGlyphKeepsItsOwnColoursInsideAndTheInkOnlyAtItsEdges() =
    runTest {
      loadColourFont()
      val drawn = drawnColour(INK, plate = null)

      val inside = drawn.texels.filter { drawn.canvasAlpha(it) == BYTE }
      val colours = inside.map { drawn.canvasColour(it) }.toSet()
      assertTrue(COLOUR_PALETTE.all { it in colours }, "inside the glyphs drew ${colours.map { it.hex() }}")
      for (texel in drawn.texels) {
        assertEquals(sampleOfCode(drawn.canvasAlpha(texel)), drawn.sample(texel, ALPHA), "alpha at texel $texel")
      }
      assertWithinOneCode(drawn, inside)

      val inked = drawn.texels.filter { texel -> (0 until RGB).all { drawn.sample(texel, it) == inkSample(it) } }
      val inkedInside = inked.filter { drawn.canvasAlpha(it) == BYTE }
      assertTrue(inkedInside.isEmpty(), "${inkedInside.size} texels inside the glyphs took the text colour")
    }

  // The plain canvas lays its fill's alpha over a colour glyph, which the raster does with the text alpha.
  @Test
  fun aTranslucentTextColourModulatesTheColourGlyph() =
    runTest {
      loadColourFont()
      val drawn = drawnColour(withAlpha(INK, HALF), plate = null)

      val inside = drawn.texels.filter { drawn.canvasAlpha(it) == HALF }
      assertTrue(inside.isNotEmpty(), "no texel drew at the text's half alpha")
      for (texel in drawn.texels) {
        val apart = abs(drawn.sample(texel, ALPHA) - sampleOfCode(drawn.canvasAlpha(texel)))
        assertTrue(apart <= ONE_CODE, "alpha at texel $texel sat $apart samples from the plain canvas")
      }
      val bound = straightBound(HALF)
      for (texel in inside) {
        for (channel in 0 until RGB) {
          val apart = abs(drawn.sample(texel, channel) - sampleOfCode(drawn.canvasCode(texel, channel)))
          assertTrue(apart <= bound, "channel $channel at texel $texel sat $apart samples past $bound")
        }
      }
    }

  @Test
  fun aColourGlyphSitsOverItsPlate() =
    runTest {
      loadColourFont()
      for (text in listOf(INK, withAlpha(INK, HALF))) {
        val drawn = drawnColour(text, PLATE)

        val glyphs = drawn.texels.count { drawn.canvasColour(it) != PLATE }
        assertTrue(glyphs > drawn.texels.size / 4, "the ${text.hex()} glyphs drew $glyphs texels over the plate")
        assertTrue(drawn.texels.all { drawn.sample(it, ALPHA) == sampleOfCode(BYTE) }, "the plate let light through")
        assertWithinOneCode(drawn, drawn.texels)
      }
    }

  private class DrawnColour(
    val samples: ShortArray,
    val canvas: ByteArray,
  ) {
    val texels: List<Int> = (canvas.indices step CHANNELS).filter { canvas[it + ALPHA] != 0.toByte() }

    fun sample(
      texel: Int,
      channel: Int,
    ): Int = samples[texel + channel].toInt() and SAMPLE_MASK

    fun canvasCode(
      texel: Int,
      channel: Int,
    ): Int = canvas[texel + channel].toInt() and BYTE

    fun canvasAlpha(texel: Int): Int = canvasCode(texel, ALPHA)

    fun canvasColour(texel: Int): Int =
      (0 until RGB).fold(canvasAlpha(texel) shl ALPHA_SHIFT) { argb, channel ->
        argb or (canvasCode(texel, channel) shl SHIFTS[channel])
      }
  }

  private fun assertWithinOneCode(
    drawn: DrawnColour,
    texels: List<Int>,
  ) {
    for (texel in texels) {
      for (channel in 0 until RGB) {
        val apart = abs(drawn.sample(texel, channel) - sampleOfCode(drawn.canvasCode(texel, channel)))
        assertTrue(apart <= ONE_CODE, "channel $channel at texel $texel sat $apart samples from the plain canvas")
      }
    }
  }

  // Draws the glyphs through rasterizeText, then the same line in the same font at the same baseline on one plain
  // canvas in the text colour, over the plate when there is one.
  private fun drawnColour(
    text: Int,
    plate: Int?,
  ): DrawnColour {
    val style =
      TextStyle(
        fontSize = FONT_SIZE,
        resolvedFontFamily = COLOUR_FAMILY,
        color = text,
        backgroundColor = plate,
        alignment = TextAlignment.Start,
      )
    val raster = drawnText(GLYPHS, style, FRAME)
    val size = raster.size
    val padding = style.platePaddingOn(FRAME).toDouble()

    val canvas = OffscreenCanvas(size.width, size.height)
    val context = checkNotNull(canvas.getContext(CONTEXT_2D))
    if (plate != null) {
      context.fillStyle = cssOf(plate)
      context.fillRect(0.0, 0.0, size.width.toDouble(), size.height.toDouble())
    }
    context.font = raster.font
    context.textBaseline = ALPHABETIC
    val ascent = context.measureText(CAP_PROBE).fontBoundingBoxAscent
    context.fillStyle = cssOf(text)
    context.fillText(GLYPHS, padding, padding + ascent)
    val read =
      context
        .getImageData(0, 0, size.width, size.height)
        .data.buffer
        .toByteArray()
    return DrawnColour(raster.samples, read)
  }

  private fun inkSample(channel: Int): Int = sampleOfCode((INK ushr SHIFTS[channel]) and BYTE)

  // An eight-bit canvas stores a colour premultiplied, so it reads back up to 255 over its alpha codes apart, plus the
  // code it rounds to.
  private fun straightBound(alpha: Int): Int = sampleOfCode(TOP_CODE / alpha + 1)

  private fun withAlpha(
    argb: Int,
    alpha: Int,
  ): Int = (argb and RGB_MASK) or (alpha shl ALPHA_SHIFT)

  private fun cssOf(argb: Int): String {
    val alpha = ((argb ushr ALPHA_SHIFT) and BYTE) / TOP_CODE.toDouble()
    return "rgba(${(0 until RGB).joinToString(", ") { "${(argb ushr SHIFTS[it]) and BYTE}" }}, $alpha)"
  }

  private fun Int.hex(): String = toUInt().toString(HEX)

  private companion object {
    // A mid grey the palette sits far from in some channel, so only a texel of almost no coverage reads as ink.
    const val INK = 0xFF808080.toInt()
    const val PLATE = 0xFF204060.toInt()
    const val HALF = 0x80

    const val GLYPHS = "AB"
    const val FONT_SIZE = 0.12f
    val FRAME = Size(1280, 720)
    const val CAP_PROBE = "H"
    const val ALPHABETIC = "alphabetic"

    val ONE_CODE = sampleOfCode(1)

    const val CHANNELS = 4
    const val RGB = 3
    const val ALPHA = 3
    const val BYTE = 0xFF
    const val ALPHA_SHIFT = 24
    const val RGB_MASK = 0xFFFFFF
    const val HEX = 16
    val SHIFTS = intArrayOf(16, 8, 0)
  }
}

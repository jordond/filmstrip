@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.toByteArray
import dev.jordond.filmstrip.style.TextAlignment
import dev.jordond.filmstrip.style.TextStyle
import dev.jordond.filmstrip.test.sampleOfCode
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.math.abs
import kotlin.test.Test

/**
 * How the browser's text raster draws a color font's glyphs, read against the same text drawn on one plain canvas.
 *
 * The glyphs come from `colr-glyphs.ttf`, a COLRv0 font the test server hands out, so no system emoji font is needed.
 * Every texel the plain canvas covers fully is inside a glyph and must keep the font's color, and only texels at a
 * glyph's edge may take the text color.
 */
class WebColorGlyphTest {
  @Test
  fun aColorGlyphKeepsItsOwnColorsInsideAndTheInkOnlyAtItsEdges() =
    runTest {
      loadColorFont()
      val drawn = drawnColor(INK, plate = null)

      val inside = drawn.texels.filter { drawn.canvasAlpha(it) == BYTE }
      val colors = inside.map { drawn.canvasColor(it) }.toSet()
      withClue("inside the glyphs drew ${colors.map { it.hex() }}") { COLOR_PALETTE.all { it in colors } shouldBe true }
      for (texel in drawn.texels) {
        withClue("alpha at texel $texel") { drawn.sample(texel, ALPHA) shouldBe sampleOfCode(drawn.canvasAlpha(texel)) }
      }
      assertWithinOneCode(drawn, inside)

      val inked = drawn.texels.filter { texel -> (0 until RGB).all { drawn.sample(texel, it) == inkSample(it) } }
      val inkedInside = inked.filter { drawn.canvasAlpha(it) == BYTE }
      withClue("${inkedInside.size} texels inside the glyphs took the text color") {
        inkedInside.isEmpty() shouldBe true
      }
    }

  // The plain canvas lays its fill's alpha over a color glyph, which the raster does with the text alpha.
  @Test
  fun aTranslucentTextColorModulatesTheColorGlyph() =
    runTest {
      loadColorFont()
      val drawn = drawnColor(withAlpha(INK, HALF), plate = null)

      val inside = drawn.texels.filter { drawn.canvasAlpha(it) == HALF }
      withClue("no texel drew at the text's half alpha") { inside.isNotEmpty() shouldBe true }
      for (texel in drawn.texels) {
        val apart = abs(drawn.sample(texel, ALPHA) - sampleOfCode(drawn.canvasAlpha(texel)))
        withClue("alpha at texel $texel sat $apart samples from the plain canvas") { (apart <= ONE_CODE) shouldBe true }
      }
      val bound = straightBound(HALF)
      for (texel in inside) {
        for (channel in 0 until RGB) {
          val apart = abs(drawn.sample(texel, channel) - sampleOfCode(drawn.canvasCode(texel, channel)))
          withClue("channel $channel at texel $texel sat $apart samples past $bound") { (apart <= bound) shouldBe true }
        }
      }
    }

  @Test
  fun aColorGlyphSitsOverItsPlate() =
    runTest {
      loadColorFont()
      for (text in listOf(INK, withAlpha(INK, HALF))) {
        val drawn = drawnColor(text, PLATE)

        val glyphs = drawn.texels.count { drawn.canvasColor(it) != PLATE }
        withClue("the ${text.hex()} glyphs drew $glyphs texels over the plate") {
          (glyphs > drawn.texels.size / 4) shouldBe true
        }
        withClue("the plate let light through") {
          drawn.texels.all { drawn.sample(it, ALPHA) == sampleOfCode(BYTE) } shouldBe true
        }
        assertWithinOneCode(drawn, drawn.texels)
      }
    }

  private class DrawnColor(
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

    fun canvasColor(texel: Int): Int =
      (0 until RGB).fold(canvasAlpha(texel) shl ALPHA_SHIFT) { argb, channel ->
        argb or (canvasCode(texel, channel) shl SHIFTS[channel])
      }
  }

  private fun assertWithinOneCode(
    drawn: DrawnColor,
    texels: List<Int>,
  ) {
    for (texel in texels) {
      for (channel in 0 until RGB) {
        val apart = abs(drawn.sample(texel, channel) - sampleOfCode(drawn.canvasCode(texel, channel)))
        withClue("channel $channel at texel $texel sat $apart samples from the plain canvas") {
          (apart <= ONE_CODE) shouldBe true
        }
      }
    }
  }

  // Draws the glyphs through rasterizeText, then the same line in the same font at the same baseline on one plain
  // canvas in the text color, over the plate when there is one.
  private fun drawnColor(
    text: Int,
    plate: Int?,
  ): DrawnColor {
    val style =
      TextStyle(
        fontSize = FONT_SIZE,
        resolvedFontFamily = COLOR_FAMILY,
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
    return DrawnColor(raster.samples, read)
  }

  private fun inkSample(channel: Int): Int = sampleOfCode((INK ushr SHIFTS[channel]) and BYTE)

  // An eight-bit canvas stores a color premultiplied, so it reads back up to 255 over its alpha codes apart, plus the
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

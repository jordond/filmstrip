package dev.jordond.filmstrip.effects.overlay

import android.graphics.Canvas
import android.text.StaticLayout
import androidx.core.graphics.createBitmap
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.style.TextStyle
import dev.jordond.filmstrip.test.GLYPH_INKS
import org.junit.Assume.assumeTrue
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * How the glyph bytes [textCoverage] reads back sit against the text colour, and what [platedSamples] makes of them.
 *
 * Ordinary text drawn from masks and from outlines reads back within [glyphInkReach] of its ink in every channel, so
 * every texel of it is laid in the text's exact colour. A colour emoji's texels keep the colours Android draws them in.
 */
class GlyphInkTest {
  @Test
  fun ordinaryTextReadsBackWithinTheInkReach() {
    var outside = 0
    var worst = 0.0
    var worstCase = "nothing"
    for (ink in GLYPH_INKS) {
      for (fontSize in FONT_SIZES) {
        val block = assertNotNull(textBlock(WORDS, TextStyle(fontSize = fontSize, color = ink), FRAME))
        val glyphs = textCoverage(block.width, block.height, ::textBitmap) { block.draw(it) }
        for (texel in glyphs.indices step CHANNELS) {
          val alpha = glyphs.codeAt(texel + ALPHA)
          if (alpha == 0) continue
          for (channel in 0 until ALPHA) {
            val apart = abs(glyphs.codeAt(texel + channel) - codeOf(ink, SHIFTS[channel]))
            if (apart > glyphInkReach(alpha)) outside++
            if (apart * alpha > worst * OPAQUE) {
              worst = apart * alpha / OPAQUE.toDouble()
              worstCase = "${ink.hex()} at $fontSize, channel $channel at alpha $alpha"
            }
          }
        }
      }
    }

    assertEquals(0, outside, "channels sat past the ink reach, the worst $worst premultiplied steps off at $worstCase")
  }

  // Android draws an emoji from its colour font whatever the paint's colour, so the raster keeps those colours rather
  // than the ink's, within the one code a premultiplied texel rounds to. An edge texel whose colour sits within the ink
  // reach is laid in the ink instead, at most INK_STEPS premultiplied steps from Android's own plus that code.
  @Test
  fun aColourEmojiKeepsTheColoursAndroidDrawsItIn() {
    val style = TextStyle(fontSize = EMOJI_SIZE, color = INK)
    val block = assertNotNull(textBlock(EMOJI, style, FRAME))
    val plain = plainDraw(block)
    assumeTrue("this device draws $EMOJI in colour", (plain.indices step CHANNELS).any { plain.isColour(it) })

    val drawn = assertIs<TextRaster.Drawn<TextSamples>>(rasterizeText(EMOJI, style, FRAME, ANY_SIDE)).pixels
    assertEquals(Size(block.width, block.height), drawn.size)

    for (texel in plain.indices step CHANNELS) {
      val alpha = drawn.samples.sampleAt(texel + ALPHA)
      assertEquals(plain.codeAt(texel + ALPHA) * EIGHT_BIT_STEP, alpha, "the alpha of texel ${texel / CHANNELS}")
      val tolerance = if (plain.isInk(texel)) INK_STEPS + 1.0 else 1.0
      for (channel in 0 until ALPHA) {
        val premultiplied = drawn.samples.sampleAt(texel + channel).toDouble() * alpha / TOP_SAMPLE / EIGHT_BIT_STEP
        val plainCode = plain.codeAt(texel + channel)
        assertTrue(
          abs(premultiplied - plainCode) <= tolerance,
          "texel ${texel / CHANNELS} channel $channel drew $premultiplied premultiplied against Android's $plainCode",
        )
      }
    }
    val interior =
      (drawn.samples.indices step CHANNELS).filter {
        drawn.samples.sampleAt(it + ALPHA) == TOP_SAMPLE && drawn.samples.isColour(it)
      }
    assertTrue(interior.isNotEmpty(), "every opaque texel of $EMOJI was laid in the ink")
  }

  // The layout drawn the way Android draws any text, read back premultiplied as the bitmap stores it.
  private fun plainDraw(block: StaticLayout): ByteArray {
    val bitmap = createBitmap(block.width, block.height)
    try {
      block.draw(Canvas(bitmap))
      return ByteArray(block.width * block.height * CHANNELS).also { bitmap.copyPixelsToBuffer(ByteBuffer.wrap(it)) }
    } finally {
      bitmap.recycle()
    }
  }

  // An opaque texel whose colour sits past the ink reach from the ink in some channel.
  private fun ByteArray.isColour(texel: Int): Boolean =
    codeAt(texel + ALPHA) == OPAQUE && (0 until ALPHA).any { abs(codeAt(texel + it) - codeOf(INK, SHIFTS[it])) > REACH }

  private fun ShortArray.isColour(texel: Int): Boolean =
    (0 until ALPHA).any { abs(sampleAt(texel + it) / EIGHT_BIT_STEP.toDouble() - codeOf(INK, SHIFTS[it])) > REACH }

  // A covered texel of the plain draw whose colour, read back straight, sits within the ink reach of the ink at its
  // alpha in every channel, which the raster lays in the ink.
  private fun ByteArray.isInk(texel: Int): Boolean {
    val coverage = codeAt(texel + ALPHA)
    if (coverage == 0) return false
    val reach = glyphInkReach(coverage)
    return (0 until ALPHA).all { abs(straightAt(texel + it, coverage) - codeOf(INK, SHIFTS[it])) <= reach }
  }

  private fun ByteArray.codeAt(index: Int): Int = this[index].toInt() and OPAQUE

  // A premultiplied code read back straight at coverage alpha, rounded to the nearest code.
  private fun ByteArray.straightAt(
    index: Int,
    coverage: Int,
  ): Int = minOf(OPAQUE, (codeAt(index) * OPAQUE + coverage / 2) / coverage)

  private fun ShortArray.sampleAt(index: Int): Int = this[index].toInt() and SAMPLE_MASK

  private fun codeOf(
    argb: Int,
    shift: Int,
  ): Int = (argb ushr shift) and OPAQUE

  private fun Int.hex(): String = "0x%08X".format(this)

  private companion object {
    val FRAME = Size(1280, 720)

    // Small sizes Android draws from masks and large ones it draws from outlines.
    val FONT_SIZES = listOf(0.02f, 0.04f, 0.1f, 0.3f)
    val SHIFTS = listOf(16, 8, 0)

    // Green sits far from every colour in the emoji, so none of its texels reads as ink by chance.
    const val INK = 0xFF00FF00.toInt()
    const val EMOJI = "😀"
    const val EMOJI_SIZE = 0.1f

    // A texture side no raster here reaches, so only what a test is about refuses it.
    const val ANY_SIDE = Int.MAX_VALUE
    const val WORDS = "Hamburgefonts"
    val REACH = glyphInkReach(OPAQUE)
    const val CHANNELS = 4
    const val ALPHA = 3
    const val OPAQUE = 255
  }
}

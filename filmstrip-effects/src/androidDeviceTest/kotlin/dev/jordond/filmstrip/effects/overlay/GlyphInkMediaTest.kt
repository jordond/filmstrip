package dev.jordond.filmstrip.effects.overlay

import android.graphics.Canvas
import android.text.StaticLayout
import androidx.core.graphics.createBitmap
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.style.TextStyle
import dev.jordond.filmstrip.test.GLYPH_INKS
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Assume.assumeTrue
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.test.Test

/**
 * How the glyph bytes [textCoverage] reads back sit against the text color, and what [platedSamples] makes of them.
 *
 * Ordinary text drawn from masks and from outlines reads back within [glyphInkReach] of its ink in every channel, so
 * every texel of it is laid in the text's exact color. A color emoji's texels keep the colors Android draws them in.
 */
class GlyphInkMediaTest {
  @Test
  fun ordinaryTextReadsBackWithinTheInkReach() {
    var outside = 0
    var worst = 0.0
    var worstCase = "nothing"
    for (ink in GLYPH_INKS) {
      for (fontSize in FONT_SIZES) {
        val block = textBlock(WORDS, TextStyle(fontSize = fontSize, color = ink), FRAME).shouldNotBeNull()
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

    withClue("channels sat past the ink reach, the worst $worst premultiplied steps off at $worstCase") {
      outside shouldBe 0
    }
  }

  // Android draws an emoji from its color font whatever the paint's color, so the raster keeps those colors rather
  // than the ink's, within the one code a premultiplied texel rounds to. An edge texel whose color sits within the ink
  // reach is laid in the ink instead, at most INK_STEPS premultiplied steps from Android's own plus that code.
  @Test
  fun aColorEmojiKeepsTheColorsAndroidDrawsItIn() {
    val style = TextStyle(fontSize = EMOJI_SIZE, color = INK)
    val block = textBlock(EMOJI, style, FRAME).shouldNotBeNull()
    val plain = plainDraw(block)
    assumeTrue("this device draws $EMOJI in color", (plain.indices step CHANNELS).any { plain.isColor(it) })

    val drawn = rasterizeText(EMOJI, style, FRAME, ANY_SIDE).shouldBeInstanceOf<TextRaster.Drawn<TextSamples>>().pixels
    drawn.size shouldBe Size(block.width, block.height)

    for (texel in plain.indices step CHANNELS) {
      val alpha = drawn.samples.sampleAt(texel + ALPHA)
      withClue("the alpha of texel ${texel / CHANNELS}") { alpha shouldBe plain.codeAt(texel + ALPHA) * EIGHT_BIT_STEP }
      val tolerance = if (plain.isInk(texel)) INK_STEPS + 1.0 else 1.0
      for (channel in 0 until ALPHA) {
        val premultiplied = drawn.samples.sampleAt(texel + channel).toDouble() * alpha / TOP_SAMPLE / EIGHT_BIT_STEP
        val plainCode = plain.codeAt(texel + channel)
        withClue(
          "texel ${texel / CHANNELS} channel $channel drew $premultiplied premultiplied against Android's $plainCode",
        ) {
          (abs(premultiplied - plainCode) <= tolerance) shouldBe true
        }
      }
    }
    val interior =
      (drawn.samples.indices step CHANNELS).filter {
        drawn.samples.sampleAt(it + ALPHA) == TOP_SAMPLE && drawn.samples.isColor(it)
      }
    withClue("every opaque texel of $EMOJI was laid in the ink") { interior.isNotEmpty() shouldBe true }
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

  // An opaque texel whose color sits past the ink reach from the ink in some channel.
  private fun ByteArray.isColor(texel: Int): Boolean =
    codeAt(texel + ALPHA) == OPAQUE && (0 until ALPHA).any { abs(codeAt(texel + it) - codeOf(INK, SHIFTS[it])) > REACH }

  private fun ShortArray.isColor(texel: Int): Boolean =
    (0 until ALPHA).any { abs(sampleAt(texel + it) / EIGHT_BIT_STEP.toDouble() - codeOf(INK, SHIFTS[it])) > REACH }

  // A covered texel of the plain draw whose color, read back straight, sits within the ink reach of the ink at its
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

    // Green sits far from every color in the emoji, so none of its texels reads as ink by chance.
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

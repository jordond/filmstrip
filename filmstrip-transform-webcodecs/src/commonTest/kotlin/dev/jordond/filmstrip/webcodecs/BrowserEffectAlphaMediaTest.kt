package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.color.blurPlane
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.filters.crt.Crt
import dev.jordond.filmstrip.effects.filters.crt.CrtParams
import dev.jordond.filmstrip.effects.filters.crt.CrtSignal
import dev.jordond.filmstrip.effects.filters.lut.CubeLut
import dev.jordond.filmstrip.effects.filters.lut.Lut
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.spatial.GaussianBlur
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.webcodecs.internal.preview.PreviewFrame
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test

/**
 * Effects drawn on frames whose alpha sits part way, through each real stage of the browser compositor.
 *
 * An [AlphaLeftOf] pass takes the clip's alpha to half over part of its frame, which no decoded clip carries on its
 * own. It is no built-in effect, so it runs after every built-in one of its stage, and a filter at the clip's scope
 * comes after it. Every read is held against the shared function of the effect after it on straight color, laid over
 * what is under the picture by the alpha the pixel holds. A clip filling the frame lies over black.
 */
class BrowserEffectAlphaMediaTest {
  @Test
  fun aClipLutOnHalfAlphaReadsStraightColor() =
    runTest {
      val source = flat(BRIGHT)
      val table = CubeLut.parse(cubeText(CUBE_POINTS) { red, green, blue -> doubleArrayOf(red * red, green, 1 - blue) })
      val plain = frameOf(Clip(source))
      val drawn = frameOf(Clip(source, effects = listOf(AlphaLeftOf(LEFT_HALF, HALF), Lut(table))), fill = Fill.Black)

      for ((x, y) in LUT_POINTS) {
        val picture = plain.at(x, y)
        val straight =
          table.at(
            (picture.red / MAX_CODE).toFloat(),
            (picture.green / MAX_CODE).toFloat(),
            (picture.blue / MAX_CODE).toFloat(),
          )
        val alpha = if (x < LEFT_HALF) HALF else 1.0
        val read = drawn.at(x, y)
        val reads = listOf(read.red, read.green, read.blue)
        for (channel in reads.indices) {
          val expected = laid(straight[channel].toDouble(), alpha, 0)
          withClue("at $x, $y channel $channel read ${reads[channel]}, and the table lays it at $expected") {
            abs(reads[channel] - expected) shouldBeLessThanOrEqualTo DRAWN_CODES
          }
        }
      }
    }

  @Test
  fun aClipCrtOnHalfAlphaReadsStraightColor() =
    runTest {
      val source = flat(BRIGHT)
      val crt = Crt(MID)
      val params = CrtParams.of(crt)
      val plain = frameOf(Clip(source))
      val drawn = frameOf(Clip(source, effects = listOf(AlphaLeftOf(LEFT_HALF, HALF), crt)), fill = Fill.Black)
      val width = drawn.size.width
      val height = drawn.size.height
      val picture = plain.at(MIDDLE, MIDDLE).red / MAX_CODE

      for ((x, y) in CRT_POINTS) {
        val column = (x * width).toInt()
        val row = (y * height).toInt()
        val u = (column + 0.5) / width
        val v = (row + 0.5) / height
        val source = CrtSignal.barrelMap(u, v, width, height, params.barrelK)
        withClue("the read at $x, $y draws from $source, which has to sit in the half alpha") {
          (source[0] < LEFT_HALF - EDGE_MARGIN) shouldBe true
        }
        val straight = CrtSignal.pixelAt(u, v, width, height, params) { _, _ -> doubleArrayOf(picture) }[0]
        val expected = laid(straight, HALF, 0)
        val read = drawn.at(x, y).red
        withClue("at $x, $y the CRT read $read, and straight color at half alpha lays it at $expected") {
          abs(read - expected) shouldBeLessThanOrEqualTo DRAWN_CODES
        }
      }
    }

  // The frame under a color fill holds premultiplied color, so this is the stage that tells a pass reading it as
  // straight from one reading it as it is.
  @Test
  fun aCompositionBlurUnderTheFillOnHalfAlphaReadsStraightColor() =
    runTest {
      val source = verticalEdge()
      val layout = listOf<EffectSpec>(Crop(AspectRatio.Square, Fit.Contain))
      val clip = Clip(source, effects = listOf(AlphaLeftOf(QUARTER, HALF)))
      val plain = frameOf(Clip(source), effects = layout)
      val drawn = frameOf(clip, effects = layout + GaussianBlur(SIGMA))
      val width = drawn.size.width
      val height = drawn.size.height
      // The clip's picture is the layout's width and its own aspect tall, centred, with no partial coverage at its
      // edge. Its left quarter holds half alpha.
      val pictureHeight = width.toDouble() * HEIGHT / WIDTH
      val top = (height - pictureHeight) / 2.0
      val alpha =
        DoubleArray(width * height) {
          val inside = it / width + 0.5 in top..(top + pictureHeight)
          val left = (it % width + 0.5) / width < QUARTER
          if (!inside) {
            0.0
          } else if (left) {
            HALF
          } else {
            1.0
          }
        }
      val middle = height / 2 * width
      val picture = DoubleArray(width * height) { plain.redAt(middle + it % width) / MAX_CODE }

      val reference = blurPlane(picture, alpha, width, height, SIGMA.toDouble())
      var worst = 0.0
      var moved = 0.0
      for (index in reference.indices) {
        val expected = if (alpha[index] > 0.0) laid(reference[index], alpha[index], FILL.red) else FILL.red.toDouble()
        worst = maxOf(worst, abs(drawn.redAt(index) - expected))
        if (alpha[index] > 0.0) moved = maxOf(moved, abs(reference[index] - picture[index]) * MAX_CODE)
      }
      withClue("the reference blur moves the picture by $moved codes, so the read is not of a sharp frame") {
        moved shouldBeGreaterThan SHARP_SPREAD
      }
      withClue("the drawn blur is $worst codes off straight color laid over the fill at its worst pixel") {
        worst shouldBeLessThanOrEqualTo DRAWN_CODES
      }
    }

  private suspend fun flat(color: Rgb): MediaSource =
    MediaSource.Bytes(makeClip(WIDTH, HEIGHT, FRAMES, ALPHA_FRAME_RATE) { _, _ -> color })

  private suspend fun verticalEdge(): MediaSource =
    MediaSource.Bytes(
      makePixelClip(WIDTH, HEIGHT, FRAMES, ALPHA_FRAME_RATE) { _, x, _ -> if (x < WIDTH / 2) LIGHT else DARK },
    )

  // Straight color at alpha source over the fill, in eight bit codes.
  private fun laid(
    straight: Double,
    alpha: Double,
    fill: Int,
  ): Double = straight * MAX_CODE * alpha + fill * (1.0 - alpha)

  private fun PreviewFrame.redAt(index: Int): Double = (pixels[index * RGBA].toInt() and BYTE_MASK).toDouble()

  private suspend fun frameOf(
    clip: Clip,
    effects: List<EffectSpec> = emptyList(),
    fill: Fill = Fill.Solid(FILL_ARGB),
  ): PreviewFrame = alphaFrameOf(clip, effects, fill)
}

private val BRIGHT = Rgb(230, 225, 220)
private val LIGHT = Rgb(220, 220, 220)
private val DARK = Rgb(40, 40, 40)
private val FILL = Rgb(0x30, 0x80, 0xC0)
private const val FILL_ARGB = 0xFF3080C0.toInt()
private const val WIDTH = 320
private const val HEIGHT = 180
private const val FRAMES = 12
private const val MAX_CODE = 255.0
private const val RGBA = 4
private const val BYTE_MASK = 0xFF
private const val HALF = 0.5
private const val MIDDLE = 0.5
private const val LEFT_HALF = 0.5
private const val QUARTER = 0.25
private const val MID = 0.5f
private const val SIGMA = 0.05f

// How far left of the alpha's edge a CRT read has to draw from, past the linear filter's reach.
private const val EDGE_MARGIN = 0.02

// Each eight bit target between the steps rounds once, the straight color under the fill once more, and the read once.
private const val DRAWN_CODES = 3.0

// A blur of a 180 code edge at a sigma of 16 pixels moves the columns beside it by far more than this.
private const val SHARP_SPREAD = 40.0

// Either side of the alpha's edge and off it.
private val LUT_POINTS = listOf(0.25 to 0.5, 0.4 to 0.3, 0.75 to 0.5)
private const val CUBE_POINTS = 5

// Inside the left half, clear of the curve's edge where coverage fades the picture.
private val CRT_POINTS = listOf(0.3 to 0.5, 0.2 to 0.4, 0.4 to 0.6, 0.25 to 0.3)

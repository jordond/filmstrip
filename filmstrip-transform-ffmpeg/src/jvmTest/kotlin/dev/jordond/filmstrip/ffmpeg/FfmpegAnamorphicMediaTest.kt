package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.filters.crt.Crt
import dev.jordond.filmstrip.effects.filters.film.Film
import dev.jordond.filmstrip.effects.filters.lut.CubeLut
import dev.jordond.filmstrip.effects.filters.lut.Lut
import dev.jordond.filmstrip.effects.filters.pixel.Pixel
import dev.jordond.filmstrip.effects.filters.pixel.PixelSignal
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.CropRect
import dev.jordond.filmstrip.effects.geometry.Rotate
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.effects.spatial.GaussianBlur
import dev.jordond.filmstrip.effects.spatial.Vignette
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.PIXEL_CODES
import dev.jordond.filmstrip.ffmpeg.support.Yuv420Frame
import dev.jordond.filmstrip.ffmpeg.support.lumaClipOf
import dev.jordond.filmstrip.ffmpeg.support.pngOf
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test

/**
 * Reads each ffmpeg resolver at clip scope on a clip with non-square pixels, which reaches the fragment as it is
 * stored, against the same effect on a clip of square pixels that shows the same picture.
 *
 * The anamorphic clip is stored half as wide as it shows, with pixels twice as wide as they are tall. Both clips draw
 * one picture of four flat quarters, so a pixel read off either sits on a flat area or beside one of two edges. A
 * pixel counts as a miss when it lands more than [LUMA_CODES] outside the range the pixels around it span in the square
 * clip's frame, so the widening's blend across an edge passes and an edge out of place does not.
 *
 * A [Pixel] reads one pixel at each cell's centre, so a centre on the widening's blend reads that blend. Its frame is
 * held against [PixelSignal] over the same clip drawn with no effect at the shown size instead.
 */
class FfmpegAnamorphicMediaTest {
  private val draw = FilterFrames()

  @Test
  fun `a clip crop on non-square pixels draws what it draws on square ones`() =
    runBlocking<Unit> { assertDrawnAlike(Crop(AspectRatio.Square)) }

  @Test
  fun `a clip crop rect on non-square pixels draws what it draws on square ones`() =
    runBlocking<Unit> { assertDrawnAlike(CropRect(NormalizedRect(0.3f, 0.1f, 0.8f, 0.7f))) }

  @Test
  fun `a clip quarter turn on non-square pixels draws what it draws on square ones`() =
    runBlocking<Unit> { assertDrawnAlike(Rotate(QUARTER_TURN)) }

  @Test
  fun `a clip turn by any angle on non-square pixels draws what it draws on square ones`() =
    runBlocking<Unit> { assertDrawnAlike(Rotate(ANY_TURN)) }

  @Test
  fun `a clip image overlay on non-square pixels draws what it draws on square ones`() =
    runBlocking<Unit> { assertDrawnAlike(swatch()) }

  @Test
  fun `a clip text overlay on non-square pixels draws what it draws on square ones`() =
    runBlocking<Unit> { assertDrawnAlike(TextOverlay(text = "Wide", anchor = Anchor.Center)) }

  @Test
  fun `a clip film on non-square pixels draws what it draws on square ones`() =
    runBlocking<Unit> { assertDrawnAlike(Film()) }

  @Test
  fun `a clip vhs on non-square pixels draws what it draws on square ones`() =
    runBlocking<Unit> { assertDrawnAlike(Vhs()) }

  @Test
  fun `a clip lut on non-square pixels draws what it draws on square ones`() =
    runBlocking<Unit> { assertDrawnAlike(Lut(INVERTED)) }

  @Test
  fun `a clip crt on non-square pixels draws what it draws on square ones`() =
    runBlocking<Unit> { assertDrawnAlike(Crt(1f)) }

  @Test
  fun `a clip pixel on non-square pixels lands on the signal at the shown size`() =
    runBlocking<Unit> {
      val pixel = Pixel()
      val drawn = draw.planesAt(draw.composition(listOf(anamorphic(pixel))), 0)
      val plain = draw.planesAt(draw.composition(listOf(anamorphic())), 0)

      withClue("the frame of non-square pixels") {
        drawn.width shouldBe WIDTH
        drawn.height shouldBe HEIGHT
      }
      var worst = 0
      var worstAt = 0 to 0
      for (y in 0 until HEIGHT) {
        for (x in 0 until WIDTH) {
          val expected =
            PixelSignal.pixelAt(x, y, WIDTH, HEIGHT, pixel) { px, py ->
              DoubleArray(RGB) { plain.luma(px, py).toDouble() }
            }
          val gap = abs(drawn.luma(x, y) - expected[0].roundToInt())
          if (gap > worst) {
            worst = gap
            worstAt = x to y
          }
        }
      }
      withClue("worst $worst at $worstAt") { worst shouldBeLessThanOrEqual PIXEL_CODES }
    }

  // The crop widens the pixels to square, so the blur after it reads the frame it is handed.
  @Test
  fun `a blur after a crop on non-square pixels draws what it draws on square ones`() =
    runBlocking<Unit> { assertDrawnAlike(Crop(AspectRatio.Square), GaussianBlur(SIGMA)) }

  // The vignette runs on the stored frame, and the overlay after it on the frame widened to square.
  @Test
  fun `an overlay after a vignette on non-square pixels draws what it draws on square ones`() =
    runBlocking<Unit> { assertDrawnAlike(Vignette(), swatch()) }

  private fun anamorphic(vararg effects: EffectSpec): Clip =
    draw.lumaClipOf(WIDTH / 2, HEIGHT, QUARTERS, sampleAspect = 2, effects = effects.toList())

  private suspend fun assertDrawnAlike(vararg effects: EffectSpec) {
    val wide = anamorphic(*effects)
    val square = draw.lumaClipOf(WIDTH, HEIGHT, QUARTERS, effects = effects.toList())
    val reference = draw.planesAt(draw.composition(listOf(square)), 0)
    val drawn = draw.planesAt(draw.composition(listOf(wide)), 0)

    withClue("the frame of non-square pixels") {
      drawn.width shouldBe reference.width
      drawn.height shouldBe reference.height
    }
    var misses = 0
    var worst = 0
    var worstAt = 0 to 0
    for (y in 0 until drawn.height) {
      for (x in 0 until drawn.width) {
        val miss = nearestMiss(drawn, reference, x, y)
        if (miss > LUMA_CODES) misses++
        if (miss > worst) {
          worst = miss
          worstAt = x to y
        }
      }
    }
    withClue(
      "${effects.toList()}: $misses pixels land more than $LUMA_CODES codes from every pixel beside them on square " +
        "pixels, at worst $worst at $worstAt",
    ) {
      misses shouldBeLessThanOrEqual 0
    }
  }

  private fun swatch(): ImageOverlay =
    ImageOverlay(
      ImageSource.ofBytes(pngOf(PALE_ARGB, Size(SWATCH_PX, SWATCH_PX))),
      Corner.TopStart,
      scale = SWATCH_SCALE,
    )

  private fun nearestMiss(
    drawn: Yuv420Frame,
    reference: Yuv420Frame,
    x: Int,
    y: Int,
  ): Int {
    var low = Int.MAX_VALUE
    var high = Int.MIN_VALUE
    for (dy in -1..1) {
      for (dx in -1..1) {
        val nx = x + dx
        val ny = y + dy
        if (nx !in 0 until reference.width || ny !in 0 until reference.height) continue
        low = minOf(low, reference.luma(nx, ny))
        high = maxOf(high, reference.luma(nx, ny))
      }
    }
    val luma = drawn.luma(x, y)
    return maxOf(low - luma, luma - high, 0)
  }

  private companion object {
    const val WIDTH = 384
    const val HEIGHT = 216
    const val QUARTERS = "40+80*gte(X/W,0.5)+100*gte(Y/H,0.5)"

    // The bicubic widening to the shown width rings beside an edge and rounds each way, both ffmpeg's limits. Measured
    // at 8 on 8.0.1.
    const val LUMA_CODES = 10

    const val RGB = 3

    const val QUARTER_TURN = 90
    const val ANY_TURN = 30
    const val SIGMA = 0.05f
    const val SWATCH_PX = 16
    const val SWATCH_SCALE = 0.25f
    const val PALE_ARGB = 0xFFE0E0E0.toInt()

    val INVERTED =
      CubeLut.parse(
        "LUT_3D_SIZE 2\n" + (0 until 8).joinToString("\n") { "${1 - it % 2} ${1 - it / 2 % 2} ${1 - it / 4}" },
      )
  }
}

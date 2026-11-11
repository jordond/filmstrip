package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.effects.filters.pixel.Pixel
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.ffmpeg.support.FILTER_RATE
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.PIXEL_CODES
import dev.jordond.filmstrip.ffmpeg.support.channelAt
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.test.OverlayPixels
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Reads what a [Pixel] reaches at the composition scope on ffmpeg, straight off the preview pump.
 */
class FfmpegPixelMediaTest {
  private val draw = FilterFrames()

  @Test
  fun `an overlay at the same scope stays clean`() =
    runBlocking<Unit> {
      val twoLevels = Pixel(cells = CELLS, levels = 2)
      val clip = draw.clipOf(WIDTH, HEIGHT, { GREY.copyOf() })
      val swatched = draw.clipOf(WIDTH, HEIGHT, { GREY.copyOf() }, listOf(swatch()))
      val x = (SWATCH_X * WIDTH).toInt()
      val y = (SWATCH_Y * HEIGHT).toInt()

      val clean = rgbaOf(draw.composition(listOf(clip), listOf(swatch(), twoLevels)))
      val plain = rgbaOf(draw.composition(listOf(swatched)))

      withClue("the look has to cut the grey down to black for this to tell") {
        clean.channelAt(WIDTH, WIDTH / 2, HEIGHT / 2, 0) shouldBeLessThanOrEqual BLACK_CODES
      }
      (0 until RGB).forEach { channel ->
        withClue("channel $channel") {
          abs(clean.channelAt(WIDTH, x, y, channel) - plain.channelAt(WIDTH, x, y, channel)) shouldBeLessThan
            PIXEL_CODES
        }
      }
    }

  @Test
  fun `a frame from a windowed preview equals the same frame on export`() =
    runBlocking<Unit> {
      val composition =
        draw.composition(listOf(draw.clipOf(WIDTH, HEIGHT, ::rampAt)), listOf(Pixel(cells = CELLS, levels = 6)))
      val at = (READ_SLOT.toDouble() / FILTER_RATE).seconds

      val fromStart = draw.frameAt(composition, Duration.ZERO, READ_SLOT)
      val fromWindow = draw.frames(composition, at, 1).single()

      fromWindow.contentEquals(fromStart) shouldBe true
    }

  private fun rampAt(x: Double): DoubleArray = doubleArrayOf(0.2 + 0.6 * x, 0.45, 0.6 - 0.4 * x)

  private suspend fun rgbaOf(composition: Composition): ByteArray = draw.frames(composition, Duration.ZERO, 1).single()

  private fun swatch(): ImageOverlay =
    ImageOverlay(
      ImageSource.ofBytes(OverlayPixels.pngOf(Size(SWATCH_PX, SWATCH_PX), PALE_SWATCH_ARGB)),
      Corner.TopStart,
      scale = SWATCH_SCALE,
    )

  private companion object {
    const val WIDTH = 640
    const val HEIGHT = 360
    const val CELLS = 45
    const val READ_SLOT = 12
    const val RGB = 3
    val GREY = doubleArrayOf(0.47, 0.47, 0.47)
    const val SWATCH_PX = 64
    const val SWATCH_SCALE = 0.3f

    // Two levels would lift this to full white, well past PIXEL_CODES.
    const val PALE_SWATCH_ARGB = 0xFFE0E0E0.toInt()
    const val SWATCH_X = 0.15
    const val SWATCH_Y = 0.2

    // Black through the way back to 4:2:0 and out to RGB.
    const val BLACK_CODES = 2
  }
}

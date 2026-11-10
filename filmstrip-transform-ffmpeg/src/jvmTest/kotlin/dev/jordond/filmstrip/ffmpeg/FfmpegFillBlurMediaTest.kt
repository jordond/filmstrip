package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.color.blurPlane
import dev.jordond.filmstrip.ffmpeg.support.FILTER_RATE
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.Yuv420Frame
import dev.jordond.filmstrip.ffmpeg.support.clipOfPicture
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.transform.internal.geometry.blurPlan
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test

/**
 * Reads the bars ffmpeg draws for a [Fill.Blurred] off the preview pump, against [blurPlane] run by the fill's shared
 * plan on the cover crop of the clip.
 *
 * A wide clip sets the output, and a taller clip after it is contained with bars beside it. The tall clip is as wide
 * as the output, so its cover is a crop with no scale and its rows are the clip's own. The crop cuts through rows of
 * another grey, so a blur held at the cover crop's edge reads apart from one held at the clip's.
 */
class FfmpegFillBlurMediaTest {
  private val draw = FilterFrames()

  @Test
  fun `a blurred fill lands on the shared plan mid-frame and at the cover crop edge`() =
    runBlocking<Unit> {
      val wide = draw.clipOfPicture(WIDTH, HEIGHT, { _, _ -> DoubleArray(3) { MID } })
      val tall = draw.clipOfPicture(WIDTH, TALL_HEIGHT, ::tallAt)
      val fill = Fill.Blurred(radius = RADIUS, dim = 0f)
      val drawn = draw.planesAt(draw.composition(listOf(wide, tall), fill = fill), TALL_SLOT)
      val source = draw.planesAt(draw.composition(listOf(tall)), 0)
      drawn.width shouldBe WIDTH
      drawn.height shouldBe HEIGHT

      val plan = fill.blurPlan(Size(WIDTH, HEIGHT))
      val sigma = plan.sigmaPx / plan.width
      val cover = lumaOf(source, CROP_TOP, HEIGHT)
      val expected = blurPlane(cover, DoubleArray(cover.size) { 1.0 }, WIDTH, HEIGHT, sigma)
      val whole = lumaOf(source, 0, TALL_HEIGHT)
      val heldAtClip = blurPlane(whole, DoubleArray(whole.size) { 1.0 }, WIDTH, TALL_HEIGHT, sigma)

      var worst = 0.0
      var worstAt = 0 to 0
      var spread = 0.0
      var apart = 0.0
      for (y in 0 until HEIGHT) {
        for (x in BAR_COLUMNS) {
          val index = y * WIDTH + x
          val off = abs(drawn.luma(x, y) - expected[index])
          if (off > worst) {
            worst = off
            worstAt = x to y
          }
          spread = maxOf(spread, abs(expected[index] - cover[index]))
          if (y == 0 || y == HEIGHT - 1) {
            apart = maxOf(apart, abs(heldAtClip[(y + CROP_TOP) * WIDTH + x] - expected[index]))
          }
        }
      }
      withClue("the shared blur moves the cover by $spread codes, so the read is not of a sharp frame") {
        spread shouldBeGreaterThan SHARP_SPREAD
      }
      withClue("a blur held at the clip's edge lands $apart codes from one held at the crop's, too close to tell") {
        apart shouldBeGreaterThan 2 * FILL_CODES
      }
      val (x, y) = worstAt
      withClue("the fill is $worst codes off the shared plan at $x, $y, reading ${drawn.luma(x, y)}") {
        worst shouldBeLessThanOrEqual FILL_CODES
      }
    }

  // The tall clip's luma from row [top], [rows] rows of it.
  private fun lumaOf(
    frame: Yuv420Frame,
    top: Int,
    rows: Int,
  ): DoubleArray = DoubleArray(WIDTH * rows) { frame.luma(it % WIDTH, top + it / WIDTH).toDouble() }

  // An edge inside the left bar's cover between rows the crop keeps, and a darker grey on the rows it cuts.
  private fun tallAt(
    x: Double,
    y: Double,
  ): DoubleArray =
    DoubleArray(3) {
      when {
        y < CUT || y > 1.0 - CUT -> DARK
        x < EDGE -> LIGHT
        else -> MID
      }
    }

  private companion object {
    const val WIDTH = 384
    const val HEIGHT = 216

    // Twice the output's height, so it is contained 192 wide and its cover crops a quarter off the top and bottom.
    const val TALL_HEIGHT = 432
    const val CROP_TOP = 108
    const val CUT = 0.25
    const val EDGE = 0.125

    // A sigma of 10.8 output pixels, which the plan boxes down by 2 onto whole pixels.
    const val RADIUS = 0.05f

    // The first slot of the tall clip, after the wide one's two seconds.
    const val TALL_SLOT = 2 * FILTER_RATE + 1
    val BAR_COLUMNS = (0 until 96) + (288 until WIDTH)

    const val LIGHT = 0.875
    const val MID = 0.5
    const val DARK = 0.125

    // gblur's recursive filter and the rounding of each step, both ffmpeg's limits, against the plan's exact kernel.
    // The same reach as a GaussianBlur's. Measured at 1.69 on 8.0.1.
    const val FILL_CODES = 2.5

    // A blur of an edge over 90 codes tall at this sigma moves the pixels beside it by far more.
    const val SHARP_SPREAD = 30.0
  }
}

package dev.jordond.filmstrip.ffmpeg.support

import dev.jordond.filmstrip.effects.filters.camcorder.OsdRaster
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import kotlin.math.abs
import kotlin.math.roundToInt

// Reads a frame ffmpeg draws for a Camcorder against the display CamcorderOsd.rasterAt draws. Luma lands pixel for
// pixel, and chroma is read only where all four pixels of a sample share one colour, since 4:2:0 holds their mean.

// Every lit pixel of [raster] on its own luma and every clear one on [picture]'s, with at least one pixel lit.
internal fun assertOnCamcorderRaster(
  frame: Yuv420Frame,
  picture: Yuv420Frame,
  raster: OsdRaster,
) {
  var lit = 0
  for (y in 0 until raster.height) {
    for (x in 0 until raster.width) {
      val argb = raster.argbAt(x, y)
      val expected = if (argb == CAMCORDER_CLEAR) picture.luma(x, y) else lumaCodeOf(argb).also { lit++ }
      if (abs(frame.luma(x, y) - expected) > CAMCORDER_LUMA_CODES) {
        withClue("($x, $y) of a pixel the raster holds as ${argb.toUInt().toString(HEX)}") {
          abs(frame.luma(x, y) - expected) shouldBeLessThanOrEqual CAMCORDER_LUMA_CODES
        }
      }
    }
  }
  withClue("the raster has to light a pixel for this to tell") { lit shouldBeGreaterThan 0 }
}

// The limited range BT.709 luma code of an 8 bit full range colour.
internal fun lumaCodeOf(argb: Int): Int {
  val (red, green, blue) = rgbChannelsOf(argb)
  return (LUMA_FLOOR + (KR * red + (1 - KR - KB) * green + KB * blue) * LUMA_SPAN / FULL).roundToInt()
}

// The limited range BT.709 Cb and Cr codes of an 8 bit full range colour.
internal fun chromaCodesOf(argb: Int): IntArray {
  val (red, green, blue) = rgbChannelsOf(argb)
  val luma = KR * red + (1 - KR - KB) * green + KB * blue
  val cb = (blue - luma) / (2 * (1 - KB))
  val cr = (red - luma) / (2 * (1 - KR))
  return intArrayOf(
    (CHROMA_ZERO + cb * CHROMA_SPAN / FULL).roundToInt(),
    (CHROMA_ZERO + cr * CHROMA_SPAN / FULL).roundToInt(),
  )
}

// The chroma samples of [raster] whose pixels, out to the reach of the scaler's taps round them, all hold [colour], as
// column and row of the sample.
internal fun samplesWhollyOf(
  raster: OsdRaster,
  colour: Int,
): List<Pair<Int, Int>> {
  val reach = -SCALER_REACH..1 + SCALER_REACH
  return (SCALER_REACH until raster.height / 2 - SCALER_REACH).flatMap { row ->
    (SCALER_REACH until raster.width / 2 - SCALER_REACH).mapNotNull { column ->
      val whole = reach.all { dy -> reach.all { dx -> raster.argbAt(column * 2 + dx, row * 2 + dy) == colour } }
      if (whole) column to row else null
    }
  }
}

private fun rgbChannelsOf(argb: Int): Triple<Double, Double, Double> =
  Triple((argb shr 16 and BYTE).toDouble(), (argb shr 8 and BYTE).toDouble(), (argb and BYTE).toDouble())

internal const val CAMCORDER_CLEAR = 0

// Each way through swscale rounds to the nearest code.
internal const val CAMCORDER_LUMA_CODES = 1

private const val KR = 0.2126
private const val KB = 0.0722
private const val LUMA_FLOOR = 16.0
private const val LUMA_SPAN = 219.0
private const val CHROMA_ZERO = 128.0
private const val CHROMA_SPAN = 224.0
private const val HEX = 16

// swscale halves chroma with its bicubic filter, which reads about four pixels each side of a sample.
private const val SCALER_REACH = 5

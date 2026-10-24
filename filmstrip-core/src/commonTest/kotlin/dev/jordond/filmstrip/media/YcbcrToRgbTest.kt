package dev.jordond.filmstrip.media

import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Encodes mid-range colours into 8, 10 and 12-bit samples with the forward equations of ITU-T H.273 section 8.3,
 * written out here on their own, and checks [ycbcrToRgb] and [rgbMatrix] bring each one back.
 */
class YcbcrToRgbTest {
  @Test
  fun `mid-range samples of every reading decode back at 8 10 and 12 bits`() {
    for (reading in READINGS) {
      for (bitDepth in BIT_DEPTHS) {
        for (fullRange in listOf(false, true)) {
          val max = ((1 shl bitDepth) - 1).toDouble()
          for (colour in MID_RANGE) {
            val (y, cb, cr) = forward(reading, colour, bitDepth, fullRange)
            val decoded = ycbcrToRgb(reading, y, cb, cr, bitDepth, fullRange)
            for (channel in 0 until 3) {
              val error = abs(decoded[channel] - colour[channel]) * max
              assertTrue(error <= MID_RANGE_CODES, "$reading $bitDepth-bit full $fullRange $channel off by $error")
            }
          }
        }
      }
    }
  }

  @Test
  fun `a mid-range sample lands elsewhere through another reading`() {
    val bt709 = ycbcrReading(1, 2, 2)
    for (bitDepth in BIT_DEPTHS) {
      for (fullRange in listOf(false, true)) {
        for (other in listOf(0, 6, 8, 9, 10).map { ycbcrReading(it, 9, 14) }) {
          val colour = MID_RANGE.first()
          val (y, cb, cr) = forward(bt709, colour, bitDepth, fullRange)
          val decoded = ycbcrToRgb(other, y, cb, cr, bitDepth, fullRange)
          val error = (0 until 3).maxOf { abs(decoded[it] - colour[it]) * 255 }
          assertTrue(error > 2.0, "$other reads BT.709 samples within $error 8-bit codes")
        }
      }
    }
  }

  @Test
  fun `BT2020 constant luminance divisors match BT2020 Table 4`() {
    val divisors = (ycbcrReading(10, 9, 14) as YcbcrReading.ConstantLuminance).divisors()
    abs(2 * divisors.nb - 1.9404) shouldBeAtMost 0.0005
    abs(2 * divisors.pb - 1.5816) shouldBeAtMost 0.0005
    abs(2 * divisors.nr - 1.7184) shouldBeAtMost 0.0005
    abs(2 * divisors.pr - 0.9936) shouldBeAtMost 0.0005
  }

  @Test
  fun `constant luminance round trips across the cube at 10 and 12 bits`() {
    for (reading in listOf(ycbcrReading(10, 9, 14), ycbcrReading(13, 1, 1), ycbcrReading(13, 12, 15))) {
      for (bitDepth in listOf(10, 12)) {
        for (fullRange in listOf(false, true)) {
          val max = ((1 shl bitDepth) - 1).toDouble()
          var worst = 0.0
          for (colour in cube(8)) {
            val (y, cb, cr) = forward(reading, colour, bitDepth, fullRange)
            val decoded = ycbcrToRgb(reading, y, cb, cr, bitDepth, fullRange)
            worst = maxOf(worst, (0 until 3).maxOf { abs(decoded[it] - colour[it]) * max })
          }
          assertTrue(worst <= CL_CUBE_CODES, "$reading $bitDepth-bit full $fullRange off by $worst codes")
        }
      }
    }
  }

  @Test
  fun `constant luminance inverts unrounded samples on every curve`() {
    for (transfer in listOf(1, 4, 5, 7, 8, 13, 17)) {
      val reading = ycbcrReading(10, 9, transfer)
      for (fullRange in listOf(false, true)) {
        val range = sampleRange(10, fullRange)
        for (colour in cube(6)) {
          val samples = unrounded(reading, colour, 10, fullRange)
          val decoded = YcbcrEvaluator(reading, range).rgb(samples[0], samples[1], samples[2])
          for (channel in 0 until 3) abs(decoded[channel] - colour[channel]) shouldBeAtMost 1e-5
        }
      }
    }
  }

  // Half a code rounding each channel to a whole code, then up to a code and a half rounding Y, Cg and Co.
  @Test
  fun `YCgCo round trips on whole codes within two codes`() {
    for (bitDepth in BIT_DEPTHS) {
      for (fullRange in listOf(false, true)) {
        val span = sampleRange(bitDepth, fullRange).lumaSpan
        for (colour in cube(6)) {
          val (y, cb, cr) = forward(YcbcrReading.YCgCo, colour, bitDepth, fullRange)
          val decoded = ycbcrToRgb(YcbcrReading.YCgCo, y, cb, cr, bitDepth, fullRange)
          for (channel in 0 until 3) {
            val error = abs(decoded[channel] - colour[channel]) * span
            assertTrue(error <= 2.0, "YCgCo $bitDepth-bit full $fullRange off by $error")
          }
        }
      }
    }
  }

  @Test
  fun `the linear matrix is the decode before its clamp`() {
    for (reading in READINGS.filter { it !is YcbcrReading.ConstantLuminance }) {
      for (bitDepth in BIT_DEPTHS) {
        for (fullRange in listOf(false, true)) {
          val matrix = assertNotNull(reading.rgbMatrix(bitDepth, fullRange))
          for (colour in MID_RANGE) {
            val (y, cb, cr) = forward(reading, colour, bitDepth, fullRange)
            val linear = matrix.rgb(y.toDouble(), cb.toDouble(), cr.toDouble())
            val decoded = ycbcrToRgb(reading, y, cb, cr, bitDepth, fullRange)
            for (channel in 0 until 3) abs(linear[channel] - decoded[channel]) shouldBeAtMost 1e-12
          }
        }
      }
    }
    assertNull(ycbcrReading(10, 9, 14).rgbMatrix(10, false))
    assertNull(ycbcrReading(14, 9, 16).rgbMatrix(10, false))
  }

  @Test
  fun `a prepared evaluator decodes as ycbcrToRgb does into the array it is handed`() {
    for (reading in READINGS) {
      for (bitDepth in BIT_DEPTHS) {
        for (fullRange in listOf(false, true)) {
          val evaluator = reading.evaluator(bitDepth, fullRange)
          val into = DoubleArray(3)
          for (colour in MID_RANGE) {
            val (y, cb, cr) = forward(reading, colour, bitDepth, fullRange)
            assertSame(into, evaluator.rgb(y, cb, cr, into))
            into.toList() shouldBe ycbcrToRgb(reading, y, cb, cr, bitDepth, fullRange).toList()
          }
        }
      }
    }
  }

  @Test
  fun `a refused reading or a depth outside 8 to 16 throws`() {
    assertFailsWith<IllegalArgumentException> { ycbcrToRgb(ycbcrReading(14, 9, 16), 64, 512, 512, 10, false) }
    assertFailsWith<IllegalArgumentException> { ycbcrReading(10, 9, 16).evaluator(10, false) }
    assertFailsWith<IllegalArgumentException> { ycbcrToRgb(ycbcrReading(1, 1, 1), 64, 128, 128, 7, false) }
    assertFailsWith<IllegalArgumentException> { ycbcrReading(1, 1, 1).rgbMatrix(17, true) }
  }

  // H.273 section 8.3 forward from red, green and blue in zero to one to rounded samples of bitDepth bits. YCgCo is
  // built from whole red, green and blue codes, as H.273 builds it.
  private fun forward(
    reading: YcbcrReading,
    rgb: DoubleArray,
    bitDepth: Int,
    fullRange: Boolean,
  ): Triple<Int, Int, Int> {
    if (reading == YcbcrReading.YCgCo) {
      val middle = 1 shl (bitDepth - 1)
      val (green, blue, red) = unrounded(YcbcrReading.Identity, rgb, bitDepth, fullRange).map { it.roundToInt() }
      return Triple(
        (0.5 * green + 0.25 * (red + blue)).roundToInt(),
        (0.5 * green - 0.25 * (red + blue)).roundToInt() + middle,
        (0.5 * (red - blue)).roundToInt() + middle,
      )
    }
    val (y, cb, cr) = unrounded(reading, rgb, bitDepth, fullRange).map { it.roundToInt() }
    return Triple(y, cb, cr)
  }

  // H.273 section 8.3 forward to samples of bitDepth bits left unrounded, for every reading but YCgCo.
  private fun unrounded(
    reading: YcbcrReading,
    rgb: DoubleArray,
    bitDepth: Int,
    fullRange: Boolean,
  ): List<Double> {
    val max = ((1 shl bitDepth) - 1).toDouble()
    val scale = (1 shl (bitDepth - 8)).toDouble()
    val middle = (1 shl (bitDepth - 1)).toDouble()

    fun luma(value: Double) = if (fullRange) max * value else scale * (219 * value + 16)

    fun chroma(value: Double) = if (fullRange) max * value + middle else scale * (224 * value + 128)
    val (r, g, b) = rgb.toList()
    return when (reading) {
      YcbcrReading.Identity -> {
        listOf(luma(g), luma(b), luma(r))
      }
      is YcbcrReading.KrKb -> {
        val (kr, kb) = reading
        val y = kr * r + (1 - kr - kb) * g + kb * b
        listOf(luma(y), chroma((b - y) / (2 * (1 - kb))), chroma((r - y) / (2 * (1 - kr))))
      }
      is YcbcrReading.ConstantLuminance -> {
        val (kr, kb) = reading
        val curve = reading.oetf
        val y = curve.encode(kr * curve.decode(r) + (1 - kr - kb) * curve.decode(g) + kb * curve.decode(b))
        val pb = 1 - curve.encode(kb)
        val nb = curve.encode(1 - kb)
        val pr = 1 - curve.encode(kr)
        val nr = curve.encode(1 - kr)
        listOf(
          luma(y),
          chroma((b - y) / (2 * if (b - y <= 0) nb else pb)),
          chroma((r - y) / (2 * if (r - y <= 0) nr else pr)),
        )
      }
      else -> {
        error("no unrounded forward for $reading")
      }
    }
  }

  private fun cube(steps: Int): List<DoubleArray> =
    (0..steps).flatMap { r ->
      (0..steps).flatMap { g ->
        (0..steps).map { b -> doubleArrayOf(r.toDouble() / steps, g.toDouble() / steps, b.toDouble() / steps) }
      }
    }

  private infix fun Double.shouldBeAtMost(limit: Double) = assertTrue(this <= limit, "$this is past $limit")

  private companion object {
    val BIT_DEPTHS = listOf(8, 10, 12)
    const val MID_RANGE_CODES = 2.0
    const val CL_CUBE_CODES = 5.0

    // Colours away from every end of the cube, so a reading that scales or weights a channel wrongly shows.
    val MID_RANGE =
      listOf(
        doubleArrayOf(0.3, 0.55, 0.7),
        doubleArrayOf(0.62, 0.41, 0.18),
        doubleArrayOf(0.5, 0.5, 0.5),
        doubleArrayOf(0.45, 0.2, 0.35),
      )

    val READINGS: List<YcbcrReading> =
      listOf(0, 1, 4, 6, 7, 8, 9, 10).map { ycbcrReading(it, 9, 14) } + ycbcrReading(13, 1, 1) + ycbcrReading(12, 6, 1)
  }
}

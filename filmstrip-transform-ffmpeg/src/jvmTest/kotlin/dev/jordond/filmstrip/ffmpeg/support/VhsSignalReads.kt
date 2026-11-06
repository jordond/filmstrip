package dev.jordond.filmstrip.ffmpeg.support

import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.effects.filters.vhs.VhsParams
import dev.jordond.filmstrip.effects.filters.vhs.VhsSignal
import io.kotest.assertions.withClue
import kotlin.math.abs
import kotlin.time.DurationUnit
import io.kotest.matchers.doubles.shouldBeLessThan as shouldBeBelow

// Reads a frame ffmpeg draws for a Vhs against VhsSignal. A chroma plane in 4:2:0 draws its own rows, so a pixel's
// chroma is read against the virtual row and the column of the sample it comes from, and its luma against its own.

internal fun lumaPlaneOf(
  vhs: Vhs,
  tick: Int,
  column: Int,
  y: Int,
  source: (Double) -> DoubleArray,
): Double {
  val x = (column + 0.5) / VHS_WIDTH
  val row = VhsSignal.virtualRow(y, VHS_HEIGHT)
  val ycc = yccOf(VhsSignal.pixelAt(x, row, tick, vhs.seed, VHS_WIDTH, VhsParams.of(vhs), source))
  return VHS_LUMA_BLACK + VHS_LUMA_SPAN * ycc[0]
}

internal fun chromaPlanesOf(
  vhs: Vhs,
  tick: Int,
  column: Int,
  planeRow: Int,
  source: (Double) -> DoubleArray,
): DoubleArray {
  val x = (column + 0.5) / (VHS_WIDTH / 2)
  val row = VhsSignal.virtualRow(planeRow, VHS_HEIGHT / 2)
  val ycc = yccOf(VhsSignal.pixelAt(x, row, tick, vhs.seed, VHS_WIDTH, VhsParams.of(vhs), source))
  return doubleArrayOf(VHS_CHROMA_ZERO + VHS_CHROMA_SPAN * ycc[1], VHS_CHROMA_ZERO + VHS_CHROMA_SPAN * ycc[2])
}

internal fun assertReadsSignal(
  frame: ByteArray,
  vhs: Vhs,
  width: Int,
  height: Int,
  slot: Int,
  source: (Double) -> DoubleArray,
  lumaCodes: Double = VHS_LUMA_CODES,
) {
  val tick = tickOf(slot)
  val rows = (0 until height step height / VHS_ROWS_READ) + (height - 1)
  // The columns on the step to black read in planes in their own test, against gblur's limit on a step. A read
  // through R'G'B' there would clip blue where luma and chroma fade at different rates.
  val columns = (width / VHS_COLUMNS_READ until width step width / VHS_COLUMNS_READ)
  rows.forEach { y ->
    columns.forEach { column ->
      val read = yccOf(readRgb(frame, width, column, y))
      val luma = yccOf(lumaSampleSignal(vhs, tick, column, y, width, height, source))
      val chroma = yccOf(chromaSampleSignal(vhs, tick, column, y, width, height, source))
      withClue("row $y column $column") {
        abs(read[0] - luma[0]) shouldBeBelow lumaCodes
        abs(read[1] - chroma[1]) shouldBeBelow VHS_CHROMA_CODES
        abs(read[2] - chroma[2]) shouldBeBelow VHS_CHROMA_CODES
      }
    }
  }
}

internal fun tickOf(slot: Int): Int =
  VhsSignal.tickAt(outputSlotTime(slot.toLong(), VHS_RATE).toDouble(DurationUnit.SECONDS))

internal fun readRgb(
  frame: ByteArray,
  width: Int,
  x: Int,
  y: Int,
): DoubleArray = DoubleArray(3) { frame.channelAt(width, x, y, it).toDouble() }

internal fun lumaSampleSignal(
  vhs: Vhs,
  tick: Int,
  column: Int,
  y: Int,
  width: Int,
  height: Int,
  source: (Double) -> DoubleArray,
): DoubleArray {
  val row = VhsSignal.virtualRow(y, height)
  val x = (column + 0.5) / width
  return VhsSignal.pixelAt(x, row, tick, vhs.seed, width, VhsParams.of(vhs), source).scaled()
}

// The sample of a 4:2:0 chroma plane this pixel takes its chroma from, read at that plane's own row.
internal fun chromaSampleSignal(
  vhs: Vhs,
  tick: Int,
  column: Int,
  y: Int,
  width: Int,
  height: Int,
  source: (Double) -> DoubleArray,
): DoubleArray {
  val row = VhsSignal.virtualRow(y / 2, height / 2)
  val x = (column / 2 + 0.5) / (width / 2)
  return VhsSignal.pixelAt(x, row, tick, vhs.seed, width, VhsParams.of(vhs), source).scaled()
}

private fun DoubleArray.scaled(): DoubleArray = DoubleArray(size) { this[it] * FULL }

internal fun yccOf(rgb: DoubleArray): DoubleArray {
  val y = VHS_KR * rgb[0] + (1.0 - VHS_KR - VHS_KB) * rgb[1] + VHS_KB * rgb[2]
  return doubleArrayOf(y, (rgb[2] - y) / (2.0 * (1.0 - VHS_KB)), (rgb[0] - y) / (2.0 * (1.0 - VHS_KR)))
}

// A mid-range ramp with blue running against the other two, so nothing saturates and a shift reads in both.
internal fun rampAt(x: Double): DoubleArray {
  val code = VHS_RAMP_LOW + x.coerceIn(0.0, 1.0) * VHS_RAMP_SPAN
  return doubleArrayOf(code, code, 1.0 - code)
}

internal const val VHS_WIDTH = 640
internal const val VHS_HEIGHT = 360
internal const val VHS_ROWS_READ = 72
internal const val VHS_COLUMNS_READ = 16
internal const val VHS_RAMP_LOW = 0.25
internal const val VHS_RAMP_SPAN = 0.5
internal const val VHS_KR = 0.2126
internal const val VHS_KB = 0.0722
internal const val VHS_LUMA_BLACK = 16.0
internal const val VHS_LUMA_SPAN = 219.0
internal const val VHS_CHROMA_ZERO = 128
internal const val VHS_CHROMA_SPAN = 224.0

// An 8 bit frame's limits against the signal, in the planes the frame holds. This reads RGBA, so swscale's rounding
// back to RGB is in it and moves with the architecture. Against the same frame's planes it alone reads up to 2.4 codes
// off in luma and 0.8 in chroma on x86_64, 1.2 and 0.6 on arm64. A ramp with no Vhs at all already reads 2.9 codes off
// in luma and 1.4 in chroma, through RGB, limited range 4:2:0 and back. With the Vhs the most measured is 3.7 in luma
// and 1.9 in chroma. One RGB channel adds a luma error to a scaled chroma one.
internal const val VHS_LUMA_CODES = 4.0
internal const val VHS_CHROMA_CODES = 2.5

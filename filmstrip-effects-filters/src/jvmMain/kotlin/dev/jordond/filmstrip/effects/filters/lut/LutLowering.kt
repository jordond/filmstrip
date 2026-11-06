package dev.jordond.filmstrip.effects.filters.lut

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.Sidecar
import dev.jordond.filmstrip.effects.filters.internal.RgbRoundTrip
import kotlin.math.roundToLong

/**
 * The filter chain that draws a [Lut] on ffmpeg, through `lut3d` reading the table as a sidecar.
 *
 * It runs on straight `gbrap`, since the table reads one pixel at a time and `lut3d` carries alpha through as it read.
 * The conversions each way name their matrix and range, so nothing is left to the scaler `lut3d` would insert.
 *
 * `lut3d` drops the fraction of the 8 bit code it interpolates, so the sidecar holds every entry [LUT3D_ROUNDING]
 * higher and the drop lands on the nearest code.
 */
@OptIn(InternalFilmstripApi::class)
internal fun lutFragment(lut: Lut): FilterFragment {
  val sidecar = Sidecar(cubeText(lut.table.blended(lut.intensity), LUT3D_ROUNDING).encodeToByteArray(), "cube")
  return FilterFragment(
    chain =
      RgbRoundTrip.opening() + RgbRoundTrip.toRgb() +
        FilterNode("lut3d", "file" to sidecar.placeholder, "interp" to "trilinear") +
        RgbRoundTrip.toCodes(),
    sidecars = listOf(sidecar),
  )
}

/**
 * [table] as the text of a `.cube` file with a domain of `0` to `1`, at finer steps than the 16 bits a table holds.
 *
 * Every entry is written [bias] higher, which can pass `1` at the top of the table.
 */
internal fun cubeText(
  table: CubeLut,
  bias: Double = 0.0,
): String =
  buildString(table.entries.size * NUMBER_CHARS + HEADER_CHARS) {
    append("LUT_3D_SIZE ").append(table.size).append('\n')
    table.entries.forEachIndexed { index, entry ->
      appendFixed(entry + bias)
      append(if ((index + 1) % CHANNELS == 0) '\n' else ' ')
    }
  }

// Written by hand since a table holds millions of numbers, and always with a point and no exponent.
private fun StringBuilder.appendFixed(entry: Double) {
  val scaled = (entry * FIXED_SCALE).roundToLong()
  append(scaled / FIXED_SCALE).append('.')
  val fraction = (scaled % FIXED_SCALE).toString()
  repeat(FIXED_DIGITS - fraction.length) { append('0') }
  append(fraction)
}

// Half of an 8 bit code, as `lut3d` reads an entry.
internal const val LUT3D_ROUNDING = 0.5 / 255.0

private const val FIXED_DIGITS = 7
private const val FIXED_SCALE = 10_000_000L
private const val NUMBER_CHARS = 10
private const val HEADER_CHARS = 32

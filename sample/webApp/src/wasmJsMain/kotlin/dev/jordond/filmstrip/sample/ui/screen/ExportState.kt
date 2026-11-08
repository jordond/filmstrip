package dev.jordond.filmstrip.sample.ui.screen

import kotlin.math.roundToInt

/**
 * How many cells the export's progress bar is made of.
 */
internal const val EXPORT_CELLS: Int = 16

/**
 * How many progress cells a run [fraction] of the way through lights, to the nearest cell. A figure that is not a
 * number lights none.
 */
internal fun litCells(fraction: Float): Int =
  if (fraction.isNaN()) 0 else (fraction * EXPORT_CELLS).roundToInt().coerceIn(0, EXPORT_CELLS)

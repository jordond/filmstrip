package dev.jordond.filmstrip.sample

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jordond.filmstrip.effects.filters.pixel.Pixel
import dev.jordond.filmstrip.effects.filters.pixel.PixelPalette

/**
 * The pixel look the editor draws, its cells, levels, dither and palette.
 *
 * The sliders hold floats, and [levelCount] reads the levels slider the way a [Pixel] takes it.
 */
@Stable
public class PixelState {
  public var enabled: Boolean by mutableStateOf(false)
  public var cells: Float by mutableStateOf(DEFAULT_CELLS)
  public var levels: Float by mutableStateOf(0f)
  public var dither: Float by mutableStateOf(0f)
  public var palette: PixelPalette? by mutableStateOf(null)

  /**
   * The levels slider as a [Pixel] reads it, with anything under two turning levels off.
   */
  public val levelCount: Int get() = levels.toInt().takeIf { it >= Pixel.MIN_LEVELS } ?: 0

  internal fun reset() {
    enabled = false
    cells = DEFAULT_CELLS
    levels = 0f
    dither = 0f
    palette = null
  }

  private companion object {
    const val DEFAULT_CELLS = Pixel.DEFAULT_CELLS.toFloat()
  }
}

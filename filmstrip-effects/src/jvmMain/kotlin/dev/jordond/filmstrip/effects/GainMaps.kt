package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.Sidecar
import dev.jordond.filmstrip.geometry.Size
import java.awt.image.BufferedImage
import java.awt.image.DataBufferUShort
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * Sixteen bit gray PNG sidecars holding a gain between zero and one at every pixel of a frame, which `blend` multiplies
 * a frame by.
 *
 * Each map is drawn and encoded once for its shape and frame size and kept for the most recent [CAPACITY] of them, so
 * a preview that resolves the same look on every seek reads it back instead of drawing it again. Safe to call from any
 * thread.
 */
@InternalFilmstripApi
public object GainMaps {
  private val maps =
    object : LinkedHashMap<Pair<Any, Size>, Sidecar>(CAPACITY, LOAD_FACTOR, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Any, Size>, Sidecar>): Boolean =
        size > CAPACITY
    }

  /**
   * The map of [gainAt] over a [frame], read at each pixel's centre as a fraction of the width and of the height.
   *
   * [shape] is equal for two calls exactly when their [gainAt] draws the same map, so it holds every figure [gainAt]
   * reads, and the map drawn for the first is handed back for the second.
   */
  public fun of(
    shape: Any,
    frame: Size,
    gainAt: (x: Double, y: Double) -> Double,
  ): Sidecar {
    val key = shape to frame
    synchronized(maps) { maps[key]?.let { return it } }
    val drawn = draw(frame, gainAt)
    return synchronized(maps) { maps.getOrPut(key) { drawn } }
  }

  /**
   * The nodes that open a map [of] drew as a frame held from [AHEAD_OF_THE_CLIP], which a branch with no input starts
   * on, so a `blend` reading it gains a clip frame that lands before zero too.
   */
  public fun read(map: Sidecar): List<FilterNode> =
    listOf(FilterNode("movie", "filename" to map.placeholder, "f" to "png_pipe"), AHEAD_OF_THE_CLIP)

  private fun draw(
    frame: Size,
    gainAt: (x: Double, y: Double) -> Double,
  ): Sidecar {
    val image = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_USHORT_GRAY)
    val samples = (image.raster.dataBuffer as DataBufferUShort).data
    for (y in 0 until frame.height) {
      for (x in 0 until frame.width) {
        val gain = gainAt((x + CENTRE) / frame.width, (y + CENTRE) / frame.height).coerceIn(0.0, 1.0)
        samples[y * frame.width + x] = (gain * FULL).roundToInt().toShort()
      }
    }
    val bytes = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    return Sidecar(bytes, "png")
  }

  /**
   * How many maps are kept.
   */
  public const val CAPACITY: Int = 8

  /**
   * The sample a gain of one is stored as.
   */
  public const val FULL: Int = 65535

  private const val LOAD_FACTOR = 0.75f
  private const val CENTRE = 0.5
}

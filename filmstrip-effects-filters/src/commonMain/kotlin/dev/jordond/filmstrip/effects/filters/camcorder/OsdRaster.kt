package dev.jordond.filmstrip.effects.filters.camcorder

import dev.jordond.filmstrip.InternalFilmstripApi
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/**
 * Works out what a [Camcorder] shows and draws it, the one place every backend reads its display from.
 *
 * @property spec The display being drawn.
 * @param frameRate The output frame rate a counter counts frames at, or null when the output does not say.
 */
@InternalFilmstripApi
public class CamcorderOsd(
  public val spec: Camcorder,
  frameRate: Float?,
) {
  internal val rate: Double = frameRate?.toDouble()?.takeIf { it > 0.0 } ?: OsdParams.FALLBACK_FRAME_RATE

  // The display holds still between ticks of OsdParams.STATE_CLOCK_MICROS, so every change lands on that clock.
  internal fun stateAt(time: Duration): OsdState = osdStateAt(spec, onStateClock(time.inWholeMicroseconds), rate)

  /**
   * Draws the display at composition [time] over a clear frame of [width] by [height] pixels.
   */
  public fun rasterAt(
    time: Duration,
    width: Int,
    height: Int,
  ): OsdRaster = rasterOf(stateAt(time), width, height)

  // The lines of the display whose last run is a frame count.
  internal val frameCountLines: List<Int> =
    spec.elements.indices.filter { (spec.elements[it] as? OsdElement.Counter)?.withFrames == true }

  // The part of the display inside [window], a region of a [width] by [height] frame. The frame counts draw first and
  // every other line over them, so where lines overlap a count the line wins.
  internal fun rasterOf(
    state: OsdState,
    width: Int,
    height: Int,
    window: PixelRect = PixelRect(0, 0, width, height),
  ): OsdRaster {
    val raster = OsdRaster(window.width, window.height, IntArray(window.width * window.height), window.left, window.top)
    val layout = osdLayout(width, height, spec.textSize)
    if (frameCountLines.isNotEmpty()) {
      placed(framesOnly(state, frameCountLines), layout) { _, line, origin -> raster.draw(line, origin) }
    }
    placed(framesCleared(state, frameCountLines), layout) { _, line, origin -> raster.draw(line, origin) }
    return raster
  }

  // The pixels each line of [state] can touch, edge included, in the order the lines are declared.
  internal fun lineBounds(
    state: OsdState,
    width: Int,
    height: Int,
  ): List<PixelRect> {
    val layout = osdLayout(width, height, spec.textSize)
    val bounds = arrayOfNulls<PixelRect>(state.lines.size)
    placed(state, layout) { index, line, origin ->
      bounds[index] =
        PixelRect(
          left = max(0, floor(origin.x - origin.dot).toInt()),
          top = max(0, floor(origin.y - origin.dot).toInt()),
          right = min(width, ceil(origin.x + (lineDots(line) + 1) * origin.dot).toInt()),
          bottom = min(height, ceil(origin.y + (OsdFont.HEIGHT + 1) * origin.dot).toInt()),
        )
    }
    return bounds.map { checkNotNull(it) }
  }

  // The pixels of a [width] by [height] frame any line that differs between [before] and [after] touches in either, or
  // null when none of them touch the frame.
  internal fun changedRegion(
    before: OsdState,
    after: OsdState,
    width: Int,
    height: Int,
  ): PixelRect? = changedRegion(before, lineBounds(before, width, height), after, lineBounds(after, width, height))

  // The pixels the characters [chars] of line [line] can touch, edge included, counted along the line's whole text.
  internal fun charBounds(
    state: OsdState,
    width: Int,
    height: Int,
    line: Int,
    chars: IntRange,
  ): PixelRect {
    val layout = osdLayout(width, height, spec.textSize)
    var bounds: PixelRect? = null
    placed(state, layout) { index, placedLine, origin ->
      if (index != line) return@placed
      val text = placedLine.runs.joinToString("") { it.text }
      val before = { at: Int -> text.take(at).sumOf { OsdFont.glyph(it).width + OsdParams.GLYPH_GAP_DOTS } }
      val end = before(chars.last) + OsdFont.glyph(text[chars.last]).width
      bounds =
        PixelRect(
          left = max(0, floor(origin.x + (before(chars.first) - 1) * origin.dot).toInt()),
          top = max(0, floor(origin.y - origin.dot).toInt()),
          right = min(width, ceil(origin.x + (end + 1) * origin.dot).toInt()),
          bottom = min(height, ceil(origin.y + (OsdFont.HEIGHT + 1) * origin.dot).toInt()),
        )
    }
    return checkNotNull(bounds)
  }

  // Hands over each line of [state] with its index and where it lands, stacked in its corner in declared order.
  private inline fun placed(
    state: OsdState,
    layout: OsdLayout,
    each: (Int, OsdLine, DotOrigin) -> Unit,
  ) {
    val counts = state.lines.groupingBy { it.corner }.eachCount()
    val stacked = mutableMapOf<OsdCorner, Int>()
    state.lines.forEachIndexed { index, line ->
      val row = stacked[line.corner] ?: 0
      stacked[line.corner] = row + 1
      each(index, line, layout.originOf(line.corner, row, counts.getValue(line.corner), line))
    }
  }
}

// A region of a frame in pixels, [left] and [top] inside it and [right] and [bottom] past it.
internal data class PixelRect(
  val left: Int,
  val top: Int,
  val right: Int,
  val bottom: Int,
) {
  val width: Int get() = right - left
  val height: Int get() = bottom - top

  // True when no pixel of the frame is inside, as a region clamped to a frame is when it lies past an edge.
  val isEmpty: Boolean get() = width <= 0 || height <= 0

  fun union(other: PixelRect): PixelRect =
    PixelRect(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))
}

// The pixels any line that differs between two states touches in either of them, or null when none of them touch the
// frame.
internal fun changedRegion(
  before: OsdState,
  beforeBounds: List<PixelRect>,
  after: OsdState,
  afterBounds: List<PixelRect>,
): PixelRect? =
  after.lines.indices
    .filter { before.lines[it] != after.lines[it] }
    .flatMap { listOf(beforeBounds[it], afterBounds[it]) }
    .filterNot { it.isEmpty }
    .reduceOrNull(PixelRect::union)

// A time in microseconds moved back onto the clock the display changes on.
internal fun onStateClock(micros: Long): Duration =
  (micros.coerceAtLeast(0) / OsdParams.STATE_CLOCK_MICROS * OsdParams.STATE_CLOCK_MICROS).microseconds

/**
 * A frame of a [Camcorder] display in straight ARGB, row major from the top left.
 *
 * Every pixel is fully opaque or fully clear, so it is its own premultiplied form.
 *
 * @property width The width in pixels.
 * @property height The height in pixels.
 */
@InternalFilmstripApi
public class OsdRaster internal constructor(
  public val width: Int,
  public val height: Int,
  internal val pixels: IntArray,
  internal val left: Int = 0,
  internal val top: Int = 0,
) {
  /**
   * The ARGB colour at column [x] and row [y], counted from the top left.
   */
  public fun argbAt(
    x: Int,
    y: Int,
  ): Int = pixels[y * width + x]

  internal fun draw(
    line: OsdLine,
    origin: DotOrigin,
  ) {
    val dots = LineDots(line)
    for (gy in -1..OsdFont.HEIGHT) {
      for (gx in -1..dots.width) {
        if (dots.colourAt(gx, gy) == HIDDEN && dots.touchesLit(gx, gy)) fill(origin, gx, gy, OsdParams.EDGE_COLOUR)
      }
    }
    for (gy in 0 until OsdFont.HEIGHT) {
      for (gx in 0 until dots.width) {
        val colour = dots.colourAt(gx, gy)
        if (colour != HIDDEN) fill(origin, gx, gy, colour)
      }
    }
  }

  // Paints every pixel whose centre falls inside the dot, which is nearest sampling of the font grid at any size.
  private fun fill(
    origin: DotOrigin,
    gx: Int,
    gy: Int,
    colour: Int,
  ) {
    val x0 = max(0, firstPixel(origin.x + gx * origin.dot) - left)
    val x1 = min(width, firstPixel(origin.x + (gx + 1) * origin.dot) - left)
    val y0 = max(0, firstPixel(origin.y + gy * origin.dot) - top)
    val y1 = min(height, firstPixel(origin.y + (gy + 1) * origin.dot) - top)
    if (x0 >= x1) return
    for (y in y0 until y1) pixels.fill(colour, y * width + x0, y * width + x1)
  }

  private fun firstPixel(edge: Double): Int = ceil(edge - PIXEL_CENTRE).toInt()
}

// Where a camcorder display sits in a frame, in pixels. Everything is a fraction of the frame, so two sizes of the same
// shape land the same fractions.
internal class OsdLayout(
  val dot: Double,
  val left: Double,
  val right: Double,
  val top: Double,
  val bottom: Double,
) {
  val pitch: Double get() = OsdParams.LINE_PITCH_DOTS * dot

  fun originOf(
    corner: OsdCorner,
    index: Int,
    count: Int,
    line: OsdLine,
  ): DotOrigin {
    val lineWidth = lineDots(line) * dot
    val x =
      when (corner) {
        OsdCorner.TopLeft, OsdCorner.BottomLeft -> left
        OsdCorner.TopRight, OsdCorner.BottomRight -> right - lineWidth
      }
    val y =
      when (corner) {
        OsdCorner.TopLeft, OsdCorner.TopRight -> top + index * pitch
        OsdCorner.BottomLeft, OsdCorner.BottomRight -> bottom - OsdFont.HEIGHT * dot - (count - 1 - index) * pitch
      }
    return DotOrigin(x, y, dot)
  }
}

internal fun osdLayout(
  width: Int,
  height: Int,
  textSize: Float,
): OsdLayout {
  val dot = textSize.toDouble() * height / OsdFont.HEIGHT
  val insetX = OsdParams.SAFE_X * width
  val insetY = OsdParams.SAFE_Y * height
  return OsdLayout(dot = dot, left = insetX, right = width - insetX, top = insetY, bottom = height - insetY)
}

// The top left of a line's first dot, and the size of a dot, in pixels.
internal class DotOrigin(
  val x: Double,
  val y: Double,
  val dot: Double,
)

// How many dots wide a line is, without the gap after its last glyph.
internal fun lineDots(line: OsdLine): Int {
  val glyphs = line.runs.sumOf { run -> run.text.sumOf { OsdFont.glyph(it).width + OsdParams.GLYPH_GAP_DOTS } }
  return max(0, glyphs - OsdParams.GLYPH_GAP_DOTS)
}

// A line's dots as colours, with HIDDEN where nothing is lit.
private class LineDots(
  line: OsdLine,
) {
  val width: Int = lineDots(line)
  private val colours = IntArray(width * OsdFont.HEIGHT)

  init {
    var x = 0
    for (run in line.runs) {
      for (char in run.text) {
        val glyph = OsdFont.glyph(char)
        for (gy in 0 until OsdFont.HEIGHT) {
          for (gx in 0 until glyph.width) {
            if (glyph.isLit(gx, gy)) colours[gy * width + x + gx] = run.colour
          }
        }
        x += glyph.width + OsdParams.GLYPH_GAP_DOTS
      }
    }
  }

  fun colourAt(
    gx: Int,
    gy: Int,
  ): Int = if (gx in 0 until width && gy in 0 until OsdFont.HEIGHT) colours[gy * width + gx] else HIDDEN

  fun touchesLit(
    gx: Int,
    gy: Int,
  ): Boolean {
    for (dy in -1..1) {
      for (dx in -1..1) {
        if (colourAt(gx + dx, gy + dy) != HIDDEN) return true
      }
    }
    return false
  }
}

private const val PIXEL_CENTRE = 0.5

// The raster as RGBA bytes, top row first, the form a GL texture is uploaded from.
internal fun OsdRaster.rgbaBytes(): ByteArray {
  val bytes = ByteArray(pixels.size * RGBA)
  pixels.forEachIndexed { index, argb ->
    val at = index * RGBA
    bytes[at] = (argb ushr RED_SHIFT).toByte()
    bytes[at + 1] = (argb ushr GREEN_SHIFT).toByte()
    bytes[at + 2] = argb.toByte()
    bytes[at + 3] = (argb ushr ALPHA_SHIFT).toByte()
  }
  return bytes
}

// Draws [region] of [state] over the same pixels of [rgba], the RGBA bytes of a whole [width] by [height] frame, and
// leaves every other pixel as it was.
internal fun CamcorderOsd.redrawInto(
  rgba: ByteArray,
  state: OsdState,
  width: Int,
  height: Int,
  region: PixelRect,
) {
  val bytes = rasterOf(state, width, height, region).rgbaBytes()
  val stride = region.width * RGBA
  for (row in 0 until region.height) {
    bytes.copyInto(rgba, ((region.top + row) * width + region.left) * RGBA, row * stride, (row + 1) * stride)
  }
}

private const val RGBA = 4
private const val ALPHA_SHIFT = 24
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8

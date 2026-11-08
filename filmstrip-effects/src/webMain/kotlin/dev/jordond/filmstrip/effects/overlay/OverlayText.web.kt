@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.PICTURE_PEAK_CEILING
import dev.jordond.filmstrip.style.FontWeight
import dev.jordond.filmstrip.style.TextAlignment
import dev.jordond.filmstrip.style.TextStyle
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.math.ceil
import kotlin.math.floor

/**
 * A run of text drawn at the size its glyphs cover, plus the plate around them.
 *
 * @property size The pixel size of the raster.
 * @property samples The raster as straight sixteen-bit RGBA, four a texel row by row from the top left, which the
 * compositor uploads.
 * @property lines The lines the text was broken into, top to bottom.
 * @property capHeight How tall a capital stands in the face the text was drawn in, in pixels.
 * @property font The CSS font shorthand the text was drawn in.
 */
internal class LaidOutText(
  val size: Size,
  val samples: ShortArray,
  val lines: List<String>,
  val capHeight: Float,
  val font: String,
)

/**
 * Draws [text] at the pixel size it occupies on [frame].
 *
 * The text is measured by [measuredText]. Lines wrap greedily at [wrapWidthOn], breaking at spaces and at every `\n`,
 * and a word wider than a whole line is split between graphemes. The block is as wide as its longest line, each line
 * is aligned inside it, and a plate adds [platePaddingOn] on every side, counted by [plateSide] so a huge face is
 * refused by its measured size rather than wrapping negative.
 *
 * The glyphs are drawn in the text's own color at full alpha through [textCoverage], in tiles no browser refuses. A
 * tile draws only the lines whose glyphs reach it, by [linesInTile] and then each line's own ink box. [platedSamples]
 * lays the text color at its alpha over the plate from what the tiles read, so a translucent plate keeps its straight
 * color exactly and a color font's glyph keeps its own color. Colors are written as the codes the style names, with
 * no color management. Samples js cannot allocate are refused with the reason [textAllocationFailed] gives, while on
 * wasm an array too large traps, as [allocatedOrNull] says.
 *
 * @param maxSide The most pixels a texture holds on a side, past which the text is refused as [textCoverage] says.
 * @param ceiling The most bytes the raster may hold at its peak, [PICTURE_PEAK_CEILING] by default.
 * @param tileSide The most pixels a tile holds on a side, [TEXT_TILE_SIDE] by default.
 * @return The drawn text, or why there is none.
 */
internal fun rasterizeText(
  text: String,
  style: TextStyle,
  frame: Size,
  maxSide: Int,
  ceiling: Long = PICTURE_PEAK_CEILING,
  tileSide: Int = TEXT_TILE_SIDE,
): TextRaster<LaidOutText> {
  val measured = measuredText(text, style, frame) ?: return TextRaster.Empty
  val (font, lines, inks, metrics) = measured
  val advance = measured.advance
  val block = measured.width

  val padding = style.platePaddingOn(frame)
  val firstBaseline = padding + metrics.fontBoundingBoxAscent
  val reachUp = inks.maxOf { it.ascent }
  val reachDown = inks.maxOf { it.descent }
  val wide = plateSide(block, padding)
  val tall = plateSide(measured.height, padding)
  val coverage =
    when (
      val drawn =
        textCoverage(wide, tall, style.color, maxSide, ceiling, tileSide) { context, tile ->
          context.font = font
          context.textBaseline = ALPHABETIC
          for (index in linesInTile(tile.top, tile.bottom, firstBaseline, advance, reachUp, reachDown, lines.size)) {
            val ink = inks[index]
            val x = padding + style.alignment.offset(block - ink.width)
            val y = firstBaseline + index * advance
            if (tile.meets(ink, x, y)) context.fillText(lines[index], x, y)
          }
        }
    ) {
      is TextRaster.Drawn -> drawn.pixels
      is TextRaster.Refused -> return drawn
    }

  val size = Size(wide.toInt(), tall.toInt())
  val samples =
    allocatedOrNull { ShortArray(coverage.size) }
      ?: return TextRaster.Refused(textAllocationFailed(size.width, size.height))
  platedSamples(coverage, style.color, style.backgroundColor, samples)
  return TextRaster.Drawn(LaidOutText(size, samples, lines, metrics.actualBoundingBoxAscent.toFloat(), font))
}

/**
 * Text broken into lines and measured in the face it is drawn in, before anything is drawn.
 *
 * @property font The CSS font shorthand the text is measured and drawn in.
 * @property lines The lines the text breaks into, top to bottom.
 * @property inks What `measureText` gave each line, in the order of [lines].
 * @property metrics What `measureText` gave a capital, which the line advance and the cap height are read from.
 * @property width How wide the block is in whole pixels, the widest line's advance and never less than one.
 * @property height How tall the block is in whole pixels, one [advance] a line.
 */
internal data class MeasuredText(
  val font: String,
  val lines: List<String>,
  val inks: List<InkBox>,
  val metrics: TextMetrics,
  val width: Long,
  val height: Long,
) {
  /**
   * How far one baseline sits below the one before it, in pixels.
   *
   * It is the face's ascent and descent together, the same for every line.
   */
  val advance: Double get() = metrics.fontBoundingBoxAscent + metrics.fontBoundingBoxDescent
}

/**
 * Measures [text] in [style] at the pixel size it occupies on [frame], or gives null when it leaves nothing to draw.
 *
 * The face is sized so a capital stands [capHeightOn] tall, measured off a capital drawn at [CAP_PROBE_SIZE], and the
 * lines break as [wrapLines] says at [wrapWidthOn]. Both sides are counted in a [Long], so a face too large for a
 * raster is measured whole and refused by its real size.
 */
internal fun measuredText(
  text: String,
  style: TextStyle,
  frame: Size,
): MeasuredText? {
  if (text.isEmpty() || frame.width <= 0 || frame.height <= 0) return null
  val measure = OffscreenCanvas(1, 1).getContext(CONTEXT_2D) ?: return null

  val family = familyOf(style)
  measure.textBaseline = ALPHABETIC
  measure.font = fontOf(style.weight, CAP_PROBE_SIZE, family)
  val probed = measure.measureText(CAP_PROBE).actualBoundingBoxAscent.toFloat()
  val cap = style.capHeightOn(frame)
  val font = fontOf(style.weight, if (probed > 0f) CAP_PROBE_SIZE * cap / probed else cap, family)
  measure.font = font

  val lines = wrapLines(text, style.wrapWidthOn(frame)) { measure.measureText(it).width }
  val inks = lines.map { InkBox(measure.measureText(it)) }
  val metrics = measure.measureText(CAP_PROBE)
  val width = ceil(inks.maxOf { it.width }).toLong().coerceAtLeast(1)
  val height = ceil((metrics.fontBoundingBoxAscent + metrics.fontBoundingBoxDescent) * lines.size).toLong()
  return MeasuredText(font, lines, inks, metrics, width, height).takeIf { height > 0 }
}

// What a line's `measureText` gave, copied out into plain numbers.
internal class InkBox(
  metrics: TextMetrics,
) {
  val width = metrics.width
  val left = metrics.actualBoundingBoxLeft
  val right = metrics.actualBoundingBoxRight
  val ascent = metrics.actualBoundingBoxAscent
  val descent = metrics.actualBoundingBoxDescent
}

/**
 * The pixels a glyph's shading can reach past the bounds `measureText` gives it, on every side.
 *
 * One pixel is for the edge a browser shades past a glyph's bounds and one for the row its baseline snaps to.
 */
internal const val INK_MARGIN: Double = 2.0

/**
 * The lines of a block whose glyphs can reach the rows from [top] up to but not including [bottom].
 *
 * Line `i` sits on a baseline [advance] times `i` below [firstBaseline]. No line's glyphs stand more than [reachUp]
 * above its baseline or [reachDown] below it, and each is widened by [INK_MARGIN] for the shading a browser lays past
 * a glyph's bounds and the row its baseline snaps to. Every line with ink in the rows is in the range, which can also
 * hold lines that draw nothing there.
 *
 * @param count How many lines the block holds.
 * @return The indices of the lines to draw, empty when none reach the rows.
 */
internal fun linesInTile(
  top: Int,
  bottom: Int,
  firstBaseline: Double,
  advance: Double,
  reachUp: Double,
  reachDown: Double,
  count: Int,
): IntRange {
  if (count <= 0) return IntRange.EMPTY
  if (advance <= 0.0) return 0 until count
  val first = ceil((top - reachDown - INK_MARGIN - firstBaseline) / advance).coerceIn(0.0, count.toDouble())
  val last = floor((bottom + reachUp + INK_MARGIN - firstBaseline) / advance).coerceIn(-1.0, count - 1.0)
  return first.toInt()..last.toInt()
}

/**
 * Breaks [text] into lines no wider than [width] where it can.
 *
 * Greedy: each line takes as many space-separated words as fit, and every `\n` starts a new line. A word wider than
 * [width] on its own is split between graphemes, so it fills lines rather than running off the edge of one.
 *
 * @param measure The width of a run of text, in the same pixels as [width].
 */
internal fun wrapLines(
  text: String,
  width: Int,
  measure: (String) -> Double,
): List<String> {
  val lines = mutableListOf<String>()
  for (paragraph in text.split('\n')) {
    var line = ""
    for (word in paragraph.split(' ')) {
      val joined = if (line.isEmpty()) word else "$line $word"
      if (measure(joined) <= width) {
        line = joined
        continue
      }
      if (line.isNotEmpty()) lines += line
      line = word
      if (measure(word) <= width) continue

      line = ""
      for (grapheme in graphemesOf(word)) {
        if (line.isNotEmpty() && measure(line + grapheme) > width) {
          lines += line
          line = grapheme
        } else {
          line += grapheme
        }
      }
    }
    lines += line
  }
  return lines
}

/**
 * The CSS font shorthand for a face of [weight] at [size] pixels in [family].
 *
 * The size is written by [plainDecimalOf], since wasm writes a large or small float in exponent form.
 */
internal fun fontOf(
  weight: FontWeight,
  size: Float,
  family: String,
): String = "${weight.cssWeight()} ${plainDecimalOf(size)}px $family"

/**
 * The digits [value] is written with, as a plain decimal with no exponent.
 *
 * A value Kotlin writes in exponent form, such as `1.0E7` or `1.5E-7`, has its decimal point moved by the exponent and
 * padded with zeros, so `10000000` and `0.00000015` carry the same digits. Any other value is written as it is.
 */
internal fun plainDecimalOf(value: Float): String {
  val written = value.toString()
  val exponentAt = written.indexOfFirst { it == 'e' || it == 'E' }
  if (exponentAt < 0) return written
  val mantissa = written.substring(0, exponentAt)
  val sign = if (mantissa.startsWith('-')) "-" else ""
  val unsigned = mantissa.removePrefix("-")
  val digits = unsigned.replace(".", "")
  val point = (unsigned.indexOf('.').takeIf { it >= 0 } ?: unsigned.length) + written.substring(exponentAt + 1).toInt()
  val plain =
    when {
      point <= 0 -> "0." + "0".repeat(-point) + digits
      point >= digits.length -> digits + "0".repeat(point - digits.length)
      else -> digits.substring(0, point) + "." + digits.substring(point)
    }
  return sign + plain
}

/**
 * The CSS family list [style] is drawn in, its own family ahead of the default one or the default alone.
 *
 * A named family goes in as a CSS string, so a comma, a quote, a leading digit or a control character stays part of
 * the name. The generic families behind it are keywords and stay bare, so a face that never loaded falls back the way
 * the default does.
 */
internal fun familyOf(style: TextStyle): String {
  val family = style.resolvedFontFamily ?: return DEFAULT_FAMILY
  return "${cssStringOf(family)}, $DEFAULT_FAMILY"
}

// Whether the glyphs of a line measured as [ink], drawn at x on baseline y, can shade a texel of this tile.
private fun TextTile.meets(
  ink: InkBox,
  x: Double,
  y: Double,
): Boolean =
  x + ink.right + INK_MARGIN > left &&
    x - ink.left - INK_MARGIN < right &&
    y + ink.descent + INK_MARGIN > top &&
    y - ink.ascent - INK_MARGIN < bottom

private fun graphemesOf(word: String): List<String> {
  val segments = Segmenter().segment(word)
  val graphemes = mutableListOf<String>()
  var index = 0
  while (index < word.length) {
    val grapheme = segments.containing(index)?.segment?.takeIf { it.isNotEmpty() } ?: word.substring(index, index + 1)
    graphemes += grapheme
    index += grapheme.length
  }
  return graphemes
}

// A control character is written as a hex escape ended by a space, since a raw newline ends the
// string and leaves the whole shorthand unparsed.
private fun cssStringOf(value: String): String =
  buildString {
    append('"')
    value.forEach { char ->
      when {
        char == '\\' || char == '"' -> append('\\').append(char)
        char < ' ' || char == DELETE -> append('\\').append(char.code.toString(HEX)).append(' ')
        else -> append(char)
      }
    }
    append('"')
  }

private fun FontWeight.cssWeight(): Int =
  when (this) {
    FontWeight.Regular -> 400
    FontWeight.Medium -> 500
    FontWeight.Bold -> 700
  }

private fun TextAlignment.offset(slack: Double): Double =
  when (this) {
    TextAlignment.Start -> 0.0
    TextAlignment.Center -> slack / 2
    TextAlignment.End -> slack
  }

private const val CAP_PROBE = "H"

private const val ALPHABETIC = "alphabetic"
private const val DEFAULT_FAMILY = "system-ui, sans-serif"
private const val DELETE = '\u007F'
private const val HEX = 16

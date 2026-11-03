package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.style.FontWeight
import dev.jordond.filmstrip.style.TextAlignment
import dev.jordond.filmstrip.style.TextStyle
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.font.LineBreakMeasurer
import java.awt.font.TextAttribute
import java.awt.font.TextLayout
import java.awt.image.BufferedImage
import java.text.AttributedString
import kotlin.math.ceil

/**
 * Draws [text] to straight sixteen-bit samples sized to the lines it breaks into, plus the plate around them.
 *
 * The face comes from [fontOf], sized so a capital stands [capHeightOn] tall, and is laid out by [textBlock] at the
 * real pixel size it occupies on [frame]. Java2D draws the glyphs in the text's own color at full alpha onto a
 * transparent eight-bit ARGB image, read back straight, and [platedSamples] lays the text color at its alpha over the
 * plate by how much of each texel they cover. A glyph the font draws in its own colors keeps them. Colors are written
 * as the codes the style names.
 *
 * The raster is counted by [textRasterFits] before anything is allocated, with the band [jvmTextBandBytes] gives, and
 * one past [ceiling] is refused with the reason [textTooLargeToDraw] gives. A face [fontOf] cannot give is refused with
 * the reason [textNoFont] gives, text holding a character that face has no glyph for with the reason [textMissingGlyph]
 * gives, and a raster the JVM cannot allocate with the reason [textAllocationFailed] gives.
 *
 * @param frame The frame the text is drawn on, which every fraction in [style] is measured against.
 * @param ceiling The most bytes the raster may hold at its peak, [PICTURE_PEAK_CEILING] by default.
 * @param fontOf Finds the face for a family and weight at [CAP_PROBE_SIZE], or null when the host has none it can
 * draw with. [awtFont] by default.
 * @return The drawn text as an untagged picture of sixteen-bit samples, or why there is none.
 */
internal fun rasterizeText(
  text: String,
  style: TextStyle,
  frame: Size,
  ceiling: Long = PICTURE_PEAK_CEILING,
  fontOf: (family: String?, weight: FontWeight) -> Font? = ::awtFont,
): TextRaster<StoredPicture> {
  if (text.isEmpty() || frame.width <= 0 || frame.height <= 0) return TextRaster.Empty
  val probe = fontOf(style.resolvedFontFamily, style.weight) ?: return TextRaster.Refused(textNoFont(style))
  val missing = probe.firstMissingGlyph(text)
  if (missing != null) return TextRaster.Refused(textMissingGlyph(style, missing))
  val block = textBlock(text, style, frame, probe) ?: return TextRaster.Empty

  val padding = style.platePaddingOn(frame)
  val wide = plateSide(block.width, padding)
  val tall = plateSide(block.height, padding)
  if (!textRasterFits(wide, tall, TEXT_TEXEL_BYTES, jvmTextBandBytes(wide, tall), ceiling)) {
    return TextRaster.Refused(textTooLargeToDraw(wide, tall, TEXT_TEXEL_BYTES, ceiling))
  }
  val size = Size(wide.toInt(), tall.toInt())

  return try {
    val glyphs = textCoverage(size, style.color) { draw -> block.drawOn(draw, padding.toFloat(), style.alignment) }
    val samples = platedSamples(glyphs, style.color, style.backgroundColor)
    TextRaster.Drawn(StoredPicture(StoredSamples.Rgba16(samples), size, color = null))
  } catch (_: OutOfMemoryError) {
    TextRaster.Refused(textAllocationFailed(size.width, size.height))
  }
}

/**
 * Text broken into lines in the face it is drawn in, before anything is drawn.
 *
 * @property lines Each line top to bottom, or null for an empty one a line break left.
 * @property ascent How far the face reaches above a baseline, in pixels.
 * @property lineHeight How far one baseline sits below the one before it, the face's ascent and descent together.
 * @property width How wide the block is in whole pixels, the widest line's visible advance and never less than one.
 * @property height How tall the block is in whole pixels, one [lineHeight] a line.
 */
internal class TextBlock(
  val lines: List<TextLayout?>,
  val ascent: Float,
  val lineHeight: Float,
  val width: Long,
  val height: Long,
)

/**
 * Lays [text] out in [style] on [frame] with [probe], or gives null when it leaves nothing to draw.
 *
 * [probe] is scaled so a capital stands [capHeightOn] tall, measured off a capital at the probe's own size. Each
 * paragraph breaks at [wrapWidthOn] with a [LineBreakMeasurer]. The block is then as wide as its widest line, so an
 * alignment is measured against the text rather than against a wrap width the text never reached. Every line fits that
 * width already, so the breaks stay where the wrap put them.
 */
internal fun textBlock(
  text: String,
  style: TextStyle,
  frame: Size,
  probe: Font,
): TextBlock? {
  val probed = probe.capHeight()
  val cap = style.capHeightOn(frame)
  val font = probe.deriveFont(if (probed > 0f) probe.size2D * cap / probed else cap)
  val metrics = font.getLineMetrics(CAP_PROBE, RENDER_CONTEXT)
  val wrap = style.wrapWidthOn(frame).toFloat()

  val lines = text.split(LINE_BREAK).flatMap { paragraph -> font.wrapped(paragraph, wrap) }
  val widest = lines.maxOf { it?.visibleAdvance ?: 0f }
  val lineHeight = metrics.ascent + metrics.descent
  val width = ceil(widest.toDouble()).toLong().coerceAtLeast(1)
  val height = ceil(lineHeight.toDouble() * lines.size).toLong()
  return TextBlock(lines, metrics.ascent, lineHeight, width, height).takeIf { height > 0 }
}

/**
 * The bytes a JVM text raster holds on the way to its samples, or null when that count does not fit in a [Long].
 *
 * The image the glyphs are drawn into and the coverage read back out of it are held together, four bytes a texel each.
 */
internal fun jvmTextBandBytes(
  width: Long,
  height: Long,
): Long? {
  if (width <= 0 || height <= 0) return 0L
  return if (width > Long.MAX_VALUE / JVM_BAND_TEXEL_BYTES / height) null else width * height * JVM_BAND_TEXEL_BYTES
}

/**
 * The face Java2D draws [family] in at [weight], at [CAP_PROBE_SIZE], or null when the host has none that draws a
 * capital.
 *
 * A null [family] is the JVM's logical sans serif, the platform default. A host with no fonts at all fails inside the
 * JDK's font lookup rather than answering a face, and that failure reads as no face here.
 */
internal fun awtFont(
  family: String?,
  weight: FontWeight,
): Font? =
  try {
    val base = Font(family ?: Font.SANS_SERIF, if (weight == FontWeight.Bold) Font.BOLD else Font.PLAIN, 1)
    val face =
      if (weight == FontWeight.Medium) {
        base.deriveFont(mapOf(TextAttribute.WEIGHT to TextAttribute.WEIGHT_MEDIUM))
      } else {
        base
      }
    face.deriveFont(CAP_PROBE_SIZE).takeIf { it.canDisplayUpTo(CAP_PROBE) == -1 && it.capHeight() > 0f }
  } catch (_: RuntimeException) {
    null
  } catch (_: Error) {
    // The JDK's limit: a host with no fonts throws an Error out of its font manager instead of answering one.
    null
  }

/**
 * The reason a text overlay is refused when the JVM finds no face to draw [style]'s family in.
 *
 * It names the family, or the platform default when [style] names none, and the font package that gives a bare Linux
 * host one.
 */
internal fun textNoFont(style: TextStyle): String =
  "No font could draw the text in ${familyOf(style)}. This backend draws text with the fonts the JVM finds on this " +
    "machine, and it found none that draws it. Install a font, such as fonts-dejavu-core on Debian or Ubuntu."

/**
 * The reason a text overlay is refused when the face found for [style] has no glyph for [codePoint].
 *
 * It names the character by its Unicode code point and the family it was looked up in, since which characters a face
 * covers differs from one host to the next.
 */
internal fun textMissingGlyph(
  style: TextStyle,
  codePoint: Int,
): String =
  "The font for ${familyOf(style)} has no glyph for ${"U+%04X".format(codePoint)}, so it would draw as an empty box. " +
    "This backend draws text with the fonts the JVM finds on this machine. " +
    "Install a font that covers the character, or name a family that does."

private fun familyOf(style: TextStyle): String = style.resolvedFontFamily ?: "the platform's default sans serif"

// The first character this face has no glyph for, skipping the spaces and control characters that draw none at all.
private fun Font.firstMissingGlyph(text: String): Int? =
  text.codePoints().toArray().firstOrNull { codePoint ->
    !Character.isWhitespace(codePoint) && !Character.isISOControl(codePoint) && !canDisplay(codePoint)
  }

// How tall this face's capitals stand, in pixels at its own size.
private fun Font.capHeight(): Float = createGlyphVector(RENDER_CONTEXT, CAP_PROBE).visualBounds.height.toFloat()

// The lines one paragraph breaks into at [wrap] pixels, or one empty line for an empty paragraph.
private fun Font.wrapped(
  paragraph: String,
  wrap: Float,
): List<TextLayout?> {
  if (paragraph.isEmpty()) return listOf(null)
  val styled = AttributedString(paragraph).apply { addAttribute(TextAttribute.FONT, this@wrapped) }
  val measurer = LineBreakMeasurer(styled.iterator, RENDER_CONTEXT)
  return buildList {
    while (measurer.position < paragraph.length) add(measurer.nextLayout(wrap))
  }
}

// Draws every line, each aligned inside the block and the block inset by [padding] on every side.
private fun TextBlock.drawOn(
  draw: Graphics2D,
  padding: Float,
  alignment: TextAlignment,
) {
  lines.forEachIndexed { index, line ->
    if (line == null) return@forEachIndexed
    val slack = width - line.visibleAdvance
    val x =
      when (alignment) {
        TextAlignment.Start -> 0f
        TextAlignment.Center -> slack / 2f
        TextAlignment.End -> slack
      }
    line.draw(draw, padding + x, padding + ascent + index * lineHeight)
  }
}

/**
 * Draws glyphs with [draw] into a transparent [size] image in [color] at full alpha and reads them back straight, four
 * bytes a texel row by row from the top left, red, green, blue and alpha.
 *
 * The image holds eight-bit ARGB that is not premultiplied, so each texel reads back as the color it was drawn in and
 * its alpha is how much of it the glyphs cover.
 */
private fun textCoverage(
  size: Size,
  color: Int,
  draw: (Graphics2D) -> Unit,
): ByteArray {
  val image = BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB)
  val graphics = image.createGraphics()
  try {
    graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
    graphics.color = Color(color and RGB_MASK)
    draw(graphics)
  } finally {
    graphics.dispose()
  }
  val argb = image.getRGB(0, 0, size.width, size.height, null, 0, size.width)
  val glyphs = ByteArray(argb.size * RGBA)
  argb.forEachIndexed { texel, value ->
    val offset = texel * RGBA
    glyphs[offset] = (value shr RED_SHIFT).toByte()
    glyphs[offset + 1] = (value shr GREEN_SHIFT).toByte()
    glyphs[offset + 2] = value.toByte()
    glyphs[offset + ALPHA] = (value ushr ALPHA_SHIFT).toByte()
  }
  return glyphs
}

// Antialiased with fractional metrics, the way the glyphs are drawn, so a measured line is as wide as the drawn one.
private val RENDER_CONTEXT = FontRenderContext(null, true, true)

private const val CAP_PROBE = "H"
private const val LINE_BREAK = '\n'

// The image and the coverage, four bytes a texel each.
private const val JVM_BAND_TEXEL_BYTES = 8L

private const val RGB_MASK = 0xFFFFFF
private const val RGBA = 4
private const val ALPHA = 3
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val ALPHA_SHIFT = 24

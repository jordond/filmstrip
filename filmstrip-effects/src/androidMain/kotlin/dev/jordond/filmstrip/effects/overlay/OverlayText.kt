package dev.jordond.filmstrip.effects.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.createBitmap
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.PICTURE_PEAK_CEILING
import dev.jordond.filmstrip.style.FontWeight
import dev.jordond.filmstrip.style.TextAlignment
import dev.jordond.filmstrip.style.TextStyle
import java.nio.ByteBuffer
import kotlin.math.ceil

/**
 * The name of the graphics API media3 uploads its overlay textures through.
 *
 * It goes into [textPastTextureSide] and [picturePastTextureSide], so a refusal says whose texture side it hit.
 */
internal const val OPEN_GL_ES: String = "OpenGL ES"

/**
 * Draws [text] to straight sixteen-bit samples sized to the glyphs it actually covers.
 *
 * Laid out by [textBlock] at the real pixel size it will occupy on [frame], so the overlay is composited at one to one
 * and never resampled.
 *
 * The glyphs are drawn in the text's own color at full alpha through [textCoverage], and [platedSamples] lays the
 * text color at its alpha over the plate by how much of each texel they cover, so a translucent plate keeps its
 * straight color exactly. A color font's glyphs keep their own color. Colors are written as the codes the style
 * names.
 *
 * The raster is counted by [textRasterFits] before [bitmapOf] runs, at [TEXT_TEXEL_BYTES] a texel with the band
 * [textBandBytes] gives, and one past [ceiling] is refused with the reason [textTooLargeToDraw] gives. So is text whose
 * block [textSidesPastLayout] measures past what `StaticLayout` can place. Then one longer than [maxSide] on a side is
 * refused with the reason [textPastTextureSide] gives. One Android cannot allocate is refused with the reason
 * [textAllocationFailed] gives.
 *
 * @param frame The frame the text is drawn on, which every fraction in [style] is measured against.
 * @param maxSide The most pixels a texture holds on a side, OpenGL ES's `GL_MAX_TEXTURE_SIZE`, the limit of the texture
 * media3 uploads the raster to.
 * @param ceiling The most bytes the raster may hold at its peak, [textCeiling] by default.
 * @param bitmapOf Makes the bitmap the coverage of a raster of the given width and height is drawn into, [textBitmap]
 * by default.
 * @return The drawn text, or why there is none.
 */
internal fun rasterizeText(
  text: String,
  style: TextStyle,
  frame: Size,
  maxSide: Int,
  ceiling: Long = textCeiling(),
  bitmapOf: (width: Int, height: Int) -> Bitmap = ::textBitmap,
): TextRaster<TextSamples> {
  val padding = style.platePaddingOn(frame)
  val block =
    textBlock(text, style, frame)
      ?: return textSidesPastLayout(text, style, frame)?.let { (width, height) ->
        val (wide, tall) = plateSide(width, padding) to plateSide(height, padding)
        TextRaster.Refused(textTooLargeToDraw(wide, tall, TEXT_TEXEL_BYTES, ceiling))
      } ?: TextRaster.Empty
  val wide = plateSide(block.width.toLong(), padding)
  val tall = plateSide(block.height.toLong(), padding)
  if (!textRasterFits(wide, tall, TEXT_TEXEL_BYTES, textBandBytes(wide, tall), ceiling)) {
    return TextRaster.Refused(textTooLargeToDraw(wide, tall, TEXT_TEXEL_BYTES, ceiling))
  }
  val width = wide.toInt()
  val height = tall.toInt()
  if (!fitsTextureSide(width, height, maxSide)) {
    return TextRaster.Refused(textPastTextureSide(width, height, maxSide, OPEN_GL_ES))
  }

  return try {
    val glyphs =
      textCoverage(width, height, bitmapOf) { canvas ->
        canvas.translate(padding.toFloat(), padding.toFloat())
        block.draw(canvas)
      }
    TextRaster.Drawn(TextSamples(Size(width, height), platedSamples(glyphs, style.color, style.backgroundColor)))
  } catch (_: OutOfMemoryError) {
    TextRaster.Refused(textAllocationFailed(width, height))
  }
}

/**
 * Lays [text] out in [style] on [frame], or gives null when it leaves nothing to draw or when
 * [textSidesPastLayout] measures a side of its block past what an [Int] holds.
 *
 * Two passes: the first wraps at the authored maximum width, the second re-lays the wrapped text in a box tight to its
 * longest line, so an alignment is measured against the text rather than against an authored width the text never
 * reached. The paint is the text color at full alpha. Media3's own `TextOverlay` lays out at a fixed hundred-pixel
 * paint and offers no wrap width, which is why filmstrip lays out and rasterizes instead.
 */
internal fun textBlock(
  text: String,
  style: TextStyle,
  frame: Size,
): StaticLayout? {
  val wrapped = wrappedText(text, style, frame) ?: return null
  if (wrapped.isPastLayout) return null
  val block = wrapped.paint.layout(text, wrapped.width.toInt().coerceAtLeast(1), wrapped.alignment)
  return block.takeIf { it.width > 0 && it.height > 0 }
}

/**
 * The width and height in pixels of the block [textBlock] lays [text] out in, when one of them passes what an [Int]
 * holds, or null when neither does.
 *
 * `StaticLayout` places its lines in [Int] pixels, which is Android's limit, so a block this large would wrap its
 * positions. The sides are measured in [Long] instead, as wide as the widest wrapped line and each line as tall as the
 * face's ascent to its descent, and [textBlock] gives null for exactly the text this gives sides for.
 */
internal fun textSidesPastLayout(
  text: String,
  style: TextStyle,
  frame: Size,
): Pair<Long, Long>? = wrappedText(text, style, frame)?.takeIf { it.isPastLayout }?.let { it.width to it.height }

// Text wrapped at the authored width, with the sides of the block it lays out in measured in Long.
private class WrappedText(
  val paint: TextPaint,
  val alignment: Layout.Alignment,
  val width: Long,
  val height: Long,
) {
  val isPastLayout: Boolean get() = width > Int.MAX_VALUE || height > Int.MAX_VALUE
}

private fun wrappedText(
  text: String,
  style: TextStyle,
  frame: Size,
): WrappedText? {
  if (text.isEmpty() || frame.width <= 0 || frame.height <= 0) return null
  val paint = textPaint(style, frame)
  val alignment = style.alignment.toLayout()
  val wrapped = paint.layout(text, style.wrapWidthOn(frame), alignment)
  val metrics = paint.fontMetrics
  val width = ceil(wrapped.widestLine().toDouble()).toLong()
  val lineHeight = ceil(metrics.descent.toDouble() - metrics.ascent).toLong()
  val lines = wrapped.lineCount.coerceAtLeast(1)
  val height = if (lineHeight > Long.MAX_VALUE / lines) Long.MAX_VALUE else lineHeight * lines
  return WrappedText(paint, alignment, width, height)
}

// The paint text is laid out with, the text color at full alpha and sized so a capital stands the cap height.
private fun textPaint(
  style: TextStyle,
  frame: Size,
): TextPaint =
  TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    typeface = typefaceFor(style.resolvedFontFamily, style.weight)
    color = style.color or OPAQUE_ALPHA
    textSize = capHeightTextSize(style.capHeightOn(frame))
  }

private fun StaticLayout.widestLine(): Float = (0 until lineCount).maxOfOrNull { getLineWidth(it) } ?: 0f

/**
 * Draws glyphs with [draw] into a [width] by [height] bitmap [bitmapOf] makes and reads them back straight, four bytes
 * a texel row by row from the top left, red, green, blue and alpha.
 *
 * The bitmap starts transparent and holds eight-bit premultiplied ARGB, which is divided out here once with each
 * channel rounded to the nearest code. It is recycled once its texels are read or drawing it throws. Android shapes
 * the coverage of a glyph it draws from a mask by the color it is drawn in, so [draw] fills in the text's own color
 * at full alpha and each texel's alpha is how much of it the glyphs cover in that color. A color font's glyph keeps
 * its own color in the texel.
 *
 * @throws IllegalStateException When [bitmapOf] makes a bitmap of any other size or config.
 */
internal fun textCoverage(
  width: Int,
  height: Int,
  bitmapOf: (width: Int, height: Int) -> Bitmap,
  draw: (Canvas) -> Unit,
): ByteArray {
  val bitmap = bitmapOf(width, height)
  try {
    check(bitmap.config == Bitmap.Config.ARGB_8888 && bitmap.width == width && bitmap.height == height) {
      "Glyph coverage is drawn into a $width by $height ARGB_8888 bitmap, not ${bitmap.width} by ${bitmap.height} " +
        "${bitmap.config}."
    }
    draw(Canvas(bitmap))
    val glyphs = ByteArray(width * height * RGBA)
    bitmap.copyPixelsToBuffer(ByteBuffer.wrap(glyphs))
    glyphs.unpremultiply()
    return glyphs
  } finally {
    bitmap.recycle()
  }
}

private fun ByteArray.unpremultiply() {
  for (texel in indices step RGBA) {
    val alpha = this[texel + ALPHA].toInt() and BYTE_MASK
    if (alpha == 0 || alpha == TOP_CODE) continue
    for (channel in 0 until RGB) {
      val code = this[texel + channel].toInt() and BYTE_MASK
      this[texel + channel] = minOf(TOP_CODE, (code * TOP_CODE + alpha / 2) / alpha).toByte()
    }
  }
}

/**
 * A transparent [width] by [height] bitmap of eight-bit ARGB that glyph coverage is drawn into.
 *
 * Android stores it red, green, blue and then alpha a texel, the order [textCoverage] reads it back in.
 */
internal fun textBitmap(
  width: Int,
  height: Int,
): Bitmap = createBitmap(width, height)

/**
 * The most bytes a text raster may hold at its peak on Android, the smaller of [PICTURE_PEAK_CEILING] and half of
 * [heap].
 *
 * Every array a text raster holds on the way to the codes media3 draws, from the coverage to the samples, the floats
 * and the codes, lives on the Java heap. That heap is Android's limit rather than filmstrip's, capped per app well
 * below the picture ceiling on most devices and shared with everything else the app holds, so a raster past half of
 * it is refused by name rather than run the app out of memory. The share is of the heap's limit rather than of what
 * is free, so the same text resolves the same way in a preview and an export.
 *
 * @param heap The most bytes the Java heap may grow to, `Runtime.maxMemory` by default.
 */
internal fun textCeiling(heap: Long = Runtime.getRuntime().maxMemory()): Long =
  minOf(PICTURE_PEAK_CEILING, heap / HEAP_SHARE)

/**
 * The paint size at which this typeface's capitals stand [target] pixels tall.
 *
 * A style names a cap height rather than an em size, so the same number reads as the same visual
 * weight whatever typeface it lands on. Android only reports ascent and descent, so the cap is
 * measured off a capital drawn at a probe size and scaled from there.
 */
private fun TextPaint.capHeightTextSize(target: Float): Float {
  textSize = CAP_PROBE_SIZE
  val bounds = Rect()
  getTextBounds("H", 0, 1, bounds)
  val probed = bounds.height().toFloat()
  return if (probed > 0f) CAP_PROBE_SIZE * target / probed else target
}

private fun TextPaint.layout(
  text: String,
  width: Int,
  alignment: Layout.Alignment,
): StaticLayout =
  StaticLayout.Builder
    .obtain(SpannableString(text), 0, text.length, this, width)
    .setAlignment(alignment)
    .setIncludePad(false)
    .build()

private fun typefaceFor(
  family: String?,
  weight: FontWeight,
): Typeface {
  val base = family?.let { Typeface.create(it, Typeface.NORMAL) } ?: Typeface.DEFAULT
  return when (weight) {
    FontWeight.Regular -> {
      base
    }
    FontWeight.Bold -> {
      Typeface.create(base, Typeface.BOLD)
    }
    // Named weights only arrive in P. Below it the family's regular face is closer than its bold.
    FontWeight.Medium -> {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Typeface.create(base, MEDIUM_WEIGHT, false)
      } else {
        base
      }
    }
  }
}

private fun TextAlignment.toLayout(): Layout.Alignment =
  when (this) {
    TextAlignment.Start -> Layout.Alignment.ALIGN_NORMAL
    TextAlignment.Center -> Layout.Alignment.ALIGN_CENTER
    TextAlignment.End -> Layout.Alignment.ALIGN_OPPOSITE
  }

private const val MEDIUM_WEIGHT = 500

// Android's limit: the Java heap holds everything the app does, so one text raster may take at most half of it.
private const val HEAP_SHARE = 2L

private const val OPAQUE_ALPHA = 0xFF000000.toInt()

private const val RGB = 3
private const val RGBA = 4
private const val ALPHA = 3
private const val BYTE_MASK = 0xFF

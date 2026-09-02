package dev.jordond.filmstrip.effects.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.core.net.toUri
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.style.FontWeight
import dev.jordond.filmstrip.style.TextAlignment
import dev.jordond.filmstrip.style.TextStyle
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Draws [text] to a bitmap sized to the glyphs it actually covers.
 *
 * Laid out at the real pixel size it will occupy on [frame], so the overlay is composited at one to
 * one and never resampled. Media3's own `TextOverlay` lays out at a fixed hundred-pixel paint and
 * offers no wrap width, which is why filmstrip rasterizes instead.
 *
 * Two passes: the first wraps at the authored maximum width, the second re-lays the wrapped text in
 * a box tight to its longest line, so an alignment is measured against the text rather than against
 * an authored width the text never reached.
 *
 * @return The drawn text, or null when the style leaves nothing to draw.
 */
internal fun rasterizeText(
  text: String,
  style: TextStyle,
  frame: Size,
): Bitmap? {
  if (text.isEmpty() || frame.width <= 0 || frame.height <= 0) return null

  val paint =
    TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      typeface = typefaceFor(style.resolvedFontFamily, style.weight)
      color = style.color
      textSize = capHeightTextSize(style.fontSize * frame.height)
    }

  val wrapWidth = (style.maxWidth * frame.width).roundToInt().coerceAtLeast(1)
  val alignment = style.alignment.toLayout()
  val wrapped = paint.layout(text, wrapWidth, alignment)
  val used = (0 until wrapped.lineCount).maxOfOrNull { wrapped.getLineWidth(it) } ?: 0f
  val padding = if (style.backgroundColor == null) 0 else (paint.textSize * PLATE_PADDING).roundToInt()
  val block = paint.layout(text, ceil(used).toInt().coerceAtLeast(1), alignment)
  if (block.width <= 0 || block.height <= 0) return null

  val bitmap = createBitmap(block.width + 2 * padding, block.height + 2 * padding)
  val canvas = Canvas(bitmap)
  style.backgroundColor?.let { canvas.drawColor(it) }
  canvas.translate(padding.toFloat(), padding.toFloat())
  block.draw(canvas)
  return bitmap
}

/**
 * Decodes [this] to a bitmap, or null when it cannot be read.
 *
 * @param context Needed only to open a `content://` [ImageSource.Uri]. Every other form reads
 *   without one.
 */
internal fun ImageSource.decode(context: Context?): Bitmap? =
  when (this) {
    is ImageSource.Path -> BitmapFactory.decodeFile(path)
    is ImageSource.Bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    is ImageSource.Uri -> decodeUri(uri, context)
  }

/**
 * The bitmap's pixel size.
 */
internal fun Bitmap.size(): Size = Size(width, height)

private fun decodeUri(
  uri: String,
  context: Context?,
): Bitmap? {
  val parsed = runCatching { uri.toUri() }.getOrNull() ?: return null
  val path = parsed.path
  if (parsed.scheme == null || parsed.scheme == "file") {
    return path?.let { BitmapFactory.decodeFile(it) }
  }
  val resolver = context?.contentResolver ?: return null
  return runCatching {
    resolver.openInputStream(parsed).use { stream -> BitmapFactory.decodeStream(stream) }
  }.getOrNull()
}

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

private const val CAP_PROBE_SIZE = 100f
private const val MEDIUM_WEIGHT = 500

// A plate that stops at the glyphs reads as a mistake, so it carries a margin proportional to the
// text rather than a fixed number of pixels.
private const val PLATE_PADDING = 0.3f

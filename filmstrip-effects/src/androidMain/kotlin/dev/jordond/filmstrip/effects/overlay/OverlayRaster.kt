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
import androidx.media3.common.OverlaySettings
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effects.media3WorkingLight
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.authoredHdrNits
import dev.jordond.filmstrip.media.authoredHdrTexels
import dev.jordond.filmstrip.media.linearToSrgb
import dev.jordond.filmstrip.style.FontWeight
import dev.jordond.filmstrip.style.TextAlignment
import dev.jordond.filmstrip.style.TextStyle
import kotlin.math.ceil
import kotlin.math.max
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
      textSize = capHeightTextSize(style.capHeightOn(frame))
    }

  val alignment = style.alignment.toLayout()
  val wrapped = paint.layout(text, style.wrapWidthOn(frame), alignment)
  val used = (0 until wrapped.lineCount).maxOfOrNull { wrapped.getLineWidth(it) } ?: 0f
  val padding = style.platePaddingOn(frame)
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
 * An overlay's pixels ready for media3, and the HDR luminance multiplier to draw them with.
 *
 * @property bitmap The pixels to hand media3.
 * @property hdrLuminance The multiplier media3 reads [bitmap] through on an HDR frame.
 */
internal class Raster(
  val bitmap: Bitmap,
  val hdrLuminance: Float,
)

/**
 * Prepares [this] for a frame of [transfer], or for an SDR frame when it is null.
 *
 * Either way media3 gets a copy stored straight rather than premultiplied, since its overlay shader
 * multiplies by the alpha it reads itself. An SDR frame takes the colours as drawn. An HDR one takes
 * the codes [media3HdrTexels] writes.
 */
internal fun Bitmap.rasterFor(transfer: HdrTransfer?): Raster {
  val pixels = IntArray(width * height)
  getPixels(pixels, 0, width, 0, 0, width, height)
  if (transfer == null) return Raster(straightCopy(pixels), OverlaySettings.DEFAULT_HDR_LUMINANCE_MULTIPLIER)

  val texels = media3HdrTexels(pixels, transfer)
  return Raster(straightCopy(texels.argb), texels.hdrLuminance)
}

// getPixels answers unpremultiplied colours whatever the bitmap stores, and setPixels on a bitmap
// that is not premultiplied keeps them that way.
private fun Bitmap.straightCopy(argb: IntArray): Bitmap =
  createBitmap(width, height).apply {
    isPremultiplied = false
    setPixels(argb, 0, width, 0, 0, width, height)
  }

/**
 * Overlay texels rewritten so media3's HDR overlay path lands each one where [authoredHdrNits] puts
 * its colour.
 *
 * media3 1.11.1 draws a text overlay onto an HDR frame as its HDR luminance multiplier times the sRGB
 * decode of each texel, with no change of primaries, then blends that with the frame by the texel's
 * alpha. Each colour here is [media3WorkingLight] of the authored one, divided by [hdrLuminance] and
 * encoded back to sRGB, so media3's own read of it undoes the mapping.
 *
 * @property argb Straight ARGB texels, each alpha as it came in.
 * @property hdrLuminance The multiplier the texels are written against, never below the level
 *   reference white reaches, and raised to the brightest channel when a colour goes past it.
 */
@InternalFilmstripApi
public class Media3HdrTexels(
  public val argb: IntArray,
  public val hdrLuminance: Float,
)

/**
 * Maps straight ARGB [argb] texels onto the codes media3 reads back as their authored light on a
 * frame of [transfer].
 *
 * Reference white lands on the top code, so on a PQ frame a neutral texel keeps its own code and only
 * a saturated one moves, by the change of primaries. On an HLG frame a saturated colour can hold more
 * scene light in one channel than white does, and the multiplier rises to fit it rather than clip it.
 */
@InternalFilmstripApi
public fun media3HdrTexels(
  argb: IntArray,
  transfer: HdrTransfer,
): Media3HdrTexels {
  val light = authoredHdrTexels(argb)
  val working = FloatArray(argb.size * RGB)
  val nits = FloatArray(RGB)
  var hdrLuminance = media3WorkingLight(authoredHdrNits(WHITE), transfer).max()

  for (index in argb.indices) {
    for (channel in 0 until RGB) nits[channel] = light[index * RGBA + channel]
    val texel = media3WorkingLight(nits, transfer)
    for (channel in 0 until RGB) {
      working[index * RGB + channel] = texel[channel]
      hdrLuminance = max(hdrLuminance, texel[channel])
    }
  }

  val codes =
    IntArray(argb.size) { index ->
      var color = argb[index] and ALPHA_MASK
      for (channel in 0 until RGB) {
        val code = (linearToSrgb(working[index * RGB + channel] / hdrLuminance) * MAX_CODE).roundToInt()
        color = color or (code shl CHANNEL_SHIFTS[channel])
      }
      color
    }
  return Media3HdrTexels(codes, hdrLuminance)
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

private const val MEDIUM_WEIGHT = 500

private const val WHITE = 0xFFFFFFFF.toInt()
private const val ALPHA_MASK = 0xFF shl 24
private const val MAX_CODE = 255f
private const val RGB = 3
private const val RGBA = 4
private val CHANNEL_SHIFTS = intArrayOf(16, 8, 0)

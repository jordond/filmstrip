package dev.jordond.filmstrip.effects.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Half
import androidx.core.graphics.createBitmap
import androidx.media3.common.OverlaySettings
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effects.media3WorkingLight
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.PictureColour
import dev.jordond.filmstrip.media.authoredHdrNits
import dev.jordond.filmstrip.media.linearToSrgb
import dev.jordond.filmstrip.media.pictureLight
import dev.jordond.filmstrip.media.pictureSrgbCodes
import dev.jordond.filmstrip.style.FontWeight
import dev.jordond.filmstrip.style.TextAlignment
import dev.jordond.filmstrip.style.TextStyle
import java.nio.ShortBuffer
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
 * Either way media3 gets a copy stored straight rather than premultiplied, since its overlay shader multiplies by the
 * alpha it reads itself. An SDR frame takes the colours [pictureSrgbCodes] gives the stored samples under [colour]. An
 * HDR one takes the codes [media3HdrTexels] writes for the light [pictureLight] gives them. A half float bitmap, the
 * way Android decodes a sixteen-bit picture, is read at its own depth rather than cut to eight bits, through the curve
 * [halfFloatCurve] reads off it.
 *
 * @param colour How the stored samples describe their colour, or null for colours that are already sRGB, such as
 * drawn text or an untagged picture.
 */
internal fun Bitmap.rasterFor(
  transfer: HdrTransfer?,
  colour: PictureColour? = null,
): Raster {
  val wide = wideSamples()
  if (wide == null) {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    if (transfer == null) {
      return Raster(straightCopy(pictureSrgbCodes(colour, pixels)), OverlaySettings.DEFAULT_HDR_LUMINANCE_MULTIPLIER)
    }
    val texels = media3HdrTexels(pixels, transfer, colour)
    return Raster(straightCopy(texels.argb), texels.hdrLuminance)
  }
  if (transfer == null) {
    return Raster(straightCopy(pictureSrgbCodes(colour, wide)), OverlaySettings.DEFAULT_HDR_LUMINANCE_MULTIPLIER)
  }
  val texels = media3HdrTexels(pictureLight(colour, wide), transfer)
  return Raster(straightCopy(texels.argb), texels.hdrLuminance)
}

// getPixels answers unpremultiplied colours whatever the bitmap stores, and setPixels on a bitmap that is not
// premultiplied keeps them that way.
private fun Bitmap.straightCopy(argb: IntArray): Bitmap =
  createBitmap(width, height).apply {
    isPremultiplied = false
    setPixels(argb, 0, width, 0, 0, width, height)
  }

// A half float bitmap's samples as sixteen-bit sRGB values, red, green, blue and alpha per texel, or null for any
// other config. A half float carries eleven significant bits, so a sample comes back within 16 of what the file
// stores. That is the depth Android's decoder hands back, since it has no sixteen-bit integer config.
private fun Bitmap.wideSamples(): ShortArray? {
  val curve = halfFloatCurve() ?: return null
  check(curve != HalfFloatCurve.Unknown) { "A half float bitmap in $colorSpace has no sRGB samples to read." }
  val halves = ShortArray(width * height * RGBA)
  copyPixelsToBuffer(ShortBuffer.wrap(halves))
  return ShortArray(halves.size) {
    val sample = Half.toFloat(halves[it]).coerceIn(0f, 1f)
    val encoded = if (curve == HalfFloatCurve.Linear && it % RGBA != ALPHA) linearToSrgb(sample) else sample
    (encoded * MAX_SAMPLE).roundToInt().toShort()
  }
}

/**
 * How a half float bitmap's colour channels are encoded, read off the colour space Android decoded it into.
 *
 * A sixteen-bit picture is asked for in extended sRGB, whose curve is the one an undescribed picture is read through.
 * Some versions decode it into linear extended sRGB whatever was asked for, so its channels are linear light and are
 * encoded to sRGB before they are read. Alpha is linear in both.
 */
internal enum class HalfFloatCurve {
  /**
   * Extended sRGB, read as it is.
   */
  Srgb,

  /**
   * Linear extended sRGB, encoded to sRGB before it is read.
   */
  Linear,

  /**
   * Any other colour space, which an overlay refuses rather than read through a curve it does not have.
   */
  Unknown,
}

/**
 * The curve this bitmap's colour channels are encoded with, or null when it does not hold half floats.
 */
internal fun Bitmap.halfFloatCurve(): HalfFloatCurve? {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || config != Bitmap.Config.RGBA_F16) return null
  return when (colorSpace) {
    ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB) -> HalfFloatCurve.Srgb
    ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB) -> HalfFloatCurve.Linear
    else -> HalfFloatCurve.Unknown
  }
}

/**
 * Overlay texels rewritten so media3's HDR overlay path lands each one on the light [pictureLight] gives its colour.
 *
 * media3 1.11.1 draws a text overlay onto an HDR frame as its HDR luminance multiplier times the sRGB decode of each
 * texel, with no change of primaries, then blends that with the frame by the texel's alpha. Each colour here is
 * [media3WorkingLight] of that light, divided by [hdrLuminance] and encoded back to sRGB, so media3's own read of it
 * undoes the mapping.
 *
 * @property argb Straight ARGB texels, each alpha as it came in.
 * @property hdrLuminance The multiplier the texels are written against, never below the level reference white reaches,
 * and raised to the brightest channel when a colour goes past it.
 */
@InternalFilmstripApi
public class Media3HdrTexels(
  public val argb: IntArray,
  public val hdrLuminance: Float,
)

/**
 * Maps straight ARGB [argb] texels onto the codes media3 reads back as their light on a frame of [transfer].
 *
 * The light is what [pictureLight] gives each texel under [colour], so an untagged texel lands on its authored light.
 * Reference white lands on the top code, so on a PQ frame an untagged neutral texel keeps its own code and only a
 * saturated one moves, by the change of primaries. On an HLG frame a saturated colour can hold more scene light in one
 * channel than white does, and the multiplier rises to fit it rather than clip it.
 *
 * @param colour How the texels describe their colour, or null when they are authored sRGB.
 */
@InternalFilmstripApi
public fun media3HdrTexels(
  argb: IntArray,
  transfer: HdrTransfer,
  colour: PictureColour? = null,
): Media3HdrTexels = media3HdrTexels(pictureLight(colour, argb), transfer)

// Each texel of [light] is red, green and blue in nits and then alpha, as pictureLight writes it.
private fun media3HdrTexels(
  light: FloatArray,
  transfer: HdrTransfer,
): Media3HdrTexels {
  val count = light.size / RGBA
  val working = FloatArray(count * RGB)
  val nits = FloatArray(RGB)
  var hdrLuminance = media3WorkingLight(authoredHdrNits(WHITE), transfer).max()

  for (index in 0 until count) {
    for (channel in 0 until RGB) nits[channel] = light[index * RGBA + channel]
    val texel = media3WorkingLight(nits, transfer)
    for (channel in 0 until RGB) {
      working[index * RGB + channel] = texel[channel]
      hdrLuminance = max(hdrLuminance, texel[channel])
    }
  }

  val codes =
    IntArray(count) { index ->
      var color = (light[index * RGBA + RGB] * MAX_CODE).roundToInt() shl ALPHA_SHIFT
      for (channel in 0 until RGB) {
        val code = (linearToSrgb(working[index * RGB + channel] / hdrLuminance) * MAX_CODE).roundToInt()
        color = color or (code shl CHANNEL_SHIFTS[channel])
      }
      color
    }
  return Media3HdrTexels(codes, hdrLuminance)
}

/**
 * The bitmap's pixel size.
 */
internal fun Bitmap.size(): Size = Size(width, height)

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
private const val ALPHA_SHIFT = 24
private const val MAX_CODE = 255f
private const val MAX_SAMPLE = 65535f
private const val RGB = 3
private const val RGBA = 4
private const val ALPHA = 3
private val CHANNEL_SHIFTS = intArrayOf(16, 8, 0)

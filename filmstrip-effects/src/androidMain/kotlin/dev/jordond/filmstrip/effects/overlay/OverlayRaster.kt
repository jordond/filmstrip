package dev.jordond.filmstrip.effects.overlay

import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.os.Build
import android.util.Half
import androidx.core.graphics.createBitmap
import androidx.media3.common.OverlaySettings
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effects.media3WorkingLight
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.PictureColour
import dev.jordond.filmstrip.media.PictureCrop
import dev.jordond.filmstrip.media.PictureOrientation
import dev.jordond.filmstrip.media.authoredHdrNits
import dev.jordond.filmstrip.media.croppedSamples
import dev.jordond.filmstrip.media.linearToSrgb
import dev.jordond.filmstrip.media.orientedSamples
import dev.jordond.filmstrip.media.pictureLight
import dev.jordond.filmstrip.media.pictureSrgbCodes
import java.nio.ShortBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The band [rasterFor] holds beside the held samples and the floats it makes for a [width] by [height] text raster, or
 * null when the count does not fit in a [Long].
 *
 * [media3HdrTexels] holds the light media3 works in at three floats a texel and the codes it writes at four bytes a
 * texel, both at once with the floats. An SDR frame holds less but is counted the same, so the same text fits or is
 * refused whichever frame it lands on. Drawing holds 12 bytes a texel at its peak, the glyph bytes and the samples
 * made from them once the coverage bitmap is recycled, against the 40 counted with this band, the samples and the
 * floats.
 */
internal fun textBandBytes(
  width: Long,
  height: Long,
): Long? {
  if (width <= 0 || height <= 0) return 0L
  val perTexel = (WORKING_TEXEL_BYTES + CODE_TEXEL_BYTES).toLong()
  return if (width > Long.MAX_VALUE / perTexel / height) null else width * height * perTexel
}

/**
 * Prepares this text for a frame of [transfer], or for an SDR frame when it is null, the way [Bitmap.rasterFor]
 * prepares a sixteen-bit picture, or refuses it with the reason [textAllocationFailed] gives when Android cannot
 * allocate what that holds.
 *
 * @param rasterOf Makes the raster from the samples, the step every sixteen-bit read shares unless a test sets it.
 * @return The raster media3 draws, or why there is none.
 */
internal fun TextSamples.rasterFor(
  transfer: HdrTransfer?,
  rasterOf: (samples: ShortArray, size: Size, transfer: HdrTransfer?, colour: PictureColour?) -> Raster = ::rasterOf,
): TextRaster.Made<Raster> =
  try {
    TextRaster.Drawn(rasterOf(samples, size, transfer, null))
  } catch (_: OutOfMemoryError) {
    TextRaster.Refused(textAllocationFailed(size.width, size.height))
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
 * The stored samples are first cut to [crop] through [croppedSamples] and then laid out the way [orientation] shows
 * them through [orientedSamples], so the raster is the picture as shown, its sides swapped for a quarter turn.
 *
 * @param colour How the stored samples describe their colour, or null for colours that are already sRGB, such as an
 * untagged picture.
 * @param orientation The turn the picture's own tag asks for, or [PictureOrientation.Normal] to keep the stored layout.
 * @param crop The texels of the stored samples kept, or null to keep them all. It has to lie within the bitmap.
 */
internal fun Bitmap.rasterFor(
  transfer: HdrTransfer?,
  colour: PictureColour? = null,
  orientation: PictureOrientation = PictureOrientation.Normal,
  crop: PictureCrop? = null,
): Raster {
  val stored = size()
  val kept = crop?.size ?: stored
  val shown = orientation.orientedSize(kept)
  val wide = wideSamples()
  if (wide != null) {
    val cut = crop?.let { croppedSamples(it, wide, stored) } ?: wide
    return rasterOf(orientedSamples(orientation, cut, kept), shown, transfer, colour)
  }
  val pixels = IntArray(width * height)
  getPixels(pixels, 0, width, 0, 0, width, height)
  val cut = crop?.let { croppedSamples(it, pixels, stored) } ?: pixels
  val oriented = orientedSamples(orientation, cut, kept)
  if (transfer == null) return sdrRaster(shown, pictureSrgbCodes(colour, oriented))
  val texels = media3HdrTexels(oriented, transfer, colour)
  return Raster(straightBitmap(shown, texels.argb), texels.hdrLuminance)
}

// Straight sixteen-bit RGBA samples of a raster of size, read at their full depth.
private fun rasterOf(
  samples: ShortArray,
  size: Size,
  transfer: HdrTransfer?,
  colour: PictureColour?,
): Raster {
  if (transfer == null) return sdrRaster(size, pictureSrgbCodes(colour, samples))
  val texels = media3HdrTexels(pictureLight(colour, samples), transfer)
  return Raster(straightBitmap(size, texels.argb), texels.hdrLuminance)
}

private fun sdrRaster(
  size: Size,
  argb: IntArray,
): Raster = Raster(straightBitmap(size, argb), OverlaySettings.DEFAULT_HDR_LUMINANCE_MULTIPLIER)

// getPixels answers unpremultiplied colours whatever the bitmap stores, and setPixels on a bitmap that is not
// premultiplied keeps the colours it is handed that way.
private fun straightBitmap(
  size: Size,
  argb: IntArray,
): Bitmap {
  val copy = createBitmap(size.width, size.height)
  var filled = false
  try {
    copy.isPremultiplied = false
    copy.setPixels(argb, 0, size.width, 0, 0, size.width, size.height)
    filled = true
  } finally {
    if (!filled) copy.recycle()
  }
  return copy
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
    (encoded * TOP_SAMPLE).roundToInt().toShort()
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
      var color = (light[index * RGBA + RGB] * TOP_CODE).roundToInt() shl ALPHA_SHIFT
      for (channel in 0 until RGB) {
        val code = (linearToSrgb(working[index * RGB + channel] / hdrLuminance) * TOP_CODE).roundToInt()
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

private const val WHITE = 0xFFFFFFFF.toInt()
private const val ALPHA_SHIFT = 24
private const val RGB = 3
private const val RGBA = 4
private const val ALPHA = 3
private val CHANNEL_SHIFTS = intArrayOf(16, 8, 0)

// What media3HdrTexels holds beside the floats it reads, the light it works in and the codes it writes.
private const val WORKING_TEXEL_BYTES = RGB * Float.SIZE_BYTES
private const val CODE_TEXEL_BYTES = Int.SIZE_BYTES

package dev.jordond.filmstrip.ffmpeg.internal.picture

import dev.jordond.filmstrip.color.ycbcrCodes
import dev.jordond.filmstrip.effects.overlay.DecodedPicture
import dev.jordond.filmstrip.effects.overlay.StoredSamples
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.heif.HeifAlpha
import dev.jordond.filmstrip.picture.heif.HeifOverlay
import kotlin.math.roundToInt

/**
 * The alpha plane ffmpeg decoded for an AVIF or HEIC, one sixteen-bit code a texel in rows, and whether the picture's
 * color is stored multiplied by it.
 *
 * A premultiplied picture's color is divided by its alpha on the way in, clamped to full scale, so every texel comes
 * back straight the way [StoredSamples] holds it. A clear texel keeps the color it stores.
 */
internal class PictureAlpha(
  val size: Size,
  private val codes: IntArray,
  private val premultiplied: Boolean,
) {
  /**
   * The alpha of the texel at [at] as a fraction of full scale, with [rgb], that texel's color as fractions of full
   * scale, made straight in place.
   */
  fun straighten(
    at: Int,
    rgb: DoubleArray,
  ): Double {
    val alpha = codes[at] / WIDE
    if (premultiplied && alpha > 0.0) {
      for (channel in rgb.indices) rgb[channel] = (rgb[channel] / alpha).coerceAtMost(1.0)
    }
    return alpha
  }

  /**
   * [picture] with this plane as the alpha of every texel, at its own depth, and its color made straight.
   *
   * The calling thread's interrupt is checked before each row.
   *
   * @throws InterruptedException when the calling thread is interrupted, with its interrupt cleared.
   */
  @Throws(InterruptedException::class)
  fun appliedTo(picture: DecodedPicture): DecodedPicture {
    val rgb = DoubleArray(RGB)
    val width = picture.size.width
    val samples =
      when (val stored = picture.samples) {
        is StoredSamples.Argb -> {
          val argb = stored.argb.copyOf()
          for (at in argb.indices) {
            if (at % width == 0) interruptible(at / width)
            val texel = argb[at]
            for (channel in 0 until RGB) rgb[channel] = (texel ushr shiftOf(channel) and BYTE) / BYTE_MAX
            val alpha = straighten(at, rgb)
            argb[at] =
              rgb.foldIndexed((alpha * BYTE_MAX).roundToInt() shl ALPHA_SHIFT) { channel, packed, level ->
                packed or ((level * BYTE_MAX).roundToInt() shl shiftOf(channel))
              }
          }
          StoredSamples.Argb(argb)
        }
        is StoredSamples.Rgba16 -> {
          val rgba = stored.rgba.copyOf()
          for (at in 0 until rgba.size / CHANNELS) {
            if (at % width == 0) interruptible(at / width)
            for (channel in 0 until RGB) rgb[channel] = (rgba[at * CHANNELS + channel].toInt() and WIDE_MASK) / WIDE
            val alpha = straighten(at, rgb)
            for (channel in 0 until RGB) rgba[at * CHANNELS + channel] = (rgb[channel] * WIDE).roundToInt().toShort()
            rgba[at * CHANNELS + RGB] = (alpha * WIDE).roundToInt().toShort()
          }
          StoredSamples.Rgba16(rgba)
        }
      }
    return DecodedPicture(picture.size, samples, picture.color, picture.limits)
  }
}

/**
 * The alpha plane [plane] holds, the grey PNG ffmpeg wrote it out as, for an alpha item of [alpha].
 *
 * [plane] holds the item's raw codes scaled to sixteen bits. A limited range item's codes are expanded to full range
 * through [fullRangeAlpha] at the item's depth, and a full range item's are kept as they are. The calling thread's
 * interrupt is checked before each row.
 *
 * @throws InterruptedException when the calling thread is interrupted, with its interrupt cleared.
 */
@Throws(InterruptedException::class)
internal fun pictureAlpha(
  plane: DecodedPicture,
  alpha: HeifAlpha,
): PictureAlpha {
  val width = plane.size.width
  val samples = plane.samples
  val codes = IntArray(plane.size.width * plane.size.height)
  for (at in codes.indices) {
    if (at % width == 0) interruptible(at / width)
    val code =
      when (samples) {
        is StoredSamples.Argb -> (samples.argb[at] ushr RED_SHIFT and BYTE) * BYTE_TO_WIDE
        is StoredSamples.Rgba16 -> samples.rgba[at * CHANNELS].toInt() and WIDE_MASK
      }
    codes[at] = if (alpha.fullRange) code else fullRangeAlpha(code, alpha.bitDepth)
  }
  return PictureAlpha(plane.size, codes, alpha.premultiplied)
}

/**
 * The full range sixteen-bit alpha code of [code], a limited range sample of [bitDepth] bits scaled to sixteen.
 *
 * Clear sits at 16 and opaque at 235, each shifted up by the bits past eight and scaled to sixteen bits the way [code]
 * is. The code is stretched linearly from clear to opaque over the whole sixteen-bit range, rounded to the nearest
 * code and clamped to it.
 */
internal fun fullRangeAlpha(
  code: Int,
  bitDepth: Int,
): Int {
  val scale = WIDE / ((1 shl bitDepth) - 1)
  val clear = (LIMITED_CLEAR shl (bitDepth - BYTE_BITS)) * scale
  val opaque = (LIMITED_OPAQUE shl (bitDepth - BYTE_BITS)) * scale
  return ((code - clear) * WIDE / (opaque - clear)).roundToInt().coerceIn(0, WIDE_MASK)
}

/**
 * How [alpha]'s plane is put together as one sixteen-bit grey plane of raw codes, or null for an alpha item coded as
 * one image, which [ALPHA_FORMAT] reads alone.
 *
 * One laid out from tiles is put together by [gridAssembly] and run through [ALPHA_FORMAT]. One drawn over a canvas by
 * [overlayAlpha]. Every way the plane is marked full range before it is converted, so it arrives as the item's raw
 * codes scaled to sixteen bits whatever range its codestream states.
 */
internal fun alphaAssembly(alpha: HeifAlpha): Assembly? {
  alpha.grid?.let { return gridAssembly(it, ALPHA_FORMAT) }
  return alpha.overlay?.let { overlayAlpha(it, alpha) }
}

/**
 * How [overlay], the layout of the alpha item [alpha], is put together as one sixteen-bit grey plane of raw codes.
 *
 * The canvas is the luma sample [HeifAlpha.reading] codes the fill's red, green and blue as, at the item's depth and
 * range, laid by [planesAssembly] as grey samples of that depth. Each input is marked full range and taken to grey at
 * the same depth, so the canvas and the inputs hold raw codes alike and go to sixteen bits together.
 */
internal fun overlayAlpha(
  overlay: HeifOverlay,
  alpha: HeifAlpha,
): Assembly {
  val fill = overlay.fill
  val luma = alpha.reading.ycbcrCodes(fill.red, fill.green, fill.blue, alpha.bitDepth, alpha.fullRange).first()
  val gray = planarGray(alpha.bitDepth)
  val canvas = PlanesCanvas(gray, intArrayOf(luma), fullRange = true)
  return planesAssembly(overlay, "setparams=range=pc,format=$gray", canvas, ",format=$WIDE_GRAY")
}

// The grey pixel format of [bitDepth] bits a sample, little-endian past eight.
private fun planarGray(bitDepth: Int): String = if (bitDepth == EIGHT_BITS) "gray" else "gray${bitDepth}le"

// Throws when the calling thread is interrupted, clearing its interrupt, naming the [row] the plane stopped at.
@Throws(InterruptedException::class)
private fun interruptible(row: Int) {
  if (Thread.interrupted()) throw InterruptedException("The picture's alpha was interrupted at row $row.")
}

// Where red, green or blue sits in a packed ARGB texel, by its [channel] index.
private fun shiftOf(channel: Int): Int = RED_SHIFT - channel * BYTE_BITS

/**
 * Why a picture whose alpha plane is not the size of its color is not drawn.
 */
internal fun alphaSizeReason(
  alpha: Size,
  color: Size,
): String =
  "The overlay image's alpha plane is ${alpha.width}x${alpha.height} and its picture ${color.width}x" +
    "${color.height}, so this overlay picture cannot be drawn."

private const val WIDE_GRAY = "gray16le"

/**
 * The filter that takes an alpha plane to sixteen-bit grey raw codes, marked full range before it is converted.
 */
internal const val ALPHA_FORMAT = "setparams=range=pc,format=$WIDE_GRAY"
private const val RGB = 3
private const val CHANNELS = 4
private const val BYTE = 0xFF
private const val BYTE_MAX = 255.0
private const val BYTE_BITS = 8
private const val BYTE_TO_WIDE = 257
private const val RED_SHIFT = 16
private const val ALPHA_SHIFT = 24
private const val WIDE = 65535.0
private const val WIDE_MASK = 0xFFFF
private const val LIMITED_CLEAR = 16
private const val LIMITED_OPAQUE = 235

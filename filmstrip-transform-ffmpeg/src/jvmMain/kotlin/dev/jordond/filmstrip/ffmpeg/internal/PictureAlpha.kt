package dev.jordond.filmstrip.ffmpeg.internal

import dev.jordond.filmstrip.effect.Degradation
import dev.jordond.filmstrip.effect.DegradationReason
import dev.jordond.filmstrip.effects.overlay.DecodedPicture
import dev.jordond.filmstrip.effects.overlay.StoredSamples
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.HeifAlpha
import dev.jordond.filmstrip.internal.HeifGrid
import kotlin.math.roundToInt

/**
 * The alpha plane ffmpeg decoded for an AVIF or HEIC, one sixteen-bit code a texel in rows, and whether the picture's
 * colour is stored multiplied by it.
 *
 * A premultiplied picture's colour is divided by its alpha on the way in, clamped to full scale, so every texel comes
 * back straight the way [StoredSamples] holds it. A clear texel keeps the colour it stores.
 */
internal class PictureAlpha(
  val size: Size,
  private val codes: IntArray,
  private val premultiplied: Boolean,
) {
  /**
   * The alpha of the texel at [at] as a fraction of full scale, with [rgb], that texel's colour as fractions of full
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
   * [picture] with this plane as the alpha of every texel, at its own depth, and its colour made straight.
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
    return DecodedPicture(picture.size, samples, picture.colour, picture.limits)
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
 * The arguments that decode [alpha]'s plane as a sixteen-bit grey PNG on stdout, for the input ffmpeg was given ahead
 * of them.
 *
 * An alpha item coded as one image is mapped by its id, which is the id ffmpeg gives the stream it reads the item as.
 * One laid out from tiles is assembled by [tiledAlpha]. Either way the plane is marked full range before it is
 * converted, so it arrives as the item's raw codes scaled to sixteen bits whatever range its codestream states.
 */
internal fun alphaFrame(alpha: HeifAlpha): List<String> {
  val graph =
    when (val grid = alpha.grid) {
      null -> listOf("-map", "0:#${alpha.item}", "-vf", ALPHA_FORMAT)
      else -> listOf("-filter_complex", tiledAlpha(grid))
    }
  return graph + listOf("-frames:v", "1", "-c:v", "png", "-f", "image2pipe", "-")
}

/**
 * The filter graph that assembles [grid]'s tiles into one sixteen-bit grey plane of raw codes.
 *
 * Each tile is mapped by its id and stacked in rows by [HeifGrid.columns], then the whole is cropped to [HeifGrid.size]
 * and marked full range before it is converted.
 */
internal fun tiledAlpha(grid: HeifGrid): String {
  val inputs = grid.tiles.joinToString("") { "[0:#$it]" }
  val layout =
    grid.tiles.indices.joinToString("|") { index ->
      "${offset(index % grid.columns, "w0")}_${offset(index / grid.columns, "h0")}"
    }
  val stacked = if (grid.tiles.size == 1) "" else "xstack=inputs=${grid.tiles.size}:layout=$layout,"
  return "$inputs${stacked}crop=${grid.size.width}:${grid.size.height}:0:0,$ALPHA_FORMAT"
}

// Throws when the calling thread is interrupted, clearing its interrupt, naming the [row] the plane stopped at.
@Throws(InterruptedException::class)
private fun interruptible(row: Int) {
  if (Thread.interrupted()) throw InterruptedException("The picture's alpha was interrupted at row $row.")
}

// Where red, green or blue sits in a packed ARGB texel, by its [channel] index.
private fun shiftOf(channel: Int): Int = RED_SHIFT - channel * BYTE_BITS

// [count] tiles of [side] along one axis, as an xstack layout expression.
private fun offset(
  count: Int,
  side: String,
): String = if (count == 0) "0" else List(count) { side }.joinToString("+")

/**
 * Whether ffmpeg's [reason] for failing an alpha decode is that it lists no stream for the item, which is how ffmpeg
 * 6.1 reads every AVIF alpha item.
 */
internal fun unlistedAlpha(reason: String): Boolean = UNLISTED in reason

/**
 * The cause an overlay resolves degraded by when this ffmpeg lists no stream for its picture's alpha item.
 */
internal val UNLISTED_ALPHA: Degradation =
  Degradation(
    DegradationReason.AlphaDiscarded,
    "This ffmpeg reads no stream for the overlay image's alpha item, as ffmpeg 6.1 does not, so the picture draws " +
      "opaque whatever alpha its file carries. ffmpeg 9 reads it.",
  )

/**
 * Why a picture whose alpha plane is not the size of its colour is not drawn.
 */
internal fun alphaSizeReason(
  alpha: Size,
  colour: Size,
): String =
  "The overlay image's alpha plane is ${alpha.width}x${alpha.height} and its picture ${colour.width}x" +
    "${colour.height}, so this overlay picture cannot be drawn."

private const val ALPHA_FORMAT = "setparams=range=pc,format=gray16le"
private const val UNLISTED = "matches no streams"
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

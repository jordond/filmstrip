package dev.jordond.filmstrip.ffmpeg.internal.picture

import dev.jordond.filmstrip.color.YcbcrEvaluator
import dev.jordond.filmstrip.effects.overlay.DecodedPicture
import dev.jordond.filmstrip.effects.overlay.StoredSamples
import dev.jordond.filmstrip.geometry.Size
import java.io.InputStream
import java.io.OutputStream
import kotlin.math.roundToInt

/**
 * The luma, blue difference and red difference planes of one 4:4:4 frame, each [size] samples of [bitDepth] bits,
 * stored one byte a sample at eight bits and two little-endian bytes past that.
 */
internal class YuvPlanes(
  val size: Size,
  val bitDepth: Int,
  val planes: List<ByteArray>,
)

/**
 * The first frame of the `yuv4mpegpipe` stream [stream] carries, read as 4:4:4 planes of [bitDepth] bits, or null when
 * the stream is not one, names another layout or depth, or ends before the frame does.
 *
 * Whatever follows the frame is read to the end and dropped, so the process writing it never blocks on a full pipe.
 */
internal fun yuv4mpegPlanes(
  stream: InputStream,
  bitDepth: Int,
): YuvPlanes? {
  val planes = framePlanes(stream, bitDepth)
  stream.transferTo(OutputStream.nullOutputStream())
  return planes
}

private fun framePlanes(
  stream: InputStream,
  bitDepth: Int,
): YuvPlanes? {
  val header = stream.line()?.split(' ') ?: return null
  if (header.first() != Y4M_SIGNATURE) return null
  val width = header.firstNotNullOfOrNull { it.removePrefixOrNull("W")?.toIntOrNull() } ?: return null
  val height = header.firstNotNullOfOrNull { it.removePrefixOrNull("H")?.toIntOrNull() } ?: return null
  val layout = header.firstNotNullOfOrNull { it.removePrefixOrNull("C") }
  val expected = if (bitDepth == EIGHT_BITS) "444" else "444p$bitDepth"
  if (layout != expected || width <= 0 || height <= 0) return null
  if (stream.line()?.startsWith(Y4M_FRAME) != true) return null

  val bytes = width.toLong() * height * if (bitDepth == EIGHT_BITS) 1 else 2
  if (bytes > Int.MAX_VALUE) return null
  val planes = List(PLANES) { stream.readNBytes(bytes.toInt()) }
  if (planes.any { it.size.toLong() != bytes }) return null
  return YuvPlanes(Size(width, height), bitDepth, planes)
}

/**
 * [planes] turned into RGB by [evaluator], one texel per sample, carrying no color description.
 *
 * Eight-bit planes come back as [StoredSamples.Argb] and deeper ones as [StoredSamples.Rgba16], each channel the
 * evaluator's value rounded to the nearest code. Each texel takes its alpha from [alpha], which also makes a
 * premultiplied color straight, and is opaque without one. The calling thread's interrupt is checked before each
 * row, so a cancelled read stops part way through a large picture.
 *
 * @param beforeRow Runs on the calling thread ahead of each row's interrupt check, given that row.
 * @throws InterruptedException when the calling thread is interrupted, with its interrupt cleared.
 */
@Throws(InterruptedException::class)
internal fun decodedPlanes(
  planes: YuvPlanes,
  evaluator: YcbcrEvaluator,
  alpha: PictureAlpha? = null,
  beforeRow: (row: Int) -> Unit = {},
): DecodedPicture {
  val (y, cb, cr) = planes.planes
  val wide = planes.bitDepth > EIGHT_BITS
  val width = planes.size.width
  val height = planes.size.height
  val argb = if (wide) null else IntArray(width * height)
  val rgba = if (wide) ShortArray(width * height * CHANNELS) else null

  val rgb = DoubleArray(RGB)
  for (row in 0 until height) {
    beforeRow(row)
    if (Thread.interrupted()) throw InterruptedException("The picture's planes were interrupted at row $row.")
    for (at in row * width until (row + 1) * width) {
      evaluator.rgb(y.sample(at, wide), cb.sample(at, wide), cr.sample(at, wide), rgb)
      val coverage = alpha?.straighten(at, rgb) ?: 1.0
      if (argb != null) {
        val a = (coverage * BYTE).roundToInt()
        val r = (rgb[0] * BYTE).roundToInt()
        val g = (rgb[1] * BYTE).roundToInt()
        val b = (rgb[2] * BYTE).roundToInt()
        argb[at] = (a shl ALPHA_SHIFT) or (r shl RED_SHIFT) or (g shl BYTE_BITS) or b
      } else {
        val texel = checkNotNull(rgba)
        for (channel in 0 until RGB) texel[at * CHANNELS + channel] = (rgb[channel] * WIDE).roundToInt().toShort()
        texel[at * CHANNELS + RGB] = (coverage * WIDE).roundToInt().toShort()
      }
    }
  }
  val samples = if (argb != null) StoredSamples.Argb(argb) else StoredSamples.Rgba16(checkNotNull(rgba))
  return DecodedPicture(planes.size, samples, color = null)
}

// The sample at [at], two little-endian bytes when [wide] and one otherwise.
private fun ByteArray.sample(
  at: Int,
  wide: Boolean,
): Int =
  if (wide) {
    (this[at * 2].toInt() and BYTE) or ((this[at * 2 + 1].toInt() and BYTE) shl BYTE_BITS)
  } else {
    this[at].toInt() and BYTE
  }

private const val Y4M_SIGNATURE = "YUV4MPEG2"
private const val Y4M_FRAME = "FRAME"
private const val PLANES = 3
private const val RGB = 3
private const val CHANNELS = 4
private const val BYTE = 0xFF
private const val BYTE_BITS = 8
private const val RED_SHIFT = 16
private const val ALPHA_SHIFT = 24
private const val WIDE = 65535.0

package dev.jordond.filmstrip.ffmpeg.support

import dev.jordond.filmstrip.effects.filters.crt.Crt
import dev.jordond.filmstrip.effects.filters.crt.CrtParams
import dev.jordond.filmstrip.effects.filters.crt.CrtSignal
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

// Worst measured: 2.75 on luma on an interior read on ffmpeg 9.0.2 arm64, and 2.34 on chroma on 8.0.1 x86_64. Only the
// reads were measured, what puts them there was not isolated.
internal const val CRT_LUMA_CODES = 3.0
internal const val CRT_CHROMA_CODES = 3.0

/**
 * How far a frame of a [Crt] lands from [CrtSignal], in 8 bit limited codes.
 *
 * @property luma The worst luma code, which holds every pattern the signal draws.
 * @property chroma The worst chroma sample against the mean of the pixels it covers, since 4:2:0 holds no less.
 * @property change How far the middle of the frame's luma moved from the plain render, which says the look is there.
 */
internal class CrtMiss(
  val luma: Double,
  val chroma: Double,
  val change: Double,
)

/**
 * Reads [frame], a [crt] drawn on the picture [plain] holds as RGBA with no look on it, against [CrtSignal].
 *
 * The reads spread over the frame, cross the curve's edge one pixel either side on each side, and sit around the
 * middle. Chroma reads are the samples those pixels land in.
 */
internal fun crtMissOf(
  frame: Yuv420Frame,
  plain: ByteArray,
  crt: Crt,
): CrtMiss {
  val width = frame.width
  val height = frame.height
  val params = CrtParams.of(crt)
  val wanted = { x: Int, y: Int ->
    CrtSignal.pixelAt((x + 0.5) / width, (y + 0.5) / height, width, height, params) { u, v ->
      plain.bilinear(width, height, u, v)
    }
  }
  val rows =
    readsAcross(height) { row -> crtLit(width / 2, row, width, height, params.barrelK) }
  val columns =
    readsAcross(width) { column -> crtLit(column, height / 2, width, height, params.barrelK) }
  var luma = 0.0
  var chroma = 0.0
  rows.forEach { y ->
    columns.forEach { x ->
      luma = max(luma, abs(frame.luma(x, y) - lumaCode(wanted(x, y))))
      val sampleX = x / 2
      val sampleY = y / 2
      if (sampleX * 2 + 1 < width && sampleY * 2 + 1 < height) {
        val covered =
          listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1).map { (dx, dy) -> wanted(sampleX * 2 + dx, sampleY * 2 + dy) }
        chroma = max(chroma, abs(frame.cb(sampleX, sampleY) - covered.map(::cbCode).average()))
        chroma = max(chroma, abs(frame.cr(sampleX, sampleY) - covered.map(::crCode).average()))
      }
    }
  }
  var change = 0.0
  (-MIDDLE_READS..MIDDLE_READS).forEach { offset ->
    val x = width / 2 + offset
    val y = height / 2 + offset
    val untouched = lumaCode(DoubleArray(RGB_CHANNELS) { plain.channelAt(width, x, y, it) / FULL })
    change = max(change, abs(frame.luma(x, y) - untouched))
  }
  return CrtMiss(luma, chroma, change)
}

/**
 * Whether a pixel holds any of the picture under a curve of [k].
 */
internal fun crtLit(
  column: Int,
  row: Int,
  width: Int,
  height: Int,
  k: Double,
): Boolean = CrtSignal.coverageAt((column + 0.5) / width, (row + 0.5) / height, width, height, k) > 0.0

private fun readsAcross(
  size: Int,
  lit: (Int) -> Boolean,
): List<Int> {
  val edge = (0 until size).first(lit)
  val spread = (0 until SPREAD_READS).map { it * (size - 1) / (SPREAD_READS - 1) }
  val atEdge = listOf(edge - 1, edge, edge + 1, size - edge - 2, size - edge - 1, size - edge)
  val middle = (-MIDDLE_READS..MIDDLE_READS).map { size / 2 + it }
  return (spread + atEdge + middle).filter { it in 0 until size }.distinct()
}

// The frame read the way the lowering and a texture filter do, between pixel centres and clamped at the edge.
private fun ByteArray.bilinear(
  width: Int,
  height: Int,
  u: Double,
  v: Double,
): DoubleArray {
  val x = u * width - 0.5
  val y = v * height - 0.5
  val left = floor(x).toInt()
  val top = floor(y).toInt()
  val across = x - left
  val down = y - top
  val texel = { column: Int, row: Int, channel: Int ->
    channelAt(width, column.coerceIn(0, width - 1), row.coerceIn(0, height - 1), channel) / FULL
  }
  return DoubleArray(RGB_CHANNELS) {
    val upper = texel(left, top, it) + (texel(left + 1, top, it) - texel(left, top, it)) * across
    val lower = texel(left, top + 1, it) + (texel(left + 1, top + 1, it) - texel(left, top + 1, it)) * across
    upper + (lower - upper) * down
  }
}

private fun lumaOf(rgb: DoubleArray): Double =
  BT709_KR * rgb[0] + (1.0 - BT709_KR - BT709_KB) * rgb[1] + BT709_KB * rgb[2]

/**
 * The 8 bit limited luma code of an R'G'B' read.
 */
internal fun lumaCode(rgb: DoubleArray): Double = LUMA_BLACK + LUMA_CODES * lumaOf(rgb)

/**
 * The 8 bit limited Cb code of an R'G'B' read.
 */
internal fun cbCode(rgb: DoubleArray): Double =
  MID_CHROMA + CHROMA_CODES * (rgb[2] - lumaOf(rgb)) / (2.0 * (1.0 - BT709_KB))

/**
 * The 8 bit limited Cr code of an R'G'B' read.
 */
internal fun crCode(rgb: DoubleArray): Double =
  MID_CHROMA + CHROMA_CODES * (rgb[0] - lumaOf(rgb)) / (2.0 * (1.0 - BT709_KR))

private const val BT709_KR = 0.2126
private const val BT709_KB = 0.0722
private const val RGB_CHANNELS = 3
private const val SPREAD_READS = 9
private const val MIDDLE_READS = 6
private const val LUMA_BLACK = 16.0
private const val LUMA_CODES = 219.0
private const val MID_CHROMA = 128.0
private const val CHROMA_CODES = 224.0

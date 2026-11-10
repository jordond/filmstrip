package dev.jordond.filmstrip.ffmpeg.support

import dev.jordond.filmstrip.effects.filters.film.Film
import dev.jordond.filmstrip.effects.filters.film.FilmParams
import dev.jordond.filmstrip.effects.filters.film.FilmSignal
import dev.jordond.filmstrip.effects.filters.film.HalationGlow
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeLessThan
import kotlin.math.abs
import kotlin.math.floor

internal const val FILM_READ_SLOT = 12

// A film's limits against the signal, in the planes the frame holds. A conversion to RGBA after them moves with the
// build, up to 2.59 codes from the planes on ffmpeg 8.0.1 x86_64 and 1.18 on 9.0.2 arm64. In planes the most measured
// on either build is 1.42 in luma and 1.18 in chroma. Half the grain misses by 2.05 and 70% of the glow by 2.43.
internal const val FILM_LUMA_CODES = 1.5
internal const val FILM_CHROMA_CODES = 1.5

private const val EDGE_MARGIN = 6

/**
 * One plane's read of a film at a pixel, how far it sat from the signal and how far it may.
 */
internal class FilmRead(
  val label: String,
  val miss: Double,
  val limit: Double,
)

/**
 * Reads [frame] of [film] against [FilmSignal] over [plain], a render of the same clip with no film on it.
 *
 * The glow's source and the pixel under it both come from [plain], read where [FilmSignal.weaveAt] moves the pixel.
 * Two pixels hold the same chroma on a 4:2:0 frame, so the columns sit [EDGE_MARGIN] off the picture's edge at the
 * middle of the frame. Each of [extra] is read as well, and every read is taken at the grain's [tick].
 */
internal fun filmReads(
  frame: Yuv420Frame,
  plain: Yuv420Frame,
  film: Film,
  extra: List<Pair<Int, Int>> = emptyList(),
  tick: Int = filmReadTick(),
): List<FilmRead> {
  val width = frame.width
  val height = frame.height
  val params = FilmParams.of(film)
  val offset = FilmSignal.weaveAt(params.seed, tick, params.weave)
  val moved = { x: Int, y: Int -> plain.rgbBetween(x + offset[0] * width, y + offset[1] * height) }
  val glow = if (params.halationGain > 0.0) HalationGlow.of(width, height, moved) else null
  val columns = listOf(2, width / 4, width / 2 - EDGE_MARGIN, width / 2 + EDGE_MARGIN, 3 * width / 4, width - 3)
  val points = listOf(2, height / 3, height / 2, height - 3).flatMap { y -> columns.map { x -> x to y } } + extra
  val pixel = { x: Int, y: Int ->
    val codes = moved(x, y)
    planeCodesOf(
      FilmSignal.pixelAt(
        codes[0],
        codes[1],
        codes[2],
        glow?.at(x, y) ?: doubleArrayOf(0.0, 0.0, 0.0),
        FilmSignal.grainCell(x, y, width, height),
        tick,
        params,
        (x + HALF) / width,
        (y + HALF) / height,
        width.toDouble() / height,
      ),
    )
  }
  return points.flatMap { (x, y) ->
    run {
      // A chroma sample holds the mean of the two by two pixels it covers.
      val block = listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1).map { (dx, dy) -> pixel(x / 2 * 2 + dx, y / 2 * 2 + dy) }
      val own = pixel(x, y)
      val wanted = DoubleArray(PLANES.size) { plane -> if (plane == 0) own[0] else block.sumOf { it[plane] } / 4 }
      val read = frame.planeCodesAt(x, y)
      PLANES.indices.map { plane ->
        FilmRead(
          "row $y column $x ${PLANES[plane]} of ${width}x$height read ${read[plane]} against ${wanted[plane]}",
          abs(read[plane] - wanted[plane]),
          if (plane == 0) FILM_LUMA_CODES else FILM_CHROMA_CODES,
        )
      }
    }
  }
}

/**
 * Asserts every read [filmReads] takes lands inside its limit.
 */
internal fun assertFilmLanded(
  frame: Yuv420Frame,
  plain: Yuv420Frame,
  film: Film,
  extra: List<Pair<Int, Int>> = emptyList(),
  tick: Int = filmReadTick(),
) = filmReads(frame, plain, film, extra, tick).forEach { read ->
  withClue(read.label) { read.miss shouldBeLessThan read.limit }
}

/**
 * The tick [filmReads] reads a frame at.
 */
internal fun filmReadTick(): Int = FilmSignal.grainTickAt(FILM_READ_SLOT.toDouble() / FILTER_RATE)

// The exact decode between the four pixels around x, y, each held at the frame's edge, the way the weave reads.
private fun Yuv420Frame.rgbBetween(
  x: Double,
  y: Double,
): DoubleArray {
  val left = floor(x).toInt()
  val top = floor(y).toInt()
  val fx = x - left
  val fy = y - top
  val at = { tx: Int, ty: Int -> rgbAt(tx.coerceIn(0, width - 1), ty.coerceIn(0, height - 1)) }
  val near = at(left, top).zip(at(left + 1, top)) { a, b -> a + (b - a) * fx }
  val far = at(left, top + 1).zip(at(left + 1, top + 1)) { a, b -> a + (b - a) * fx }
  return DoubleArray(near.size) { near[it] + (far[it] - near[it]) * fy }
}

private const val HALF = 0.5

/**
 * The luma, Cb and Cr codes the unit [rgb] encodes to in 8 bit limited range BT.709.
 */
internal fun planeCodesOf(rgb: DoubleArray): DoubleArray {
  val (y, cb, cr) = yccOf(rgb)
  return doubleArrayOf(
    VHS_LUMA_BLACK + VHS_LUMA_SPAN * y,
    VHS_CHROMA_ZERO + VHS_CHROMA_SPAN * cb,
    VHS_CHROMA_ZERO + VHS_CHROMA_SPAN * cr,
  )
}

/**
 * The luma, Cb and Cr codes this frame holds at a pixel.
 */
internal fun Yuv420Frame.planeCodesAt(
  x: Int,
  y: Int,
): DoubleArray = doubleArrayOf(luma(x, y).toDouble(), cb(x / 2, y / 2).toDouble(), cr(x / 2, y / 2).toDouble())

// An exact BT.709 decode with no clip and no rounding.
private fun Yuv420Frame.rgbAt(
  x: Int,
  y: Int,
): DoubleArray {
  val (lumaCode, cbCode, crCode) = planeCodesAt(x, y)
  val luma = (lumaCode - VHS_LUMA_BLACK) / VHS_LUMA_SPAN
  val red = luma + 2.0 * (1.0 - VHS_KR) * (crCode - VHS_CHROMA_ZERO) / VHS_CHROMA_SPAN
  val blue = luma + 2.0 * (1.0 - VHS_KB) * (cbCode - VHS_CHROMA_ZERO) / VHS_CHROMA_SPAN
  return doubleArrayOf(red, (luma - VHS_KR * red - VHS_KB * blue) / (1.0 - VHS_KR - VHS_KB), blue)
}

private val PLANES = listOf("luma", "Cb", "Cr")

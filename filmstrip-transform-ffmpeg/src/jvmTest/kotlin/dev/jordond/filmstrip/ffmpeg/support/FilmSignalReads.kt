package dev.jordond.filmstrip.ffmpeg.support

import dev.jordond.filmstrip.effects.filters.film.Film
import dev.jordond.filmstrip.effects.filters.film.FilmParams
import dev.jordond.filmstrip.effects.filters.film.FilmSignal
import dev.jordond.filmstrip.effects.filters.film.HalationGlow
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeLessThan
import kotlin.math.abs

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
 * The glow's source and the pixel under it both come from [plain]. Two pixels hold the same chroma on a 4:2:0 frame, so
 * the columns sit [EDGE_MARGIN] off the picture's edge at the middle of the frame.
 */
internal fun filmReads(
  frame: Yuv420Frame,
  plain: Yuv420Frame,
  film: Film,
): List<FilmRead> {
  val width = frame.width
  val height = frame.height
  val params = FilmParams.of(film)
  val glow = if (params.halationGain > 0.0) HalationGlow.of(width, height, plain::rgbAt) else null
  val tick = FilmSignal.grainTickAt(FILM_READ_SLOT.toDouble() / FILTER_RATE)
  val columns = listOf(2, width / 4, width / 2 - EDGE_MARGIN, width / 2 + EDGE_MARGIN, 3 * width / 4, width - 3)
  return listOf(2, height / 3, height / 2, height - 3).flatMap { y ->
    columns.flatMap { x ->
      val codes = plain.rgbAt(x, y)
      val wanted =
        planeCodesOf(
          FilmSignal.pixelAt(
            codes[0],
            codes[1],
            codes[2],
            glow?.at(x, y) ?: doubleArrayOf(0.0, 0.0, 0.0),
            FilmSignal.grainCell(x, y, width, height),
            tick,
            params,
          ),
        )
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
) = filmReads(frame, plain, film).forEach { read ->
  withClue(read.label) { read.miss shouldBeLessThan read.limit }
}

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

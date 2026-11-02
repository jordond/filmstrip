package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.media.heifWithExplicitCodestreamColour
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Holds every HEIC still, alone, as a grid and handed over as written, to the one decode measured for it on this
 * platform, read from the bitmap ImageIO decodes it to.
 *
 * On macOS that is the whole codes macOS 26.6.2 (25G83) decoded each to, [ycbcrToRgb]'s for most and the ones
 * [WHOLE_CODES_OFF_ROUNDED] lists for two. On the simulator it is the one decode [AppleStillSamples] gives. This fails
 * once an OS build decodes a still to any other whole codes, which says the table needs that build's codes.
 *
 * Also holds [AppleStillSamples.WHOLE_CODE_DRIFT] to the most those macOS codes sit off the unrounded reading.
 */
@OptIn(InternalFilmstripApi::class)
class AppleStillSamplesMeasureTest {
  private val heics =
    ColourPictures.HEICS_WITH_SIGNAL_VARIANTS + AppleStillSamples.BT709_MATRIX_OTHER_PRIMARIES +
      ColourPictures.HEIC_NCLX_BT709_FULL_EDGE_CHROMA

  @Test
  fun everyHeicDecodesToExactlyTheWholeCodesMeasuredForIt() {
    val misses =
      heics.flatMap { heic ->
        listOfNotNull(
          imageIoMiss("$heic", heifWithExplicitCodestreamColour(heic.bytes()).handed) {
            measured(heic, asWritten = false, it)
          },
          imageIoMiss("$heic grid", heifWithExplicitCodestreamColour(heic.gridBytes()).handed) {
            measured(heic, asWritten = false, it)
          },
          imageIoMiss("$heic as written", heic.bytes()) { measured(heic, asWritten = true, it) },
        )
      }

    assertTrue(misses.isEmpty(), "Measured decode misses on ${osBuild()}:\n" + misses.joinToString("\n"))
  }

  @Test
  fun wholeCodeDriftIsTheMostAMeasuredMacOsHeicDecodeSitsOffTheUnroundedReading() {
    val drifts =
      heics.flatMap { heic ->
        listOf(false, true).map { asWritten ->
          val decode = AppleStillSamples.heicDecode(heic, asWritten)
          val reading = AppleStillSamples.unroundedReading(decode)
          val codes = macOsCodes(decode)
          codes.indices.maxOf { abs(codes[it] - reading[it]) }
        }
      }
    val most = drifts.max()

    assertTrue(
      most <= AppleStillSamples.WHOLE_CODE_DRIFT && most > AppleStillSamples.WHOLE_CODE_DRIFT - DRIFT_STEP,
      "the most a measured decode sits off the unrounded reading is $most codes",
    )
  }

  // The one decode measured for heic on this platform. Asking for one unmeasured here fails the way the suite's does.
  private fun measured(
    heic: HeicPicture,
    asWritten: Boolean,
    read: ShortArray,
  ): List<ShortArray> {
    val platform = ApplePlatform.current()
    val decodes =
      if (asWritten) {
        AppleStillSamples.asWritten(heic, read, platform)
      } else {
        AppleStillSamples.of(heic, read, platform)
      }
    if (platform != ApplePlatform.MacOs) return decodes
    return listOf(AppleStillSamples.wholeCodeTexel(macOsCodes(AppleStillSamples.heicDecode(heic, asWritten))))
  }

  private fun macOsCodes(decode: AppleStillSamples.HeicDecode): IntArray {
    val (y, cb, cr) = decode.ycbcr
    val colour = decode.colour
    return WHOLE_CODES_OFF_ROUNDED[WholeCodeDecode(decode.ycbcr, colour.matrix, decode.full)]
      ?: ycbcrToRgb(y, cb, cr, colour.matrix, colour.primaries, colour.transfer, decode.full)
  }

  // Eight-bit samples decoded under a matrix at a range.
  private data class WholeCodeDecode(
    val ycbcr: List<Int>,
    val matrix: Int,
    val full: Boolean,
  )

  private companion object {
    const val BT709 = 1

    // The drift is the most measured, rounded up to a hundredth of a code.
    const val DRIFT_STEP = 0.01

    // The whole codes ImageIO decoded these samples to on macOS 26.6.2 (25G83) where they are not ycbcrToRgb's.
    val WHOLE_CODES_OFF_ROUNDED: Map<WholeCodeDecode, IntArray> =
      mapOf(
        WholeCodeDecode(ColourPictures.FLAT_YCBCR, BT709, true) to intArrayOf(203, 112, 69),
        WholeCodeDecode(ColourPictures.HEIC_NCLX_BT709_FULL_EDGE_CHROMA.ycbcr, BT709, true) to intArrayOf(13, 7, 251),
      )
  }
}

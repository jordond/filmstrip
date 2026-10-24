package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.media.YcbcrReading
import dev.jordond.filmstrip.media.nearestReadable
import dev.jordond.filmstrip.media.ycbcrToRgb
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which matrices Android's HEIC and AVIF decoders read on each API level, and how a picture lands through the nearest
 * of them core finds.
 *
 * A reading the decoder reads exactly lands with no limit. Any other lands through core's nearest matrix, degraded
 * with a cause ending on core's figures within the bound and refused past it, so YCgCo and the identity matrix are
 * refused wherever the decoder reads neither. A full range AVIF before API 33 also names its range.
 */
class MatrixLandingHostTest {
  // Android's own decoders, as measured on API 30, 31, 33, 34, 35 and 36 for HEIC and 31, 33, 34, 35 and 36 for AVIF,
  // so the table is pinned here as it is. API 28 and 29 are given API 30's answer and API 32 is given API 31's.
  @Test
  fun eachApiLevelReadsTheMatricesItsDecoderWasMeasuredReading() {
    for (tenBit in listOf(false, true)) {
      for (fullRange in listOf(true, false)) {
        for (sdk in HEIC_API_LEVELS) {
          val heic =
            when {
              sdk < 33 -> setOf(BT601)
              sdk == 33 && !fullRange -> setOf(BT601, BT2020)
              else -> setOf(BT709, BT601, BT2020)
            }
          assertEquals(heic, readableMatrices(fullRange, sdk, avif = false, tenBit), "HEIC $fullRange $tenBit $sdk")
        }
        for (sdk in AVIF_API_LEVELS) {
          val bt709From =
            when {
              fullRange -> 33
              tenBit -> 35
              else -> 34
            }
          val avif =
            when {
              sdk < 33 -> setOf(BT601)
              sdk < 36 -> setOfNotNull(BT709.takeIf { sdk >= bt709From }, BT601, BT2020)
              else -> setOfNotNull(BT709, FCC, BT601, SMPTE_240, BT2020, YCGCO.takeIf { fullRange && !tenBit })
            }
          assertEquals(avif, readableMatrices(fullRange, sdk, avif = true, tenBit), "AVIF $fullRange $tenBit $sdk")
        }
      }
    }
  }

  @Test
  fun everyReadingLandsThroughTheNearestMatrixItsDecoderReads() {
    for ((avif, levels) in listOf(true to AVIF_API_LEVELS, false to HEIC_API_LEVELS)) {
      for (matrix in listOf(0, 1, 2, 4, 5, 6, 7, 8, 9, 10, 12)) {
        for (fullRange in listOf(true, false)) {
          for (sdk in levels) {
            val what = "matrix $matrix full $fullRange AVIF $avif on API $sdk"
            val readable = readableMatrices(fullRange, sdk, avif, tenBit = false)
            val cutsRange = avif && sdk < 33 && fullRange
            val nearest = assertNotNull(reading(matrix).nearestReadable(readable, fullRange, fullRange && !cutsRange))
            val ownMatrix = assertNotNull(reading(matrix).nearestReadable(readable)).exact

            val landing = assertNotNull(decoderLanding(reading(matrix), fullRange, sdk, avif, tenBit = false), what)

            assertEquals(nearest.matrix, landing.matrix, what)
            val landed = landing.limits.filterIsInstance<DecoderLimit.LandedThrough>()
            assertEquals(if (ownMatrix) emptyList() else listOf(DecoderLimit.LandedThrough(avif, nearest)), landed)
            assertEquals(if (nearest.withinBound) null else pastBound(avif, nearest), landing.refusal, what)
            assertEquals(cutsRange, DecoderLimit.AvifBeforeApi33 in landing.limits, what)
            assertEquals(fullRange && !cutsRange, landing.fullRange, what)
          }
        }
      }
    }
  }

  @Test
  fun aReadingCoreRefusesHasNoLanding() {
    for (matrix in listOf(3, 11, 14, 15)) {
      assertIs<YcbcrReading.Refused>(reading(matrix))
      assertNull(decoderLanding(reading(matrix), true, 36, avif = true, tenBit = false), "matrix $matrix")
      assertNull(restatedForDecoder(reading(matrix), true, 36, avif = true, tenBit = false), "matrix $matrix")
    }
  }

  @Test
  fun aMatrixItsDecoderReadsLandsWithNoLimit() {
    val exact = listOf(BT709 to 34, BT601 to 28, 5 to 30, BT2020 to 33, CHROMATICITY_DERIVED to 34)
    for ((matrix, sdk) in exact) {
      val landing = assertNotNull(decoderLanding(reading(matrix), false, sdk, avif = false, tenBit = false))

      assertEquals(emptyList(), landing.limits, "matrix $matrix on API $sdk")
      assertNull(landing.refusal, "matrix $matrix on API $sdk")
    }
    for (matrix in listOf(FCC, SMPTE_240, YCGCO)) {
      assertEquals(emptyList(), decoderLanding(reading(matrix), true, 36, avif = true, tenBit = false)?.limits)
    }
  }

  @Test
  fun aMatrixWithinTheBoundLandsDegradedNamingCoresFigures() {
    for ((matrix, sdk) in listOf(BT709 to 30, BT2020 to 31, FCC to 34, SMPTE_240 to 36, BT2020_CONSTANT to 33)) {
      val landing = assertNotNull(decoderLanding(reading(matrix), true, sdk, avif = false, tenBit = false))
      val limit = assertIs<DecoderLimit.LandedThrough>(landing.limits.single(), "matrix $matrix on API $sdk")

      assertTrue(limit.nearest.withinBound && !limit.nearest.exact, "matrix $matrix on API $sdk")
      assertNull(landing.refusal)
      assertTrue(limit.message.startsWith("Android's HEIC decoder"), limit.message)
      assertTrue(limit.message.endsWith("matrix ${limit.nearest.matrix} instead, ${limit.nearest.figures}."))
    }
    val bt2020Constant = assertNotNull(decoderLanding(reading(BT2020_CONSTANT), true, 33, avif = true, false))
    assertEquals(BT2020, bt2020Constant.matrix, "constant luminance lands through its own weights")
  }

  // Checks that YCgCo is refused on every decoder that does not read it, and identity on every decoder as past the
  // bound, with its nearest readable matrix's figures in the reason.
  @Test
  fun yCgCoAndTheIdentityMatrixAreRefusedWhereTheirDecoderReadsNeither() {
    for ((avif, levels) in listOf(true to AVIF_API_LEVELS, false to HEIC_API_LEVELS)) {
      for (sdk in levels) {
        for (fullRange in listOf(true, false)) {
          val readsYcgco = YCGCO in readableMatrices(fullRange, sdk, avif, tenBit = false)
          val ycgco = assertNotNull(decoderLanding(reading(YCGCO), fullRange, sdk, avif, tenBit = false))
          val identity = assertNotNull(decoderLanding(reading(0), fullRange, sdk, avif, tenBit = false))
          val what = "AVIF $avif full $fullRange on API $sdk"

          assertEquals(!readsYcgco, ycgco.refusal != null, what)
          val landedFullRange = fullRange && !(avif && sdk < 33)
          val matrices = readable(fullRange, sdk, avif)
          val nearest = assertNotNull(reading(0).nearestReadable(matrices, fullRange, landedFullRange), what)
          assertEquals(pastBound(avif, nearest), identity.refusal, what)
          assertTrue(assertNotNull(identity.refusal).endsWith("${nearest.figures}."), what)
        }
      }
    }
  }

  @Test
  fun aFullRangeAvifBeforeApi33NamesItsRangeAsWell() {
    for (sdk in listOf(31, 32)) {
      val bt709 = assertNotNull(decoderLanding(reading(BT709), true, sdk, avif = true, tenBit = false))
      val bt601 = assertNotNull(decoderLanding(reading(BT601), true, sdk, avif = true, tenBit = false))
      val limited = assertNotNull(decoderLanding(reading(BT601), false, sdk, avif = true, tenBit = false))

      assertEquals(DecoderLimit.AvifBeforeApi33, bt709.limits.last())
      val moved = assertNotNull(reading(BT709).nearestReadable(setOf(BT601), true, decoderFullRange = false))
      assertEquals(DecoderLimit.LandedThrough(avif = true, moved), bt709.limits.first())
      assertEquals(listOf(DecoderLimit.AvifBeforeApi33), bt601.limits)
      assertFalse(bt601.fullRange)
      assertEquals(emptyList(), limited.limits)
    }
  }

  // BT.2020 constant luminance on the BT.709 curve lands through BT.601 within the bound at its own range, and the
  // range the decoder before API 33 moves takes the whole landing past it.
  @Test
  fun aFullRangeAvifBeforeApi33IsRefusedWhenTheMovedRangeTakesItPastTheBound() {
    val constant = reading(BT2020_CONSTANT)
    val matrixOnly = assertNotNull(constant.nearestReadable(setOf(BT601)))
    val moved = assertNotNull(constant.nearestReadable(setOf(BT601), fullRange = true, decoderFullRange = false))
    assertTrue(matrixOnly.withinBound && !moved.withinBound, "$matrixOnly then $moved")
    for (sdk in listOf(31, 32)) {
      val full = assertNotNull(decoderLanding(constant, true, sdk, avif = true, tenBit = false))
      val limited = assertNotNull(decoderLanding(constant, false, sdk, avif = true, tenBit = false))

      assertEquals(pastBound(avif = true, moved), full.refusal, "API $sdk")
      assertEquals(listOf(DecoderLimit.LandedThrough(avif = true, moved), DecoderLimit.AvifBeforeApi33), full.limits)
      assertNull(limited.refusal, "API $sdk")
      assertEquals(listOf(DecoderLimit.LandedThrough(avif = true, matrixOnly)), limited.limits, "API $sdk")
    }
    assertNull(decoderLanding(constant, true, 31, avif = false, tenBit = false)?.refusal)
  }

  // A sample in the middle of the range lands no further from its colour than core's worst channel error says.
  @Test
  fun aMidRangeSampleLandsWithinCoresWorstError() {
    for ((matrix, sdk) in listOf(BT709 to 30, BT2020 to 31, FCC to 34, SMPTE_240 to 36)) {
      for (fullRange in listOf(true, false)) {
        val landing = assertNotNull(decoderLanding(reading(matrix), fullRange, sdk, avif = false, tenBit = false))
        val nearest = assertIs<DecoderLimit.LandedThrough>(landing.limits.single()).nearest
        val own = ycbcrToRgb(reading(matrix), MID_Y, MID_CB, MID_CR, 8, fullRange)
        val landed = ycbcrToRgb(reading(landing.matrix), MID_Y, MID_CB, MID_CR, 8, fullRange)

        val off = (0 until 3).maxOf { abs(own[it] - landed[it]) * 255 }
        assertTrue(off > 0.0, "matrix $matrix lands a mid sample where it was")
        assertTrue(off <= nearest.worstError, "matrix $matrix lands $off codes out past ${nearest.worstError}")
      }
    }
  }

  private fun readable(
    fullRange: Boolean,
    sdk: Int,
    avif: Boolean,
  ): Set<Int> = readableMatrices(fullRange, sdk, avif, tenBit = false)

  private companion object {
    const val BT709 = 1
    const val FCC = 4
    const val BT601 = 6
    const val SMPTE_240 = 7
    const val YCGCO = 8
    const val BT2020 = 9
    const val BT2020_CONSTANT = 10
    const val CHROMATICITY_DERIVED = 12
    const val MID_Y = 110
    const val MID_CB = 140
    const val MID_CR = 150
    val HEIC_API_LEVELS = listOf(28, 29, 30, 31, 32, 33, 34, 35, 36)
    val AVIF_API_LEVELS = listOf(31, 32, 33, 34, 35, 36)
  }
}

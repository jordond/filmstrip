package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.color.YcbcrReading
import dev.jordond.filmstrip.color.nearestReadable
import dev.jordond.filmstrip.color.ycbcrToRgb
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.abs
import kotlin.test.Test

/**
 * Which matrices Android's HEIC and AVIF decoders read on each API level, and how a picture lands through the nearest
 * of them core finds.
 *
 * A reading the decoder reads exactly lands with no limit. Any other lands through core's nearest matrix, degraded
 * with a cause ending on core's figures within the bound and refused past it, so YCgCo and the identity matrix are
 * refused wherever the decoder reads neither. A full range AVIF before API 33 also names its range.
 */
class MatrixConversionHostTest {
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
          withClue("HEIC $fullRange $tenBit $sdk") {
            readableMatrices(fullRange, sdk, avif = false, tenBit) shouldBe heic
          }
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
          withClue("AVIF $fullRange $tenBit $sdk") {
            readableMatrices(fullRange, sdk, avif = true, tenBit) shouldBe avif
          }
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
            val cutsRange = fullRange && sdk < (if (avif) 33 else 29)
            val nearest =
              reading(
                matrix,
              ).nearestReadable(readable, fullRange, fullRange && !cutsRange).shouldNotBeNull()
            val ownMatrix = reading(matrix).nearestReadable(readable).shouldNotBeNull().exact

            val conversion =
              withClue(what) {
                decoderConversionOf(reading(matrix), fullRange, sdk, avif, tenBit = false).shouldNotBeNull()
              }

            withClue(what) { conversion.matrix shouldBe nearest.matrix }
            val landed = conversion.limits.filterIsInstance<DecoderLimit.LandedThrough>()
            landed shouldBe (if (ownMatrix) emptyList() else listOf(DecoderLimit.LandedThrough(avif, nearest)))
            withClue(what) { conversion.refusal shouldBe (if (nearest.withinBound) null else pastBound(avif, nearest)) }
            val rangeLimit = if (avif) DecoderLimit.AvifBeforeApi33 else DecoderLimit.HeicBeforeApi29
            withClue(what) { (rangeLimit in conversion.limits) shouldBe cutsRange }
            withClue(what) { conversion.fullRange shouldBe (fullRange && !cutsRange) }
          }
        }
      }
    }
  }

  @Test
  fun aReadingCoreRefusesHasNoConversion() {
    for (matrix in listOf(3, 11, 14, 15)) {
      reading(matrix).shouldBeInstanceOf<YcbcrReading.Refused>()
      withClue("matrix $matrix") {
        decoderConversionOf(reading(matrix), true, 36, avif = true, tenBit = false).shouldBeNull()
      }
      withClue("matrix $matrix") {
        restatedForDecoder(reading(matrix), true, 36, avif = true, tenBit = false).shouldBeNull()
      }
    }
  }

  @Test
  fun aMatrixItsDecoderReadsLandsWithNoLimit() {
    val exact = listOf(BT709 to 34, BT601 to 28, 5 to 30, BT2020 to 33, CHROMATICITY_DERIVED to 34)
    for ((matrix, sdk) in exact) {
      val conversion = decoderConversionOf(reading(matrix), false, sdk, avif = false, tenBit = false).shouldNotBeNull()

      withClue("matrix $matrix on API $sdk") { conversion.limits shouldBe emptyList() }
      withClue("matrix $matrix on API $sdk") { conversion.refusal.shouldBeNull() }
    }
    for (matrix in listOf(FCC, SMPTE_240, YCGCO)) {
      decoderConversionOf(reading(matrix), true, 36, avif = true, tenBit = false)?.limits shouldBe emptyList()
    }
  }

  @Test
  fun aMatrixWithinTheBoundLandsDegradedNamingCoresFigures() {
    for ((matrix, sdk) in listOf(BT709 to 30, BT2020 to 31, FCC to 34, SMPTE_240 to 36, BT2020_CONSTANT to 33)) {
      val conversion = decoderConversionOf(reading(matrix), true, sdk, avif = false, tenBit = false).shouldNotBeNull()
      val limit =
        withClue("matrix $matrix on API $sdk") {
          conversion.limits.single().shouldBeInstanceOf<DecoderLimit.LandedThrough>()
        }

      withClue("matrix $matrix on API $sdk") { (limit.nearest.withinBound && !limit.nearest.exact) shouldBe true }
      conversion.refusal.shouldBeNull()
      withClue(limit.message) { limit.message.startsWith("Android's HEIC decoder") shouldBe true }
      limit.message.endsWith("matrix ${limit.nearest.matrix} instead, ${limit.nearest.figures}.") shouldBe true
    }
    val bt2020Constant = decoderConversionOf(reading(BT2020_CONSTANT), true, 33, avif = true, false).shouldNotBeNull()
    withClue("constant luminance lands through its own weights") { bt2020Constant.matrix shouldBe BT2020 }
  }

  // Checks that YCgCo is refused on every decoder that does not read it, and identity on every decoder as past the
  // bound, with its nearest readable matrix's figures in the reason.
  @Test
  fun yCgCoAndTheIdentityMatrixAreRefusedWhereTheirDecoderReadsNeither() {
    for ((avif, levels) in listOf(true to AVIF_API_LEVELS, false to HEIC_API_LEVELS)) {
      for (sdk in levels) {
        for (fullRange in listOf(true, false)) {
          val readsYcgco = YCGCO in readableMatrices(fullRange, sdk, avif, tenBit = false)
          val ycgco = decoderConversionOf(reading(YCGCO), fullRange, sdk, avif, tenBit = false).shouldNotBeNull()
          val identity = decoderConversionOf(reading(0), fullRange, sdk, avif, tenBit = false).shouldNotBeNull()
          val what = "AVIF $avif full $fullRange on API $sdk"

          withClue(what) { (ycgco.refusal != null) shouldBe !readsYcgco }
          val landedFullRange = fullRange && sdk >= (if (avif) 33 else 29)
          val matrices = readable(fullRange, sdk, avif)
          val nearest =
            withClue(what) {
              reading(0).nearestReadable(matrices, fullRange, landedFullRange).shouldNotBeNull()
            }
          withClue(what) { identity.refusal shouldBe pastBound(avif, nearest) }
          withClue(what) { identity.refusal.shouldNotBeNull().endsWith("${nearest.figures}.") shouldBe true }
        }
      }
    }
  }

  @Test
  fun aFullRangeAvifBeforeApi33NamesItsRangeAsWell() {
    for (sdk in listOf(31, 32)) {
      val bt709 = decoderConversionOf(reading(BT709), true, sdk, avif = true, tenBit = false).shouldNotBeNull()
      val bt601 = decoderConversionOf(reading(BT601), true, sdk, avif = true, tenBit = false).shouldNotBeNull()
      val limited = decoderConversionOf(reading(BT601), false, sdk, avif = true, tenBit = false).shouldNotBeNull()

      bt709.limits.last() shouldBe DecoderLimit.AvifBeforeApi33
      val moved = reading(BT709).nearestReadable(setOf(BT601), true, decoderFullRange = false).shouldNotBeNull()
      bt709.limits.first() shouldBe DecoderLimit.LandedThrough(avif = true, moved)
      bt601.limits shouldBe listOf(DecoderLimit.AvifBeforeApi33)
      bt601.fullRange shouldBe false
      limited.limits shouldBe emptyList()
    }
  }

  // BT.2020 constant luminance on the BT.709 curve lands through BT.601 within the bound at its own range, and the
  // range the decoder before API 33 moves takes the whole landing past it.
  @Test
  fun aFullRangeAvifBeforeApi33IsRefusedWhenTheMovedRangeTakesItPastTheBound() {
    val constant = reading(BT2020_CONSTANT)
    val matrixOnly = constant.nearestReadable(setOf(BT601)).shouldNotBeNull()
    val moved = constant.nearestReadable(setOf(BT601), fullRange = true, decoderFullRange = false).shouldNotBeNull()
    withClue("$matrixOnly then $moved") { (matrixOnly.withinBound && !moved.withinBound) shouldBe true }
    for (sdk in listOf(31, 32)) {
      val full = decoderConversionOf(constant, true, sdk, avif = true, tenBit = false).shouldNotBeNull()
      val limited = decoderConversionOf(constant, false, sdk, avif = true, tenBit = false).shouldNotBeNull()

      withClue("API $sdk") { full.refusal shouldBe pastBound(avif = true, moved) }
      full.limits shouldBe listOf(DecoderLimit.LandedThrough(avif = true, moved), DecoderLimit.AvifBeforeApi33)
      withClue("API $sdk") { limited.refusal.shouldBeNull() }
      withClue("API $sdk") { limited.limits shouldBe listOf(DecoderLimit.LandedThrough(avif = true, matrixOnly)) }
    }
    decoderConversionOf(constant, true, 31, avif = false, tenBit = false)?.refusal.shouldBeNull()
  }

  // A sample in the middle of the range lands no further from its color than core's worst channel error says.
  @Test
  fun aMidRangeSampleLandsWithinCoresWorstError() {
    for ((matrix, sdk) in listOf(BT709 to 30, BT2020 to 31, FCC to 34, SMPTE_240 to 36)) {
      for (fullRange in listOf(true, false)) {
        val conversion =
          decoderConversionOf(reading(matrix), fullRange, sdk, avif = false, tenBit = false).shouldNotBeNull()
        val nearest =
          conversion.limits
            .single()
            .shouldBeInstanceOf<DecoderLimit.LandedThrough>()
            .nearest
        val own = ycbcrToRgb(reading(matrix), MID_Y, MID_CB, MID_CR, 8, fullRange)
        val landed = ycbcrToRgb(reading(conversion.matrix), MID_Y, MID_CB, MID_CR, 8, fullRange)

        val off = (0 until 3).maxOf { abs(own[it] - landed[it]) * 255 }
        withClue("matrix $matrix lands a mid sample where it was") { (off > 0.0) shouldBe true }
        withClue("matrix $matrix lands $off codes out past ${nearest.worstError}") {
          (off <= nearest.worstError) shouldBe true
        }
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

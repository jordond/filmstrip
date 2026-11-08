package dev.jordond.filmstrip.color

import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import kotlin.math.abs
import kotlin.test.Test

class ColorMatricesTest {
  // IEC 61966-2-1 gives this matrix to four places, derived from the same primaries and D65 white.
  @Test
  fun srgbPrimariesBuildTheMatrixTheSrgbSpecPublishes() {
    val matrix = rgbToXyz(SRGB).shouldNotBeNull()

    assertClose(SRGB_TO_XYZ, matrix, 1e-4)
  }

  @Test
  fun everyChannelAtFullLightsTheWhiteWithAYOfOne() {
    val matrix = rgbToXyz(SRGB).shouldNotBeNull()

    assertClose(D65_WHITE, apply(matrix, doubleArrayOf(1.0, 1.0, 1.0)), 1e-12)
  }

  // Each channel at half light has to land half way to the white, which a matrix with the white scaled in only at the
  // ends would miss.
  @Test
  fun halfLightOnEveryChannelLandsHalfWayToTheWhite() {
    val matrix = rgbToXyz(DISPLAY_P3).shouldNotBeNull()

    val half = apply(matrix, doubleArrayOf(0.5, 0.5, 0.5))

    assertClose(DoubleArray(3) { D65_WHITE[it] / 2 }, half, 1e-12)
  }

  @Test
  fun theXyzPrimariesBuildTheIdentity() {
    val xyz = doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0, 1.0 / 3, 1.0 / 3)

    assertClose(IDENTITY, rgbToXyz(xyz).shouldNotBeNull(), 1e-12)
  }

  // The collinear primaries are exact in binary, so their determinant comes out at zero rather than at a rounding
  // error away from it.
  @Test
  fun chromaticitiesThatSpanNoColorSpaceBuildNothing() {
    val collinear = doubleArrayOf(0.25, 0.25, 0.375, 0.375, 0.5, 0.5, 0.3127, 0.3290)
    val whiteOnTheAxis = SRGB.copyOf().also { it[7] = 0.0 }
    val notANumber = SRGB.copyOf().also { it[2] = Double.NaN }
    val endless = SRGB.copyOf().also { it[0] = Double.POSITIVE_INFINITY }

    rgbToXyz(collinear).shouldBeNull()
    rgbToXyz(whiteOnTheAxis).shouldBeNull()
    rgbToXyz(notANumber).shouldBeNull()
    rgbToXyz(endless).shouldBeNull()
  }

  @Test
  fun bradfordCarriesTheSourceWhiteOntoTheTargetWhite() {
    val adapted = apply(bradford(D65_WHITE, D50_WHITE), D65_WHITE)

    assertClose(D50_WHITE, adapted, 1e-12)
  }

  // The published D65 to D50 Bradford matrix, which pins the cone matrix as well as where the white lands.
  @Test
  fun bradfordFromD65ToD50MatchesThePublishedMatrix() {
    assertClose(D65_TO_D50, bradford(D65_WHITE, D50_WHITE), 1e-3)
  }

  @Test
  fun adaptingAWhiteToItselfChangesNothing() {
    assertClose(IDENTITY, bradford(D65_WHITE, D65_WHITE), 1e-12)
  }

  @Test
  fun adaptingThereAndBackChangesNothing() {
    val roundTrip = multiply(bradford(D50_WHITE, D65_WHITE), bradford(D65_WHITE, D50_WHITE))

    assertClose(IDENTITY, roundTrip, 1e-12)
  }

  @Test
  fun aMatrixTimesItsInverseIsTheIdentity() {
    val matrix = rgbToXyz(DISPLAY_P3).shouldNotBeNull()

    assertClose(IDENTITY, multiply(matrix, invert(matrix).shouldNotBeNull()), 1e-12)
    assertClose(IDENTITY, multiply(invert(matrix).shouldNotBeNull(), matrix), 1e-12)
  }

  @Test
  fun aSingularOrNonFiniteMatrixHasNoInverse() {
    val repeatedRow = doubleArrayOf(1.0, 2.0, 3.0, 1.0, 2.0, 3.0, 0.0, 1.0, 0.0)
    val endless = IDENTITY.copyOf().also { it[4] = Double.POSITIVE_INFINITY }

    invert(repeatedRow).shouldBeNull()
    invert(DoubleArray(9)).shouldBeNull()
    invert(endless).shouldBeNull()
  }

  @Test
  fun multiplyComposesRowsWithColumns() {
    val left = doubleArrayOf(1.0, 2.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 3.0)
    val right = doubleArrayOf(0.0, 1.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)
    val vector = doubleArrayOf(2.0, 5.0, 7.0)

    assertClose(doubleArrayOf(2.0, 1.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 3.0), multiply(left, right), 0.0)
    assertClose(apply(left, apply(right, vector)), apply(multiply(left, right), vector), 1e-12)
  }

  private fun assertClose(
    expected: DoubleArray,
    actual: DoubleArray,
    tolerance: Double,
  ) {
    withClue("expected ${expected.toList()} within $tolerance, got ${actual.toList()}") {
      (
        expected.size == actual.size &&
          expected.indices.all {
            abs(
              expected[it] - actual[it],
            ) <= tolerance
          }
      ).shouldBeTrue()
    }
  }

  private companion object {
    val SRGB = doubleArrayOf(0.64, 0.33, 0.30, 0.60, 0.15, 0.06, 0.3127, 0.3290)

    val DISPLAY_P3 = doubleArrayOf(0.680, 0.320, 0.265, 0.690, 0.150, 0.060, 0.3127, 0.3290)

    val SRGB_TO_XYZ =
      doubleArrayOf(
        0.4124,
        0.3576,
        0.1805,
        0.2126,
        0.7152,
        0.0722,
        0.0193,
        0.1192,
        0.9505,
      )

    val D50_WHITE = xyToXyz(0.3457, 0.3585)

    val D65_TO_D50 =
      doubleArrayOf(
        1.0478,
        0.0229,
        -0.0501,
        0.0295,
        0.9905,
        -0.0170,
        -0.0092,
        0.0150,
        0.7521,
      )

    val IDENTITY = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
  }
}

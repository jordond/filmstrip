package dev.jordond.filmstrip.effects.color

import dev.jordond.filmstrip.media.HDR_REFERENCE_WHITE_NITS
import dev.jordond.filmstrip.media.HLG_NOMINAL_PEAK_NITS
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.PQ_PEAK_NITS
import dev.jordond.filmstrip.media.brightnessDisplayGain
import dev.jordond.filmstrip.media.nitsFromSdrSignal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The numbers every backend lowers, checked against pixels worked out by hand.
 *
 * The test pixel is deliberately unequal across channels. A diagonal matrix reads the same whichever
 * way its rows and columns are laid out, so a grey pixel or a per-channel scale proves nothing about
 * the orientation of a mixing matrix.
 */
class ColorMatrixTest {
  @Test
  fun theIdentityLeavesAPixelAlone() {
    assertPixel(ColorMatrix.Identity.transform(RED, GREEN, BLUE), RED, GREEN, BLUE)
    assertTrue(ColorMatrix.Identity.isIdentity)
    assertTrue(ColorMatrix.Identity.isDiagonal)
  }

  @Test
  fun aFullDesaturationLandsOnTheRec709Luma() {
    val luma = LUMA_RED * RED + LUMA_GREEN * GREEN + LUMA_BLUE * BLUE

    assertPixel(Saturation(0f).matrix.transform(RED, GREEN, BLUE), luma, luma, luma)
    assertEquals(0.4634f, luma, TOLERANCE)
  }

  @Test
  fun aHalfSaturationSitsHalfwayToTheLuma() {
    val luma = LUMA_RED * RED + LUMA_GREEN * GREEN + LUMA_BLUE * BLUE

    val pixel = Saturation(0.5f).matrix.transform(RED, GREEN, BLUE)

    assertPixel(pixel, (RED + luma) / 2, (GREEN + luma) / 2, (BLUE + luma) / 2)
  }

  @Test
  fun saturationKeepsAGreyPixelWhereItWas() {
    assertPixel(Saturation(2f).matrix.transform(GREY, GREY, GREY), GREY, GREY, GREY)
  }

  @Test
  fun contrastPivotsOnMidGrey() {
    val pixel = Contrast(1.2f).matrix.transform(RED, GREEN, BLUE)

    assertPixel(pixel, 0.5f + (RED - 0.5f) * 1.2f, 0.5f + (GREEN - 0.5f) * 1.2f, 0.5f + (BLUE - 0.5f) * 1.2f)
    assertPixel(Contrast(0f).matrix.transform(RED, GREEN, BLUE), 0.5f, 0.5f, 0.5f)
  }

  @Test
  fun aFullInversionIsTheNegative() {
    assertPixel(Invert().matrix.transform(RED, GREEN, BLUE), 1f - RED, 1f - GREEN, 1f - BLUE)
    assertPixel(Invert(0.5f).matrix.transform(RED, GREEN, BLUE), 0.5f, 0.5f, 0.5f)
  }

  @Test
  fun sepiaBlendsTowardsTheTonedPixel() {
    val toned = Sepia(1f).matrix.transform(RED, GREEN, BLUE)
    val half = Sepia(0.5f).matrix.transform(RED, GREEN, BLUE)

    assertPixel(
      toned,
      0.393f * RED + 0.769f * GREEN + 0.189f * BLUE,
      0.349f * RED + 0.686f * GREEN + 0.168f * BLUE,
      0.272f * RED + 0.534f * GREEN + 0.131f * BLUE,
    )
    assertPixel(half, (RED + toned[0]) / 2, (GREEN + toned[1]) / 2, (BLUE + toned[2]) / 2)
  }

  @Test
  fun aHueRotationLeavesGreyAloneAndAFullTurnIsTheIdentity() {
    assertPixel(HueRotate(137f).matrix.transform(GREY, GREY, GREY), GREY, GREY, GREY)
    assertPixel(HueRotate(360f).matrix.transform(RED, GREEN, BLUE), RED, GREEN, BLUE)

    // A third of a turn carries red round to green, which is the sign of the rotation.
    val turned = HueRotate(120f).matrix.transform(1f, 0f, 0f)
    assertTrue(turned[1] > turned[0] && turned[1] > turned[2], "red turned to ${turned.toList()}")
  }

  @Test
  fun anRgbAdjustmentScalesEachChannelOnItsOwn() {
    assertPixel(RgbAdjustment(0.5f, 1f, 2f).matrix.transform(RED, GREEN, BLUE), RED / 2, GREEN, BLUE * 2)
    assertPixel(RgbAdjustment(-1f, Float.NaN, 1f).matrix.transform(RED, GREEN, BLUE), 0f, GREEN, BLUE)
  }

  // Nothing leaves the range at this gain, so applying the two in turn and applying their product
  // agree. Where a channel does leave it the two differ, and the product is the answer every backend
  // is held to.
  @Test
  fun composingMatchesApplyingOneAfterTheOther() {
    val first = Contrast(1.2f).matrix
    val second = Saturation(0.5f).matrix

    val stepped = first.transform(RED, GREEN, BLUE).let { second.transform(it[0], it[1], it[2]) }
    val composed = first.then(second).transform(RED, GREEN, BLUE)

    assertPixel(composed, stepped[0], stepped[1], stepped[2])
    // Saturation keeps grey where it is, so it commutes with a contrast. A sepia tone does not.
    assertNotEquals(first.then(Sepia().matrix), Sepia().matrix.then(first))
  }

  @Test
  fun theBiasIsCarriedThroughTheSecondMatrix() {
    val lift = ColorMatrix(rBias = 0.2f)
    val halve = Brightness(0.5f).matrix

    assertEquals(0.1f, lift.then(halve).rBias, TOLERANCE)
    assertEquals(0.2f, halve.then(lift).rBias, TOLERANCE)
  }

  @Test
  fun theColumnMajorFormPutsRowsAtAStrideOfFour() {
    val matrix =
      Saturation(0f).matrix.let {
        ColorMatrix(
          rr = it.rr,
          rg = it.rg,
          rb = it.rb,
          rBias = 0.25f,
          gr = it.gr,
          gg = it.gg,
          gb = it.gb,
          br = it.br,
          bg = it.bg,
          bb = it.bb,
        )
      }

    val gl = matrix.toColumnMajor4x4()

    assertEquals(16, gl.size)
    assertEquals(matrix.rr, gl[0])
    assertEquals(matrix.rg, gl[4])
    assertEquals(matrix.rb, gl[8])
    assertEquals(matrix.rBias, gl[12])
    assertEquals(matrix.gr, gl[1])
    assertEquals(matrix.bb, gl[10])
    assertEquals(1f, gl[15])
    assertEquals(listOf(0f, 0f, 0f), listOf(gl[3], gl[7], gl[11]))
  }

  @Test
  fun aNaNEntryReadsAsTheIdentity() {
    val sanitised = ColorMatrix(rr = Float.NaN, gb = Float.NaN, bBias = Float.NaN).sanitised

    assertEquals(ColorMatrix.Identity, sanitised)
  }

  // An infinity is worse than a NaN downstream: ffmpeg's lut3d parses one, writes whatever the
  // conversion to an integer comes to, and exits zero, so the frame is wrong with nothing said.
  @Test
  fun anInfiniteEntryReadsAsTheIdentity() {
    val sanitised =
      ColorMatrix(rr = Float.POSITIVE_INFINITY, gb = Float.NEGATIVE_INFINITY, bBias = Float.POSITIVE_INFINITY)
        .sanitised

    assertEquals(ColorMatrix.Identity, sanitised)
  }

  // Every effect guards its own input, and the shared lowering sanitises whatever comes back, so an
  // infinity cannot reach a backend through the trig a hue rotation runs or the arithmetic the
  // others do.
  @Test
  fun noCatalogueEffectLowersANonFiniteMatrix() {
    val broken =
      listOf(
        Brightness(Float.POSITIVE_INFINITY),
        Contrast(Float.POSITIVE_INFINITY),
        Saturation(Float.POSITIVE_INFINITY),
        RgbAdjustment(red = Float.POSITIVE_INFINITY),
        HueRotate(Float.POSITIVE_INFINITY),
        HueRotate(Float.NaN),
        Sepia(Float.POSITIVE_INFINITY),
        Invert(Float.NEGATIVE_INFINITY),
        ColorMatrix(rg = Float.POSITIVE_INFINITY),
      )

    broken.forEach { spec ->
      val matrix = checkNotNull(colorMatrixOf(spec)) { "${spec.id} lowered to no matrix" }
      val pixel = matrix.transform(RED, GREEN, BLUE)

      pixel.forEachIndexed { channel, value ->
        assertTrue(value.isFinite(), "${spec.id} wrote $value into channel $channel")
      }
    }
  }

  // A whole turn is the identity, and the trig table it comes out of leaves cross terms around 1e-16
  // rather than zero. A backend reading those as a mix lowers a no-op through a lookup table that
  // moves code values.
  @Test
  fun aWholeTurnOfHueIsTheIdentity() {
    val matrix = checkNotNull(colorMatrixOf(HueRotate(360f)))

    assertTrue(matrix.isDiagonal, "the cross terms of $matrix read as a mix")
    assertTrue(matrix.isIdentity, "$matrix reads as something other than the identity")
  }

  // Which lowering a colour effect takes is the matrix's shape, not the type that wrote it, so a
  // folded run that comes to a plain scale is as cheap as the brightness that spells the same thing.
  @Test
  fun aUniformScaleIsRecognisedWhateverWroteIt() {
    assertEquals(0.5f, checkNotNull(colorMatrixOf(Brightness(0.5f))).uniformScale)
    assertEquals(0.5f, checkNotNull(colorMatrixOf(ColorMatrix(rr = 0.5f, gg = 0.5f, bb = 0.5f))).uniformScale)
    assertEquals(null, checkNotNull(colorMatrixOf(RgbAdjustment(red = 0.5f))).uniformScale)
    assertEquals(null, checkNotNull(colorMatrixOf(Contrast(0.5f))).uniformScale)
    assertEquals(null, checkNotNull(colorMatrixOf(Sepia())).uniformScale)
  }

  // A pass this side did not write is free to spell a uniform of that name any way it likes, and a
  // backend folding a foreign chain reads it back rather than failing the plan.
  @Test
  fun anArrayThatIsNotAMat4ReadsBackAsNothing() {
    assertEquals(null, colorMatrixOfColumnMajor4x4OrNull(floatArrayOf(1f, 0f, 0f)))
    assertEquals(ColorMatrix.Identity, colorMatrixOfColumnMajor4x4OrNull(ColorMatrix.Identity.toColumnMajor4x4()))
  }

  @Test
  fun everyColourEffectHasAMatrixAndNothingElseDoes() {
    assertEquals(Brightness(0.5f).matrix, colorMatrixOf(Brightness(0.5f)))
    assertEquals(ColorMatrix(rg = 0.5f), colorMatrixOf(ColorMatrix(rg = 0.5f)))
    assertEquals(
      null,
      colorMatrixOf(
        dev.jordond.filmstrip.effects.geometry
          .Rotate(90),
      ),
    )
  }

  // What a backend reads back out of a uniform is the matrix that wrote it, cross terms and bias
  // included, so a pass folded on the far side composes through the same product as the planner.
  @Test
  fun theColumnMajorFormReadsBackToTheSameMatrix() {
    val matrix = checkNotNull(colorMatrixOf(Sepia(0.7f))).then(checkNotNull(colorMatrixOf(Contrast(1.3f))))

    assertEquals(matrix, colorMatrixOfColumnMajor4x4(matrix.toColumnMajor4x4()))
  }

  // The middle of the range, worked out by hand: 100 nits is a signal of 0.7249, a contrast of 1.5
  // moves that to 0.8372, and 0.8372 back to light is 137.3 nits.
  @Test
  fun onAGradeContrastRunsOnTheSignalAndPivotsOnMidGreyLight() {
    val pivot = nitsFromSdrSignal(0.5f)

    val pixel = Contrast(1.5f).matrix.transformNits(100f, pivot, 10f, HdrTransfer.Pq)

    assertEquals(44.18f, pivot, NITS_TOLERANCE)
    assertEquals(137.33f, pixel[0], NITS_TOLERANCE)
    assertEquals(pivot, pixel[1], NITS_TOLERANCE)
    assertEquals(2.35f, pixel[2], NITS_TOLERANCE)
  }

  @Test
  fun onAGradeInvertTurnsAnythingAboveReferenceWhiteBlack() {
    val pixel = Invert().matrix.transformNits(1_000f, HDR_REFERENCE_WHITE_NITS, 10f, HdrTransfer.Pq)

    assertEquals(0f, pixel[0], NITS_TOLERANCE)
    assertEquals(0f, pixel[1], NITS_TOLERANCE)
    assertEquals(106.39f, pixel[2], NITS_TOLERANCE)
  }

  // Without a bias the choice of white cancels, so a saturation or a hue rotation reads the same
  // however bright the picture is, and only a contrast or an inversion is anchored to 203 nits.
  @Test
  fun onAGradeABiasFreeMatrixIsWhiteIndependent() {
    val matrix = Saturation(0.5f).matrix.then(HueRotate(40f).matrix)

    val dim = matrix.transformNits(80f, 40f, 10f, HdrTransfer.Pq)
    val bright = matrix.transformNits(800f, 400f, 100f, HdrTransfer.Pq)

    repeat(3) { assertEquals(dim[it] * 10f, bright[it], bright[it] * 1e-3f) }
  }

  @Test
  fun onAGradeBrightnessIsTheDisplayGain() {
    val pixel = Brightness(1.5f).matrix.transformNits(100f, 40f, 10f, HdrTransfer.Pq)

    assertEquals(100f * brightnessDisplayGain(1.5f), pixel[0], NITS_TOLERANCE)
    assertEquals(40f * brightnessDisplayGain(1.5f), pixel[1], NITS_TOLERANCE)
  }

  @Test
  fun onAGradeTheCeilingIsTheFormatsPeak() {
    val pq = Brightness(3f).matrix.transformNits(5_000f, 0f, 0f, HdrTransfer.Pq)
    val hlg = Brightness(3f).matrix.transformNits(500f, 0f, 0f, HdrTransfer.Hlg)

    assertEquals(PQ_PEAK_NITS, pq[0], NITS_TOLERANCE)
    assertEquals(HLG_NOMINAL_PEAK_NITS, hlg[0], NITS_TOLERANCE)
  }

  private fun assertPixel(
    pixel: FloatArray,
    red: Float,
    green: Float,
    blue: Float,
  ) {
    assertEquals(red, pixel[0], TOLERANCE, "red of ${pixel.toList()}")
    assertEquals(green, pixel[1], TOLERANCE, "green of ${pixel.toList()}")
    assertEquals(blue, pixel[2], TOLERANCE, "blue of ${pixel.toList()}")
  }

  private companion object {
    const val RED = 0.8f
    const val GREEN = 0.4f
    const val BLUE = 0.1f
    const val GREY = 0.37f
    const val TOLERANCE = 0.0015f
    const val NITS_TOLERANCE = 0.1f
  }
}

package dev.jordond.filmstrip.effects.color

import dev.jordond.filmstrip.color.HDR_REFERENCE_WHITE_NITS
import dev.jordond.filmstrip.color.HLG_NOMINAL_PEAK_NITS
import dev.jordond.filmstrip.color.PQ_PEAK_NITS
import dev.jordond.filmstrip.color.brightnessDisplayGain
import dev.jordond.filmstrip.color.nitsFromSdrSignal
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.media.HdrTransfer
import io.kotest.assertions.withClue
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.test.assertFailsWith

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
    ColorMatrix.Identity.isIdentity shouldBe true
    ColorMatrix.Identity.isDiagonal shouldBe true
  }

  @Test
  fun aFullDesaturationLandsOnTheRec709Luma() {
    val luma = LUMA_RED * RED + LUMA_GREEN * GREEN + LUMA_BLUE * BLUE

    assertPixel(Saturation(0f).matrix.transform(RED, GREEN, BLUE), luma, luma, luma)
    luma shouldBe (0.4634f plusOrMinus TOLERANCE)
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
    withClue("red turned to ${turned.toList()}") { (turned[1] > turned[0] && turned[1] > turned[2]) shouldBe true }
  }

  @Test
  fun anRgbAdjustmentScalesEachChannelOnItsOwn() {
    assertPixel(RgbAdjustment(0.5f, 1f, 2f).matrix.transform(RED, GREEN, BLUE), RED / 2, GREEN, BLUE * 2)
    assertPixel(RgbAdjustment(-1f, 1f, 1f).matrix.transform(RED, GREEN, BLUE), 0f, GREEN, BLUE)
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
    Sepia().matrix.then(first) shouldNotBe first.then(Sepia().matrix)
  }

  @Test
  fun theBiasIsCarriedThroughTheSecondMatrix() {
    val lift = ColorMatrix(rBias = 0.2f)
    val halve = Brightness(0.5f).matrix

    lift.then(halve).rBias shouldBe (0.1f plusOrMinus TOLERANCE)
    halve.then(lift).rBias shouldBe (0.2f plusOrMinus TOLERANCE)
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

    gl.size shouldBe 16
    gl[0] shouldBe matrix.rr.stored()
    gl[4] shouldBe matrix.rg.stored()
    gl[8] shouldBe matrix.rb.stored()
    gl[12] shouldBe matrix.rBias.stored()
    gl[1] shouldBe matrix.gr.stored()
    gl[10] shouldBe matrix.bb.stored()
    gl[15] shouldBe 1f
    listOf(gl[3], gl[7], gl[11]) shouldBe listOf(0f, 0f, 0f)
  }

  @Test
  fun everyColorEffectRefusesANumberThatIsNotFinite() {
    val builds: List<(Float) -> EffectSpec> =
      listOf(
        ::Brightness,
        ::Contrast,
        ::Saturation,
        ::HueRotate,
        ::Sepia,
        ::Invert,
        { RgbAdjustment(green = it) },
        { ColorMatrix(gb = it) },
        { ColorMatrix(bBias = it) },
      )

    builds.forEach { build ->
      listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { value ->
        assertFailsWith<IllegalArgumentException> { build(value) }
      }
      build(MID_VALUE).shouldBeInstanceOf<EffectSpec>()
    }
  }

  // Each factor is valid on its own, and the product of the run leaves what a float holds. The fold holds every entry
  // at the largest float rather than letting it become an infinity, and a NaN from an infinity less an infinity.
  @Test
  fun aLongRunOfLargeFactorsFoldsToAFiniteMatrix() {
    val run = List(LONG_RUN) { Contrast(LARGE_FACTOR) }
    val folded = run.fold(ColorMatrix.Identity) { combined, next -> combined.then(next.matrix) }

    folded.toColumnMajor4x4().forEach { entry -> entry.isFinite() shouldBe true }
    folded.rr shouldBe Float.MAX_VALUE
    folded.rBias shouldBe -Float.MAX_VALUE
  }

  @Test
  fun aForeignMat4WithANumberThatIsNotFiniteReadsAsNoMatrix() {
    val columns = ColorMatrix(rr = MID_VALUE).toColumnMajor4x4()

    colorMatrixOfColumnMajor4x4OrNull(columns) shouldBe ColorMatrix(rr = MID_VALUE)
    colorMatrixOfColumnMajor4x4OrNull(columns.copyOf().also { it[5] = Float.NaN }).shouldBeNull()
  }

  // A whole turn is the identity, and the trig table it comes out of leaves cross terms around 1e-16
  // rather than zero. A backend reading those as a mix lowers a no-op through a lookup table that
  // moves code values.
  @Test
  fun aWholeTurnOfHueIsTheIdentity() {
    val matrix = checkNotNull(colorMatrixOf(HueRotate(360f)))

    withClue("the cross terms of $matrix read as a mix") { matrix.isDiagonal shouldBe true }
    withClue("$matrix reads as something other than the identity") { matrix.isIdentity shouldBe true }
  }

  // Which lowering a color effect takes is the matrix's shape, not the type that wrote it, so a
  // folded run that comes to a plain scale is as cheap as the brightness that spells the same thing.
  @Test
  fun aUniformScaleIsRecognisedWhateverWroteIt() {
    checkNotNull(colorMatrixOf(Brightness(0.5f))).uniformScale shouldBe 0.5f
    checkNotNull(colorMatrixOf(ColorMatrix(rr = 0.5f, gg = 0.5f, bb = 0.5f))).uniformScale shouldBe 0.5f
    checkNotNull(colorMatrixOf(RgbAdjustment(red = 0.5f))).uniformScale shouldBe null
    checkNotNull(colorMatrixOf(Contrast(0.5f))).uniformScale shouldBe null
    checkNotNull(colorMatrixOf(Sepia())).uniformScale shouldBe null
  }

  // A pass this side did not write is free to spell a uniform of that name any way it likes, and a
  // backend folding a foreign chain reads it back rather than failing the plan.
  @Test
  fun anArrayThatIsNotAMat4ReadsBackAsNothing() {
    colorMatrixOfColumnMajor4x4OrNull(floatArrayOf(1f, 0f, 0f)) shouldBe null
    colorMatrixOfColumnMajor4x4OrNull(ColorMatrix.Identity.toColumnMajor4x4()) shouldBe ColorMatrix.Identity
  }

  @Test
  fun everyColorEffectHasAMatrixAndNothingElseDoes() {
    colorMatrixOf(Brightness(0.5f)) shouldBe Brightness(0.5f).matrix
    colorMatrixOf(ColorMatrix(rg = 0.5f)) shouldBe ColorMatrix(rg = 0.5f)
    colorMatrixOf(
      dev.jordond.filmstrip.effects.geometry
        .Rotate(90),
    ) shouldBe null
  }

  // What a backend reads back out of a uniform is the matrix that wrote it, cross terms and bias
  // included, so a pass folded on the far side composes through the same product as the planner.
  @Test
  fun theColumnMajorFormReadsBackToTheSameMatrix() {
    val matrix = checkNotNull(colorMatrixOf(Sepia(0.7f))).then(checkNotNull(colorMatrixOf(Contrast(1.3f))))

    val readBack = colorMatrixOfColumnMajor4x4(matrix.toColumnMajor4x4())

    readBack.entries shouldBe matrix.entries.map { it.stored() }
  }

  // The middle of the range, worked out by hand: 100 nits is a signal of 0.7249, a contrast of 1.5
  // moves that to 0.8372, and 0.8372 back to light is 137.3 nits.
  @Test
  fun onAGradeContrastRunsOnTheSignalAndPivotsOnMidGreyLight() {
    val pivot = nitsFromSdrSignal(0.5f)

    val pixel = Contrast(1.5f).matrix.transformNits(100f, pivot, 10f, HdrTransfer.Pq)

    pivot shouldBe (44.18f plusOrMinus NITS_TOLERANCE)
    pixel[0] shouldBe (137.33f plusOrMinus NITS_TOLERANCE)
    pixel[1] shouldBe (pivot plusOrMinus NITS_TOLERANCE)
    pixel[2] shouldBe (2.35f plusOrMinus NITS_TOLERANCE)
  }

  @Test
  fun onAGradeInvertTurnsAnythingAboveReferenceWhiteBlack() {
    val pixel = Invert().matrix.transformNits(1_000f, HDR_REFERENCE_WHITE_NITS, 10f, HdrTransfer.Pq)

    pixel[0] shouldBe (0f plusOrMinus NITS_TOLERANCE)
    pixel[1] shouldBe (0f plusOrMinus NITS_TOLERANCE)
    pixel[2] shouldBe (106.39f plusOrMinus NITS_TOLERANCE)
  }

  // Without a bias the choice of white cancels, so a saturation or a hue rotation reads the same
  // however bright the picture is, and only a contrast or an inversion is anchored to 203 nits.
  @Test
  fun onAGradeABiasFreeMatrixIsWhiteIndependent() {
    val matrix = Saturation(0.5f).matrix.then(HueRotate(40f).matrix)

    val dim = matrix.transformNits(80f, 40f, 10f, HdrTransfer.Pq)
    val bright = matrix.transformNits(800f, 400f, 100f, HdrTransfer.Pq)

    repeat(3) { bright[it] shouldBe (dim[it] * 10f plusOrMinus bright[it] * 1e-3f) }
  }

  @Test
  fun onAGradeBrightnessIsTheDisplayGain() {
    val pixel = Brightness(1.5f).matrix.transformNits(100f, 40f, 10f, HdrTransfer.Pq)

    pixel[0] shouldBe (100f * brightnessDisplayGain(1.5f) plusOrMinus NITS_TOLERANCE)
    pixel[1] shouldBe (40f * brightnessDisplayGain(1.5f) plusOrMinus NITS_TOLERANCE)
  }

  @Test
  fun onAGradeTheCeilingIsTheFormatsPeak() {
    val pq = Brightness(3f).matrix.transformNits(5_000f, 0f, 0f, HdrTransfer.Pq)
    val hlg = Brightness(3f).matrix.transformNits(500f, 0f, 0f, HdrTransfer.Hlg)

    pq[0] shouldBe (PQ_PEAK_NITS plusOrMinus NITS_TOLERANCE)
    hlg[0] shouldBe (HLG_NOMINAL_PEAK_NITS plusOrMinus NITS_TOLERANCE)
  }

  private fun assertPixel(
    pixel: FloatArray,
    red: Float,
    green: Float,
    blue: Float,
  ) {
    withClue("red of ${pixel.toList()}") { pixel[0] shouldBe (red plusOrMinus TOLERANCE) }
    withClue("green of ${pixel.toList()}") { pixel[1] shouldBe (green plusOrMinus TOLERANCE) }
    withClue("blue of ${pixel.toList()}") { pixel[2] shouldBe (blue plusOrMinus TOLERANCE) }
  }

  // A Float on js is a double until it lands in a FloatArray, which holds 32 bits there as it does
  // everywhere else. An entry read back out of a uniform is compared at the width the uniform holds.
  private fun Float.stored(): Float = floatArrayOf(this)[0]

  private val ColorMatrix.entries: List<Float>
    get() = listOf(rr, rg, rb, rBias, gr, gg, gb, gBias, br, bg, bb, bBias)

  private companion object {
    const val RED = 0.8f
    const val GREEN = 0.4f
    const val BLUE = 0.1f
    const val GREY = 0.37f
    const val TOLERANCE = 0.0015f
    const val NITS_TOLERANCE = 0.1f
    const val MID_VALUE = 0.5f
    const val LARGE_FACTOR = 1e30f
    const val LONG_RUN = 64
  }
}

package dev.jordond.filmstrip.effects.spatial

import dev.jordond.filmstrip.color.BlurPlan
import dev.jordond.filmstrip.color.sdrSignalCeiling
import dev.jordond.filmstrip.color.sdrSignalFromHlgScene
import dev.jordond.filmstrip.color.sdrSignalFromNits
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.Media3Handle
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.MEDIA3_HDR_PEAK_NITS
import dev.jordond.filmstrip.effects.StepShaderProgram
import dev.jordond.filmstrip.effects.color.HdrColorMatrixUniforms
import dev.jordond.filmstrip.effects.media3SignalUniforms
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.pow
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What media3 is handed for a blur and a vignette, and the figures their steps read a kept grade through.
 */
class SpatialGlEffectHostTest {
  @Test
  fun aBlurAndAVignetteResolveToAPassCarryingTheKeptGrade() {
    val blur = GaussianBlur(0.05f)
    val vignette = Vignette()

    effectOf(blur, HdrTransfer.Hlg) shouldBe SpatialGlEffect(blur, HdrTransfer.Hlg)
    effectOf(vignette, null) shouldBe SpatialGlEffect(vignette, null)
  }

  @Test
  fun twoPassesOfOneSpecOnOneGradeCompareEqual() {
    (SpatialGlEffect(Vignette(0.25f), HdrTransfer.Pq) == SpatialGlEffect(Vignette(0.25f), HdrTransfer.Pq)) shouldBe true
    (SpatialGlEffect(Vignette(0.25f), HdrTransfer.Pq) == SpatialGlEffect(Vignette(0.25f), HdrTransfer.Hlg)) shouldBe
      false
    (SpatialGlEffect(GaussianBlur(0.05f), null) == SpatialGlEffect(GaussianBlur(0.025f), null)) shouldBe false
  }

  @Test
  fun aBlurStartsFromTheInputAndDrawsAtTheSharedPlansSize() {
    val blur = GaussianBlur(0.05f)
    val steps = SpatialGlEffect(blur, null).stepsIn(null)
    val plan = BlurPlan.of(blur.sigma.toDouble(), WIDTH)

    steps
      .first()
      .reads.values
      .toList() shouldBe listOf(StepShaderProgram.INPUT)
    steps.first().targetAt(WIDTH, HEIGHT) shouldBe Size(plan.width, plan.heightOf(HEIGHT))
    steps.last().targetAt(WIDTH, HEIGHT) shouldBe Size(WIDTH, HEIGHT)
  }

  @Test
  fun codesGoThroughWithEveryPowerAtOne() {
    val uniforms = SpatialGlEffect(Vignette(), null).stepsIn(null).single().uniformsAt(0.0, WIDTH, HEIGHT)

    listOf("uWhite", "uDisplayGamma", "uOotfGamma", "uCeiling").forEach { name ->
      withClue(name) { uniforms.getValue(name).single() shouldBe 1f }
    }
  }

  @Test
  fun aKeptPqGradeReachesTheSharedSignalAtAMidLight() {
    val domain = HdrTransfer.Pq.media3SignalUniforms
    val light = 0.1f

    domain.toSignal(light) shouldBe (sdrSignalFromNits(light * MEDIA3_HDR_PEAK_NITS).toDouble() plusOrMinus TOLERANCE)
    domain.ceiling shouldBe HdrTransfer.Pq.sdrSignalCeiling
  }

  @Test
  fun aKeptHlgGradeReachesTheSharedSignalAtAMidSceneLight() {
    val domain = HdrTransfer.Hlg.media3SignalUniforms
    val scene = 0.25f

    domain.toSignal(scene) shouldBe (sdrSignalFromHlgScene(scene).toDouble() plusOrMinus TOLERANCE)
    domain.ceiling shouldBe HdrTransfer.Hlg.sdrSignalCeiling
  }

  @Test
  fun aVignetteOnAKeptGradeIsHandedTheGradesFigures() {
    val domain = HdrTransfer.Hlg.media3SignalUniforms
    val uniforms = SpatialGlEffect(Vignette(), HdrTransfer.Hlg).stepsIn(domain).single().uniformsAt(0.0, WIDTH, HEIGHT)

    uniforms.getValue("uWhite").single() shouldBe domain.white
    uniforms.getValue("uDisplayGamma").single() shouldBe domain.displayGamma
    uniforms.getValue("uOotfGamma").single() shouldBe domain.ootfGamma
    uniforms.getValue("uCeiling").single() shouldBe domain.ceiling
  }

  // The shader's toSignal, on the figures it is handed.
  private fun HdrColorMatrixUniforms.toSignal(light: Float): Double =
    (light.toDouble().pow(ootfGamma.toDouble()) / white).pow(1.0 / displayGamma)

  private fun effectOf(
    spec: EffectSpec,
    transfer: HdrTransfer?,
  ): Any {
    val resolution = BuiltInEffectResolver().resolve(spec, CAPABILITIES, attributes(transfer))
    val handle = resolution.shouldBeInstanceOf<EffectResolution.Resolved>().effect.handle
    return handle.shouldBeInstanceOf<Media3Handle.Video>().effect
  }

  private fun attributes(transfer: HdrTransfer?): Attributes =
    Attributes(
      inputSize = Size(WIDTH, HEIGHT),
      outputSize = Size(WIDTH, HEIGHT),
      layoutSize = Size(WIDTH, HEIGHT),
      colorSpace = if (transfer == null) ColorSpace.Bt709 else ColorSpace.Bt2020,
      hdrTransfer = transfer,
      frameRate = 30f,
      span = TimeRange.of(Duration.ZERO, 1.seconds),
    )

  private companion object {
    const val WIDTH = 1280
    const val HEIGHT = 720
    const val TOLERANCE = 1e-5
    val CAPABILITIES =
      RenderCapabilities(
        api = RenderApi.OpenGlEs,
        supportsFragmentShader = true,
        supportsComputeShader = false,
        supportsHdr = true,
        colorSpaces = setOf(ColorSpace.Bt709, ColorSpace.Bt2020),
        maxTextureSize = 4_096,
        features = emptySet(),
      )
  }
}

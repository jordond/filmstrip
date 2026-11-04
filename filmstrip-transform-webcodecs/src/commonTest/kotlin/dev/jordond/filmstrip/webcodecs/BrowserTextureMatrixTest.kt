package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.geometry.Rotate
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The texture matrix each built-in geometry pass carries in its uniforms, read straight off the resolver.
 *
 * A pass's uniforms are public, so a caller writing into one must not move the matrix of any other pass.
 */
class BrowserTextureMatrixTest {
  @Test
  fun everyIdentityPassCarriesAMatrixOfItsOwn() {
    val resolver = BuiltInEffectResolver()
    for (spec in listOf(Scale(SCALED_HEIGHT), Rotate(0))) {
      val first = matrixOf(resolver, spec)
      val second = matrixOf(resolver, spec)
      first[0] = 0f

      withClue("$spec") { second[0] shouldBe 1f }
    }
  }

  private fun matrixOf(
    resolver: BuiltInEffectResolver,
    spec: EffectSpec,
  ): FloatArray =
    resolver
      .resolve(spec, CAPABILITIES, ATTRIBUTES)
      .shouldBeInstanceOf<EffectResolution.Resolved>()
      .effect.pass
      .uniforms.values
      .single()

  private companion object {
    const val SCALED_HEIGHT = 48
    val FRAME = Size(64, 48)
    val CAPABILITIES =
      RenderCapabilities(
        api = RenderApi.WebGl,
        supportsFragmentShader = true,
        supportsComputeShader = false,
        supportsHdr = false,
        colorSpaces = setOf(ColorSpace.Bt709),
        maxTextureSize = 4_096,
        features = emptySet(),
      )
    val ATTRIBUTES =
      Attributes(
        inputSize = FRAME,
        outputSize = FRAME,
        layoutSize = FRAME,
        colorSpace = ColorSpace.Bt709,
        hdrTransfer = null,
        frameRate = 30f,
        span = TimeRange.of(Duration.ZERO, 1.seconds),
      )
  }
}

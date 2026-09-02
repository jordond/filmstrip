package dev.jordond.filmstrip.media3

import androidx.media3.effect.RgbMatrix
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.MEDIA3_HDR_PEAK_NITS
import dev.jordond.filmstrip.effects.color.Contrast
import dev.jordond.filmstrip.effects.color.HdrColorMatrixEffect
import dev.jordond.filmstrip.effects.color.Saturation
import dev.jordond.filmstrip.effects.color.colorMatrixOf
import dev.jordond.filmstrip.effects.color.hdrColorMatrixUniforms
import dev.jordond.filmstrip.effects.color.toColumnMajor4x4
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HDR_REFERENCE_WHITE_NITS
import dev.jordond.filmstrip.media.HLG_NOMINAL_PEAK_NITS
import dev.jordond.filmstrip.media.HLG_SYSTEM_GAMMA
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.SDR_DISPLAY_GAMMA
import dev.jordond.filmstrip.media.sdrSignalCeiling
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What a colour matrix lowers to on each of the two colour spaces media3 works in.
 *
 * On SDR media3's own matrix multiplies the encoded signal the matrix is written against. A kept
 * grade reaches the shader as linear light, so the matrix travels in a pass of its own that moves
 * the light into that signal and back. Brightness alone keeps a lowering of its own on a grade,
 * covered by the device tests since building it calls into `android.opengl.Matrix`.
 */
class Media3ColorGradeTest {
  private val resolver = BuiltInEffectResolver()

  @Test
  fun `a colour matrix runs through the hdr pass on an export that keeps its grade`() {
    val effect = hdrPassFor(Contrast(FACTOR), HdrTransfer.Pq)

    assertEquals(HdrTransfer.Pq, effect.transfer)
    assertEquals(colorMatrixOf(Contrast(FACTOR)), effect.matrix)
  }

  @Test
  fun `a colour matrix resolves to media3's own matrix on an sdr export`() {
    val resolution = resolver.resolve(Contrast(FACTOR), capabilities(hdr = false), attributes(null))

    assertIs<RgbMatrix>(assertIs<EffectResolution.Resolved>(resolution).effect.handle)
  }

  @Test
  fun `the pass carries the matrix as the columns the shader multiplies by`() {
    val effect = hdrPassFor(Saturation(FACTOR), HdrTransfer.Hlg)

    assertContentEquals(checkNotNull(colorMatrixOf(Saturation(FACTOR))).toColumnMajor4x4(), effect.columns)
  }

  // Every figure the shader reads comes off the shared constants, so a change to what reference
  // white or the display gamma means moves the pass with it rather than leaving a typed copy behind.
  @Test
  fun `a pq frame is read as display light against media3's peak`() {
    val uniforms = HdrTransfer.Pq.hdrColorMatrixUniforms()

    assertEquals(HDR_REFERENCE_WHITE_NITS / MEDIA3_HDR_PEAK_NITS, uniforms.white)
    assertEquals(SDR_DISPLAY_GAMMA.toFloat(), uniforms.displayGamma)
    assertEquals(1f, uniforms.ootfGamma)
    assertEquals(HdrTransfer.Pq.sdrSignalCeiling, uniforms.ceiling)
  }

  @Test
  fun `an hlg frame is read as scene light through the system gamma`() {
    val uniforms = HdrTransfer.Hlg.hdrColorMatrixUniforms()

    assertEquals(HDR_REFERENCE_WHITE_NITS / HLG_NOMINAL_PEAK_NITS, uniforms.white)
    assertEquals(SDR_DISPLAY_GAMMA.toFloat(), uniforms.displayGamma)
    assertEquals(HLG_SYSTEM_GAMMA.toFloat(), uniforms.ootfGamma)
    assertEquals(HdrTransfer.Hlg.sdrSignalCeiling, uniforms.ceiling)
  }

  @Test
  fun `the two transfer functions do not read the frame the same way`() {
    val pq = HdrTransfer.Pq.hdrColorMatrixUniforms()
    val hlg = HdrTransfer.Hlg.hdrColorMatrixUniforms()

    assertNotEquals(pq.ootfGamma, hlg.ootfGamma)
    assertNotEquals(pq.ceiling, hlg.ceiling)
  }

  @Test
  fun `the pass compares by the matrix and the transfer it carries`() {
    val contrast = hdrPassFor(Contrast(FACTOR), HdrTransfer.Pq)

    assertEquals(hdrPassFor(Contrast(FACTOR), HdrTransfer.Pq), contrast)
    assertNotEquals(hdrPassFor(Contrast(FACTOR), HdrTransfer.Hlg), contrast)
    assertNotEquals(hdrPassFor(Saturation(FACTOR), HdrTransfer.Pq), contrast)
  }

  private fun hdrPassFor(
    spec: EffectSpec,
    transfer: HdrTransfer,
  ): HdrColorMatrixEffect {
    val resolution = resolver.resolve(spec, capabilities(hdr = true), attributes(transfer))

    return assertIs<HdrColorMatrixEffect>(assertIs<EffectResolution.Resolved>(resolution).effect.handle)
  }

  private fun attributes(transfer: HdrTransfer?) =
    Attributes(
      inputSize = FRAME,
      outputSize = FRAME,
      layoutSize = FRAME,
      colorSpace = if (transfer == null) ColorSpace.Bt709 else ColorSpace.Bt2020,
      hdrTransfer = transfer,
      frameRate = 30f,
      span = TimeRange.of(Duration.ZERO, 1.seconds),
    )

  private fun capabilities(hdr: Boolean) =
    RenderCapabilities(
      api = RenderApi.OpenGlEs,
      supportsFragmentShader = true,
      supportsComputeShader = false,
      supportsHdr = hdr,
      colorSpaces = setOf(ColorSpace.Bt709, ColorSpace.Bt2020),
      maxTextureSize = 8_192,
      features = emptySet(),
    )

  private companion object {
    val FRAME = Size(1280, 720)

    const val FACTOR = 0.5f
  }
}

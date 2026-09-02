package dev.jordond.filmstrip.effects.color

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effects.MEDIA3_HDR_PEAK_NITS
import dev.jordond.filmstrip.media.HDR_REFERENCE_WHITE_NITS
import dev.jordond.filmstrip.media.HLG_NOMINAL_PEAK_NITS
import dev.jordond.filmstrip.media.HdrTransfer

/**
 * Recombines the colour channels of a frame media3 holds as HDR linear light.
 *
 * media3 multiplies its own colour matrices into the light itself, which is not the domain a
 * [ColorMatrix] is written in. This pass moves each channel into the signal an SDR display at
 * reference white would have been fed, applies the matrix there, floors it at black, and moves the
 * result back, clamped where the format runs out. One pass over one 2D sampler, built fresh for
 * every graph.
 *
 * media3 says which domain the frame is actually in when it asks for the shader program, and it is
 * not always the one the plan resolved: a readback and a thumbnail run the same lowered chain
 * through a graph that tone-maps to BT.709 first. The matrix runs on the sample as it is there,
 * since a tone-mapped frame already holds the signal the entries are written for.
 *
 * @property matrix The matrix to apply, as authored against the encoded SDR signal.
 * @property transfer The transfer function the grade is written in, which decides how the light in
 * the texture is read and where the ceiling sits.
 */
@InternalFilmstripApi
public class HdrColorMatrixEffect(
  public val matrix: ColorMatrix,
  public val transfer: HdrTransfer,
) : GlEffect {
  /**
   * The matrix as the `mat4` the shader multiplies by.
   */
  public val columns: FloatArray = matrix.toColumnMajor4x4()

  /**
   * The figures the shader reads the frame's light through. media3 stores PQ against the peak it
   * decodes to and HLG against its nominal peak, and it hands HLG over as scene light.
   */
  public val uniforms: HdrColorMatrixUniforms =
    transfer.hdrColorMatrixUniforms(
      white =
        HDR_REFERENCE_WHITE_NITS /
          when (transfer) {
            HdrTransfer.Pq -> MEDIA3_HDR_PEAK_NITS
            HdrTransfer.Hlg -> HLG_NOMINAL_PEAK_NITS
          },
      holdsSceneLight = transfer == HdrTransfer.Hlg,
    )

  override fun toGlShaderProgram(
    context: Context,
    useHdr: Boolean,
  ): GlShaderProgram = HdrColorMatrixShaderProgram(useHdr) { this }

  // Compared by value because media3 compares the whole effect list against the one the last input
  // stream was configured with, in DefaultVideoFrameProcessor.configure, and rebuilds every shader
  // program when they differ. Identity equality there would rebuild this pass at every clip
  // boundary.
  override fun equals(other: Any?): Boolean =
    this === other || (other is HdrColorMatrixEffect && matrix == other.matrix && transfer == other.transfer)

  override fun hashCode(): Int = HASH * matrix.hashCode() + transfer.hashCode()

  private companion object {
    const val HASH = 31
  }
}

/**
 * Draws [HdrColorMatrixEffect]'s pass.
 *
 * The effect is read through [current] on every draw rather than held, so a slot that swaps the
 * matrix while the graph runs reaches the next frame without the graph being rebuilt. The output
 * texture is allocated at high precision whenever the graph runs in HDR, since the light it holds
 * goes past one.
 *
 * @param useHdr Whether the graph holds HDR light, which decides the output texture's precision and
 * which domain the matrix runs in. media3 answers this per graph, so a readback that tone-maps to
 * BT.709 gets the signal arm of the shader while the export of the same edit gets the light one.
 * @param current Where the matrix and its figures are read from on each draw.
 */
@InternalFilmstripApi
public class HdrColorMatrixShaderProgram(
  private val useHdr: Boolean,
  private val current: () -> HdrColorMatrixEffect,
) : BaseGlShaderProgram(useHdr, TEXTURE_POOL_CAPACITY) {
  private val program =
    try {
      GlProgram(VERTEX_SHADER, FRAGMENT_SHADER).apply {
        setBufferAttribute(
          "aFramePosition",
          GlUtil.getNormalizedCoordinateBounds(),
          GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )
      }
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException(e)
    }

  override fun configure(
    inputWidth: Int,
    inputHeight: Int,
  ): Size = Size(inputWidth, inputHeight)

  override fun drawFrame(
    inputTexId: Int,
    presentationTimeUs: Long,
  ) {
    val effect = current()
    try {
      program.use()
      program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
      program.setFloatsUniform("uColorMatrix", effect.columns)
      program.setFloatUniform("uHdr", if (useHdr) 1f else 0f)
      program.setFloatUniform("uWhite", effect.uniforms.white)
      program.setFloatUniform("uDisplayGamma", effect.uniforms.displayGamma)
      program.setFloatUniform("uOotfGamma", effect.uniforms.ootfGamma)
      program.setFloatUniform("uCeiling", effect.uniforms.ceiling)
      program.bindAttributesAndUniforms()
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
      GlUtil.checkGlError()
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException.from(e, presentationTimeUs)
    }
  }

  override fun release() {
    super.release()
    try {
      program.delete()
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException(e)
    }
  }

  private companion object {
    const val TEXTURE_POOL_CAPACITY = 1
  }
}

private val VERTEX_SHADER =
  """
  #version 300 es
  in vec4 aFramePosition;
  out vec2 vTexSamplingCoord;
  void main() {
    gl_Position = aFramePosition;
    vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
  }
  """.trimIndent()

// Every figure comes in as a uniform. On the light arm the texture holds light past one, so nothing
// clamps until the matrix has run, and then only at the format's own ceiling. The signal arm is what
// a graph media3 tone-mapped to BT.709 gets: the sample is already the encoded signal the matrix is
// written for, so it runs there and clamps at white.
private val FRAGMENT_SHADER =
  """
  #version 300 es
  precision highp float;
  uniform sampler2D uTexSampler;
  uniform mat4 uColorMatrix;
  uniform float uHdr;
  uniform float uWhite;
  uniform float uDisplayGamma;
  uniform float uOotfGamma;
  uniform float uCeiling;
  in vec2 vTexSamplingCoord;
  out vec4 outColor;
  $HDR_COLOR_MATRIX_GLSL
  void main() {
    vec4 sampled = texture(uTexSampler, vTexSamplingCoord);
    if (uHdr < 0.5) {
      outColor = vec4(clamp((uColorMatrix * vec4(sampled.rgb, 1.0)).rgb, 0.0, 1.0), sampled.a);
    } else {
      vec3 graded =
        filmstripGradeHdr(sampled.rgb, uColorMatrix, uWhite, uDisplayGamma, uOotfGamma, uCeiling);
      outColor = vec4(graded, sampled.a);
    }
  }
  """.trimIndent()

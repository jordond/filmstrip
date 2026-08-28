package dev.jordond.filmstrip.media3.internal

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import dev.jordond.filmstrip.media.HdrTransfer

/**
 * Paints an opaque fill colour wherever a frame carries no pixels of its own.
 *
 * media3 clears a letterbox bar or a timeline gap to black with zero alpha, and a colour effect
 * changes a pixel's colour without ever touching its alpha. Run last in the composition's effect
 * chain, this reads that leftover alpha to tell an empty pixel from a real one and writes [color]
 * over it, whatever colour an earlier effect left behind.
 *
 * @property color Packed ARGB the empty region is painted with. Alpha is ignored, the pass always
 *   writes full opacity.
 * @property transfer The transfer function reaching the encoder, or null when the export is SDR.
 *   An HDR frame reaches this pass as linear light, where an sRGB channel means nothing on its own.
 */
internal class FillFlatten(
  private val color: Int,
  private val transfer: HdrTransfer? = null,
) : GlEffect {
  override fun toGlShaderProgram(
    context: Context,
    useHdr: Boolean,
  ): GlShaderProgram = FillFlattenShaderProgram(useHdr, fillComponents(color, transfer.takeIf { useHdr }))
}

/**
 * Runs [FillFlatten]'s single full-frame pass.
 *
 * Mixes the fill colour under a pixel's own colour by that pixel's alpha, then writes the result
 * back fully opaque.
 */
private class FillFlattenShaderProgram(
  useHdr: Boolean,
  fill: FloatArray,
) : BaseGlShaderProgram(useHdr, 1) {
  private val glProgram =
    try {
      GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException(e)
    }

  init {
    glProgram.setBufferAttribute(
      "aFramePosition",
      GlUtil.getNormalizedCoordinateBounds(),
      GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
    )
    glProgram.setFloatsUniform("uFill", fill)
  }

  override fun configure(
    inputWidth: Int,
    inputHeight: Int,
  ): Size = Size(inputWidth, inputHeight)

  override fun drawFrame(
    inputTexId: Int,
    presentationTimeUs: Long,
  ) {
    try {
      glProgram.use()
      glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
      glProgram.bindAttributesAndUniforms()
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException(e, presentationTimeUs)
    }
  }

  override fun release() {
    super.release()
    try {
      glProgram.delete()
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException(e)
    }
  }
}

private val VERTEX_SHADER =
  """
  #version 100
  attribute vec4 aFramePosition;
  varying vec2 vTexSamplingCoord;
  void main() {
    gl_Position = aFramePosition;
    vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
  }
  """.trimIndent()

private val FRAGMENT_SHADER =
  """
  #version 100
  precision highp float;
  uniform sampler2D uTexSampler;
  uniform vec3 uFill;
  varying vec2 vTexSamplingCoord;
  void main() {
    vec4 src = texture2D(uTexSampler, vTexSamplingCoord);
    gl_FragColor = vec4(mix(uFill, src.rgb, src.a), 1.0);
  }
  """.trimIndent()

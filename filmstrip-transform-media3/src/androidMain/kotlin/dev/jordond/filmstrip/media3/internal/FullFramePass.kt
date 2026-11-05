package dev.jordond.filmstrip.media3.internal

import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram

/**
 * One draw of [fragmentShader] over the whole input texture, into an output the same size as the input.
 *
 * The vertex stage covers the frame and hands the fragment stage `vTexSamplingCoord`, and the input is bound to
 * `uTexSampler`. [uniforms] sets anything else the fragment stage reads, once, as the program is built.
 */
internal class FullFramePass(
  useHdr: Boolean,
  fragmentShader: String,
  uniforms: GlProgram.() -> Unit = {},
) : BaseGlShaderProgram(useHdr, 1) {
  private val glProgram =
    try {
      GlProgram(FRAME_VERTEX_SHADER, fragmentShader)
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException(e)
    }

  init {
    glProgram.setBufferAttribute(
      "aFramePosition",
      GlUtil.getNormalizedCoordinateBounds(),
      GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
    )
    glProgram.uniforms()
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

// Covers the frame and hands the fragment stage where it is in it, from the bottom left.
internal val FRAME_VERTEX_SHADER =
  """
  #version 100
  attribute vec4 aFramePosition;
  varying vec2 vTexSamplingCoord;
  void main() {
    gl_Position = aFramePosition;
    vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
  }
  """.trimIndent()

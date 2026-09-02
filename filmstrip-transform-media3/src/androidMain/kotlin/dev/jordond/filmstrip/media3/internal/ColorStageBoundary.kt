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

/**
 * Ends the shader program a clip's colour matrices were merged into, clamping what they came to.
 *
 * media3 folds every consecutive `RgbMatrix` and matrix transformation into one program and does not
 * clamp between them, so a clip's grade and the composition's would multiply together and clamp
 * once. The planner folds a run of colour effects per stage, and the other backends write the clip's
 * stage into a frame before the composition's runs, so this pass puts the same boundary here: one
 * full-frame draw between the two chains, clamping to the range a frame can hold.
 *
 * Only an SDR chain needs it. A kept grade lowers its colour to a pass of its own, which already
 * ends the program and clamps at the transfer's ceiling as it writes.
 */
internal class ColorStageBoundary : GlEffect {
  override fun toGlShaderProgram(
    context: Context,
    useHdr: Boolean,
  ): GlShaderProgram = ColorStageBoundaryShaderProgram(useHdr)

  // Compared by value so a preview can tell a re-lowering that changed the chain from one that
  // produced the same chain again. Every instance stands for the same pass.
  override fun equals(other: Any?): Boolean = this === other || other is ColorStageBoundary

  override fun hashCode(): Int = HASH

  private companion object {
    const val HASH = 0x0C0105
  }
}

private class ColorStageBoundaryShaderProgram(
  useHdr: Boolean,
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

// Alpha is carried through untouched: a letterbox bar and a timeline gap are told apart by it
// further down the chain.
private val FRAGMENT_SHADER =
  """
  #version 100
  precision highp float;
  uniform sampler2D uTexSampler;
  varying vec2 vTexSamplingCoord;
  void main() {
    vec4 sampled = texture2D(uTexSampler, vTexSamplingCoord);
    gl_FragColor = vec4(clamp(sampled.rgb, 0.0, 1.0), sampled.a);
  }
  """.trimIndent()

package dev.jordond.filmstrip.effects.filters.glitch

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import dev.jordond.filmstrip.effects.filters.internal.FULL_FRAME_VERTEX_SHADER
import dev.jordond.filmstrip.effects.filters.internal.requireGles3

/**
 * Draws a [Glitch] as one media3 pass running [GlitchShader].
 *
 * The planner tone maps an HDR source for a [Glitch], so a frame processor in HDR here is a bug and is refused by name.
 * [GlitchShader] is GLSL ES 3.00, which media3 can only link on an OpenGL ES 3 context. That is the device's limit,
 * and a context below it is refused by name too.
 */
internal class GlitchEffect(
  private val glitch: Glitch,
) : GlEffect {
  override fun toGlShaderProgram(
    context: Context,
    useHdr: Boolean,
  ): GlShaderProgram {
    if (useHdr) throw VideoFrameProcessingException("Glitch requires SDR, and this frame processor runs in HDR.")
    try {
      requireGles3("Glitch", GlUtil.getContextMajorVersion())
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException(e)
    }
    return GlitchShaderProgram(glitch)
  }

  // Compared by value so a preview can tell a re-lowering that changed this pass from one that produced it again.
  override fun equals(other: Any?): Boolean = this === other || (other is GlitchEffect && glitch == other.glitch)

  override fun hashCode(): Int = glitch.hashCode()
}

/**
 * Runs [GlitchShader] over a frame the same size as the input.
 *
 * A frame's uniforms come from its presentation time alone, so a seek lands on the bursts an export draws there.
 */
internal class GlitchShaderProgram(
  private val glitch: Glitch,
) : BaseGlShaderProgram(false, TEXTURE_POOL_CAPACITY) {
  private var width = 1
  private var height = 1

  private val program =
    try {
      GlProgram(FULL_FRAME_VERTEX_SHADER, GlitchShader.FRAGMENT).apply {
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
  ): Size {
    width = inputWidth
    height = inputHeight
    return Size(inputWidth, inputHeight)
  }

  override fun drawFrame(
    inputTexId: Int,
    presentationTimeUs: Long,
  ) {
    try {
      program.use()
      program.setSamplerTexIdUniform(GlitchShader.TEXTURE, inputTexId, 0)
      program.setFloatsUniform(GlitchShader.RESOLUTION, floatArrayOf(width.toFloat(), height.toFloat()))
      // A GL texture runs from the bottom left.
      program.setFloatUniform(GlitchShader.FLIP_V, 1f)
      GlitchShader.uniforms(glitch, presentationTimeUs / MICROS_PER_SECOND).forEach { (name, value) ->
        program.setFloatUniform(name, value)
      }
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
    const val MICROS_PER_SECOND = 1_000_000.0
  }
}

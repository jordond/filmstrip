package dev.jordond.filmstrip.effects.filters.vhs

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
 * Draws a [Vhs] as one media3 pass running [VhsShader].
 *
 * The planner tone maps an HDR source for a [Vhs], so a frame processor in HDR here is a bug and is refused by name.
 * [VhsShader] is GLSL ES 3.00, which media3 can only link on an OpenGL ES 3 context. That is the device's limit, and a
 * context below it is refused by name too.
 */
internal class VhsEffect(
  private val vhs: Vhs,
) : GlEffect {
  override fun toGlShaderProgram(
    context: Context,
    useHdr: Boolean,
  ): GlShaderProgram {
    if (useHdr) throw VideoFrameProcessingException("Vhs requires SDR, and this frame processor runs in HDR.")
    try {
      requireGles3(GlUtil.getContextMajorVersion())
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException(e)
    }
    return VhsShaderProgram(vhs)
  }

  // Compared by value so a preview can tell a re-lowering that changed this pass from one that produced it again.
  override fun equals(other: Any?): Boolean = this === other || (other is VhsEffect && vhs == other.vhs)

  override fun hashCode(): Int = vhs.hashCode()
}

/**
 * Refuses a [Vhs] by name on a GL context below the OpenGL ES 3 that [VhsShader] is written for.
 */
internal fun requireGles3(contextMajorVersion: Long) {
  if (contextMajorVersion < MIN_GLES_MAJOR) {
    throw VideoFrameProcessingException(
      "Vhs needs OpenGL ES $MIN_GLES_MAJOR, and this device's context is OpenGL ES $contextMajorVersion.",
    )
  }
}

/**
 * Runs [VhsShader] over a frame the same size as the input.
 *
 * A frame's uniforms come from its presentation time alone, so a seek lands on the pattern an export draws there.
 */
internal class VhsShaderProgram(
  private val vhs: Vhs,
) : BaseGlShaderProgram(false, TEXTURE_POOL_CAPACITY) {
  private var width = 1
  private var height = 1

  private val program =
    try {
      GlProgram(VERTEX_SHADER, VhsShader.FRAGMENT).apply {
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
      program.setSamplerTexIdUniform(VhsShader.TEXTURE, inputTexId, 0)
      program.setFloatsUniform(VhsShader.RESOLUTION, floatArrayOf(width.toFloat(), height.toFloat()))
      // A GL texture runs from the bottom left.
      program.setFloatUniform(VhsShader.FLIP_V, 1f)
      VhsShader.uniforms(vhs, presentationTimeUs / MICROS_PER_SECOND).forEach { (name, value) ->
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

private const val MIN_GLES_MAJOR = 3L

private val VERTEX_SHADER =
  """
  #version 300 es
  in vec4 aFramePosition;
  out vec2 vUv;
  void main() {
    gl_Position = aFramePosition;
    vUv = aFramePosition.xy * 0.5 + 0.5;
  }
  """.trimIndent()

package dev.jordond.filmstrip.effects.filters.pixel

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
 * Draws a [Pixel] as one media3 pass running [PixelShader].
 *
 * The planner tone maps an HDR source for a [Pixel], so a frame processor in HDR here is a bug and is refused by name.
 * [PixelShader] is GLSL ES 3.00, which media3 can only link on an OpenGL ES 3 context. That is the device's limit, and
 * a context below it is refused by name too.
 */
internal class PixelEffect(
  private val pixel: Pixel,
) : GlEffect {
  override fun toGlShaderProgram(
    context: Context,
    useHdr: Boolean,
  ): GlShaderProgram {
    if (useHdr) throw VideoFrameProcessingException("Pixel requires SDR, and this frame processor runs in HDR.")
    try {
      requireGles3("Pixel", GlUtil.getContextMajorVersion())
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException(e)
    }
    return PixelShaderProgram(pixel)
  }

  // Compared by value so a preview can tell a re-lowering that changed this pass from one that produced it again.
  override fun equals(other: Any?): Boolean = this === other || (other is PixelEffect && pixel == other.pixel)

  override fun hashCode(): Int = pixel.hashCode()
}

/**
 * Runs [PixelShader] over a frame the same size as the input.
 *
 * Nothing it draws reads the clock, so a frame's uniforms come from its size alone and only change on [configure].
 * [PixelShader.uniforms] holds only the names this fragment declares, since [GlProgram] refuses any other. Every
 * texture media3 hands it runs up the picture, so [PixelShader.FLIP_V] is always 1.
 */
internal class PixelShaderProgram(
  private val pixel: Pixel,
) : BaseGlShaderProgram(false, TEXTURE_POOL_CAPACITY) {
  private var uniforms: Map<String, FloatArray> = emptyMap()

  private val program =
    try {
      GlProgram(FULL_FRAME_VERTEX_SHADER, PixelShader.fragment(pixel.palette)).apply {
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
    uniforms = PixelShader.uniforms(pixel, inputWidth, inputHeight)
    return Size(inputWidth, inputHeight)
  }

  override fun drawFrame(
    inputTexId: Int,
    presentationTimeUs: Long,
  ) {
    try {
      program.use()
      program.setSamplerTexIdUniform(PixelShader.TEXTURE, inputTexId, 0)
      uniforms.forEach { (name, value) -> program.setFloatsUniform(name, value) }
      program.setFloatUniform(PixelShader.FLIP_V, 1f)
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

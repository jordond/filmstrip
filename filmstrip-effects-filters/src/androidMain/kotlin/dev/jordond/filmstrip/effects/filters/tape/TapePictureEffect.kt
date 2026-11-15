package dev.jordond.filmstrip.effects.filters.tape

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import dev.jordond.filmstrip.effect.PreviewClock
import dev.jordond.filmstrip.effects.filters.internal.FULL_FRAME_VERTEX_SHADER
import dev.jordond.filmstrip.effects.filters.internal.requireGles3

/**
 * Draws a [TapeLook] as one media3 pass running [TapePictureShader].
 *
 * The planner tone maps an HDR source for a [TapeLook], so a frame processor in HDR here is a bug and is refused by
 * name. [TapePictureShader] is GLSL ES 3.00, which media3 can only link on an OpenGL ES 3 context. That is the device's
 * limit, and a context below it is refused by name too.
 *
 * @param previewClock The clock of the preview this is drawn in, or null in an export.
 */
internal class TapePictureEffect(
  private val look: TapeLook,
  private val previewClock: PreviewClock? = null,
) : GlEffect {
  override fun toGlShaderProgram(
    context: Context,
    useHdr: Boolean,
  ): GlShaderProgram {
    if (useHdr) {
      throw VideoFrameProcessingException(
        "${look.id.value} requires SDR, and this frame processor runs in HDR.",
      )
    }
    try {
      requireGles3(look.id.value, GlUtil.getContextMajorVersion())
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException(e)
    }
    return TapePictureShaderProgram(look, previewClock)
  }

  // Compared by value so a preview can tell a re-lowering that changed this pass from one that produced it again.
  override fun equals(other: Any?): Boolean =
    this === other || (other is TapePictureEffect && look == other.look && previewClock === other.previewClock)

  override fun hashCode(): Int = look.hashCode()
}

/**
 * Runs [TapePictureShader] over a frame the same size as the input.
 *
 * A frame's uniforms come from [TapePictureSignal.tapeSeconds] of its presentation time, so without a preview clock a
 * seek lands on the pattern an export draws there.
 */
internal class TapePictureShaderProgram(
  private val look: TapeLook,
  private val previewClock: PreviewClock? = null,
) : BaseGlShaderProgram(false, TEXTURE_POOL_CAPACITY) {
  private var width = 1
  private var height = 1

  private val program =
    try {
      GlProgram(FULL_FRAME_VERTEX_SHADER, TapePictureShader.FRAGMENT).apply {
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
      program.setSamplerTexIdUniform(TapePictureShader.TEXTURE, inputTexId, 0)
      program.setFloatsUniform(TapePictureShader.RESOLUTION, floatArrayOf(width.toFloat(), height.toFloat()))
      // A GL texture runs from the bottom left.
      program.setFloatUniform(TapePictureShader.FLIP_V, 1f)
      val seconds = TapePictureSignal.tapeSeconds(look, presentationTimeUs / MICROS_PER_SECOND, previewClock)
      TapePictureShader.uniforms(look, seconds).forEach { (name, value) ->
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

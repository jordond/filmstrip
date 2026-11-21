package dev.jordond.filmstrip.effects.filters.bloom

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlUtil
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import dev.jordond.filmstrip.effects.StepShaderProgram
import dev.jordond.filmstrip.effects.filters.internal.requireGles3
import dev.jordond.filmstrip.effects.filters.internal.toGlSteps
import dev.jordond.filmstrip.effects.stepTargetFormat

/**
 * Draws a [Bloom] as a media3 pass running [BloomShader]'s chain.
 *
 * The planner tone maps an HDR source for a [Bloom], so a frame processor in HDR here is a bug and is refused by name.
 * [BloomShader] is GLSL ES 3.00, which media3 can only link on an OpenGL ES 3 context. That is the device's limit, and
 * a context below it is refused by name too.
 */
internal class BloomEffect(
  private val bloom: Bloom,
) : GlEffect {
  override fun toGlShaderProgram(
    context: Context,
    useHdr: Boolean,
  ): GlShaderProgram {
    if (useHdr) throw VideoFrameProcessingException("Bloom requires SDR, and this frame processor runs in HDR.")
    try {
      requireGles3("Bloom", GlUtil.getContextMajorVersion())
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException(e)
    }
    return bloomShaderProgram(bloom, stepTargetFormat(GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty()))
  }

  // Compared by value so a preview can tell a re-lowering that changed this pass from one that produced it again.
  override fun equals(other: Any?): Boolean = this === other || (other is BloomEffect && bloom == other.bloom)

  override fun hashCode(): Int = bloom.hashCode()
}

/**
 * A [StepShaderProgram] running [BloomShader]'s steps for [bloom], each target in [targetFormat].
 *
 * The chain runs on straight color, since media3 hands frames over that way.
 */
internal fun bloomShaderProgram(
  bloom: Bloom,
  targetFormat: Int,
): StepShaderProgram =
  StepShaderProgram(BloomShader.steps(bloom).toGlSteps(), targetFormat, useHighPrecisionColorComponents = false)

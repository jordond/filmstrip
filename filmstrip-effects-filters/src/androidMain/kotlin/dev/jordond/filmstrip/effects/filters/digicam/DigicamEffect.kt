package dev.jordond.filmstrip.effects.filters.digicam

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
 * Draws a [Digicam] as a media3 pass running [DigicamShader]'s chain.
 *
 * The planner tone maps an HDR source for a [Digicam], so a frame processor in HDR here is a bug and is refused by
 * name. [DigicamShader] is GLSL ES 3.00, which media3 can only link on an OpenGL ES 3 context. That is the device's
 * limit, and a context below it is refused by name too.
 */
internal class DigicamEffect(
  private val digicam: Digicam,
) : GlEffect {
  override fun toGlShaderProgram(
    context: Context,
    useHdr: Boolean,
  ): GlShaderProgram {
    if (useHdr) throw VideoFrameProcessingException("Digicam requires SDR, and this frame processor runs in HDR.")
    try {
      requireGles3("Digicam", GlUtil.getContextMajorVersion())
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException(e)
    }
    return digicamShaderProgram(digicam, stepTargetFormat(GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty()))
  }

  // Compared by value so a preview can tell a re-lowering that changed this pass from one that produced it again.
  override fun equals(other: Any?): Boolean = this === other || (other is DigicamEffect && digicam == other.digicam)

  override fun hashCode(): Int = digicam.hashCode()
}

/**
 * A [StepShaderProgram] running [DigicamShader]'s steps for [digicam], each target in [targetFormat].
 *
 * The chain reads [DigicamShader.FLIP_V] as 1, since a GL texture runs from the bottom left, and runs on straight
 * color, since media3 hands frames over that way.
 */
internal fun digicamShaderProgram(
  digicam: Digicam,
  targetFormat: Int,
): StepShaderProgram =
  StepShaderProgram(
    DigicamShader.steps(digicam).toGlSteps(mapOf(DigicamShader.FLIP_V to floatArrayOf(1f))),
    targetFormat,
    useHighPrecisionColorComponents = false,
  )

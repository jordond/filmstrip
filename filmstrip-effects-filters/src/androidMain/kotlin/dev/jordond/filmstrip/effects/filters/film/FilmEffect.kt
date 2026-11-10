package dev.jordond.filmstrip.effects.filters.film

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlUtil
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import dev.jordond.filmstrip.effects.GlStep
import dev.jordond.filmstrip.effects.StepShaderProgram
import dev.jordond.filmstrip.effects.filters.internal.requireGles3
import dev.jordond.filmstrip.effects.stepTargetFormat

/**
 * Draws a [Film] as a media3 pass running [FilmShader]'s chain.
 *
 * The planner tone maps an HDR source for a [Film], so a frame processor in HDR here is a bug and is refused by name.
 * [FilmShader] is GLSL ES 3.00, which media3 can only link on an OpenGL ES 3 context. That is the device's limit, and a
 * context below it is refused by name too.
 */
internal class FilmEffect(
  private val film: Film,
) : GlEffect {
  override fun toGlShaderProgram(
    context: Context,
    useHdr: Boolean,
  ): GlShaderProgram {
    if (useHdr) throw VideoFrameProcessingException("Film requires SDR, and this frame processor runs in HDR.")
    try {
      requireGles3("Film", GlUtil.getContextMajorVersion())
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException(e)
    }
    return filmShaderProgram(film, stepTargetFormat(GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty()))
  }

  // Compared by value so a preview can tell a re-lowering that changed this pass from one that produced it again.
  override fun equals(other: Any?): Boolean = this === other || (other is FilmEffect && film == other.film)

  override fun hashCode(): Int = film.hashCode()
}

/**
 * A [StepShaderProgram] running [FilmShader]'s steps for [film], each target in [targetFormat].
 *
 * The chain reads [FilmShader.FLIP_V] as 1, since a GL texture runs from the bottom left, and runs on straight color,
 * since media3 hands frames over that way.
 */
internal fun filmShaderProgram(
  film: Film,
  targetFormat: Int,
): StepShaderProgram =
  StepShaderProgram(
    FilmShader.steps(film).map { step ->
      GlStep(
        step.name,
        step.fragment,
        step.reads.mapValues { (_, source) -> if (source == FilmShader.INPUT) StepShaderProgram.INPUT else source },
        step.targetAt,
      ) { seconds, width, height -> step.uniformsAt(seconds, width, height) + (FilmShader.FLIP_V to FLIPPED) }
    },
    targetFormat,
    useHighPrecisionColorComponents = false,
  )

private val FLIPPED = floatArrayOf(1f)

package dev.jordond.filmstrip.effects.filters.film

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLES30
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
    return FilmShaderProgram(film, stepTargetFormat(GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty()))
  }

  // Compared by value so a preview can tell a re-lowering that changed this pass from one that produced it again.
  override fun equals(other: Any?): Boolean = this === other || (other is FilmEffect && film == other.film)

  override fun hashCode(): Int = film.hashCode()
}

/**
 * Runs [FilmShader]'s steps over a frame, each step before the last on a target of its own and the last on the output
 * media3 has focused.
 *
 * A frame's uniforms come from its presentation time and size alone, so a seek lands on the look an export draws there.
 * Each target is in [targetFormat], which [stepTargetFormat] picks for a device.
 */
internal class FilmShaderProgram(
  film: Film,
  private val targetFormat: Int,
) : BaseGlShaderProgram(false, TEXTURE_POOL_CAPACITY) {
  private val steps: List<LinkedStep>
  private var targets: Map<String, StepTarget> = emptyMap()
  private var width = 1
  private var height = 1

  init {
    try {
      steps = FilmShader.steps(film).map(::LinkedStep)
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException(e)
    }
  }

  override fun configure(
    inputWidth: Int,
    inputHeight: Int,
  ): Size {
    width = inputWidth
    height = inputHeight
    try {
      releaseTargets()
      targets =
        steps.dropLast(1).associate { linked ->
          val size = linked.step.targetAt(inputWidth, inputHeight)
          linked.step.name to StepTarget.of(size.width, size.height, targetFormat)
        }
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException(e)
    }
    return Size(inputWidth, inputHeight)
  }

  override fun drawFrame(
    inputTexId: Int,
    presentationTimeUs: Long,
  ) {
    val seconds = presentationTimeUs / MICROS_PER_SECOND
    val output = IntArray(1)
    GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, output, 0)
    try {
      steps.forEach { linked ->
        val target = targets[linked.step.name]
        if (target != null) {
          GlUtil.focusFramebufferUsingCurrentContext(target.framebuffer, target.width, target.height)
        } else {
          GlUtil.focusFramebufferUsingCurrentContext(output[0], width, height)
        }
        linked.draw(seconds, width, height) { source ->
          if (source == FilmShader.INPUT) inputTexId else targets.getValue(source).texture
        }
      }
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException.from(e, presentationTimeUs)
    }
  }

  override fun release() {
    super.release()
    try {
      releaseTargets()
      steps.forEach { it.program.delete() }
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException(e)
    }
  }

  private fun releaseTargets() {
    targets.values.forEach { it.release() }
    targets = emptyMap()
  }

  private companion object {
    const val TEXTURE_POOL_CAPACITY = 1
    const val MICROS_PER_SECOND = 1_000_000.0
  }
}

/**
 * One [FilmStep] linked to a program.
 */
private class LinkedStep(
  val step: FilmStep,
) {
  val program =
    GlProgram(FULL_FRAME_VERTEX_SHADER, step.fragment).apply {
      setBufferAttribute(
        "aFramePosition",
        GlUtil.getNormalizedCoordinateBounds(),
        GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
      )
    }

  fun draw(
    seconds: Double,
    width: Int,
    height: Int,
    textureOf: (String) -> Int,
  ) {
    program.use()
    step.reads.entries.forEachIndexed { unit, (sampler, source) ->
      program.setSamplerTexIdUniform(sampler, textureOf(source), unit)
    }
    // A GL texture runs from the bottom left. GlProgram refuses a name the shader does not hold, so only a step that
    // reads it is told.
    if (FilmShader.FLIP_V in step.fragment) program.setFloatUniform(FilmShader.FLIP_V, 1f)
    val (arrays, values) =
      step.uniformsAt(seconds, width, height).entries.partition { (name, _) -> name.endsWith(ARRAY_SUFFIX) }
    values.forEach { (name, value) -> program.setFloatsUniform(name, value) }
    program.bindAttributesAndUniforms()
    // GlProgram holds a uniform to 16 floats and binds one element of an array, so an array goes straight to GL.
    arrays.forEach { (name, value) ->
      GLES20.glUniform1fv(program.getUniformLocation(name), value.size, value, 0)
    }
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, QUAD_VERTICES)
    GlUtil.checkGlError()
  }
}

/**
 * A texture and framebuffer one step draws into.
 */
private class StepTarget(
  val texture: Int,
  val framebuffer: Int,
  val width: Int,
  val height: Int,
) {
  fun release() {
    GlUtil.deleteFbo(framebuffer)
    GlUtil.deleteTexture(texture)
  }

  companion object {
    fun of(
      width: Int,
      height: Int,
      format: Int,
    ): StepTarget {
      val texture = IntArray(1)
      GLES20.glGenTextures(1, texture, 0)
      GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
      GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, format, width, height)
      GlUtil.checkGlError()
      return StepTarget(texture[0], GlUtil.createFboForTexture(texture[0]), width, height)
    }
  }
}

/**
 * The format a step's target takes on a device with these [extensions].
 *
 * Half float holds a fraction of a code between the steps, and its step of 2^-11 from a half to one costs up to 0.12 of
 * a code. Rendering to it takes either extension. OpenGL ES 3 alone only renders to RGBA8, which is that device's limit
 * and rounds the frame and the glow by under a code.
 */
internal fun stepTargetFormat(extensions: String): Int {
  val rendersHalfFloat = "EXT_color_buffer_float" in extensions || "EXT_color_buffer_half_float" in extensions
  return if (rendersHalfFloat) GLES30.GL_RGBA16F else GLES30.GL_RGBA8
}

private const val QUAD_VERTICES = 4
private const val ARRAY_SUFFIX = "[0]"

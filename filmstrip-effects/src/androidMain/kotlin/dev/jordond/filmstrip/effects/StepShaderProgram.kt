package dev.jordond.filmstrip.effects

import android.opengl.GLES20
import android.opengl.GLES30
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size as FrameSize

/**
 * One draw of a [StepShaderProgram].
 *
 * The fragment shader is GLSL ES 3.00 and reads at the `vUv` varying, which runs from the bottom left of the frame.
 *
 * @property name What a later step calls this step's output in its own [reads].
 * @property fragment The fragment shader source.
 * @property reads Each sampler this step reads, to the [name] of an earlier step or [StepShaderProgram.INPUT].
 * @property targetAt The size this step draws at, for a frame of a width and height in pixels.
 * @property uniformsAt The uniforms for a frame at a presentation time in seconds and a width and height in pixels, by
 * name. A name ending in `[0]` is a float array, and any other is a float or a vec by its length. A name the linked
 * shader does not hold is skipped, so a step can be handed uniforms only some of its siblings read.
 */
@InternalFilmstripApi
public class GlStep(
  public val name: String,
  public val fragment: String,
  public val reads: Map<String, String>,
  public val targetAt: (width: Int, height: Int) -> FrameSize,
  public val uniformsAt: (seconds: Double, width: Int, height: Int) -> Map<String, FloatArray>,
)

/**
 * Runs [GlStep]s over a frame, each step before the last on a target of its own and the last on the output media3 has
 * focused.
 *
 * A frame's uniforms come from its presentation time and size alone, so a seek lands on what an export draws there.
 * Each target is in [targetFormat], which [stepTargetFormat] picks for a device.
 *
 * @param steps The steps in the order they run. Building the program links each one.
 * @param targetFormat The sized internal format every step but the last draws into.
 * @param useHighPrecisionColorComponents Whether the output media3 hands this program holds half floats, which a graph
 * in HDR needs.
 * @throws VideoFrameProcessingException when a step does not link.
 */
@InternalFilmstripApi
public class StepShaderProgram(
  steps: List<GlStep>,
  private val targetFormat: Int,
  useHighPrecisionColorComponents: Boolean,
) : BaseGlShaderProgram(useHighPrecisionColorComponents, TEXTURE_POOL_CAPACITY) {
  private val linked: List<LinkedStep>
  private var targets: Map<String, StepTarget> = emptyMap()
  private var width = 1
  private var height = 1

  init {
    try {
      linked = steps.map(::LinkedStep)
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
        linked.dropLast(1).associate { step ->
          val size = step.step.targetAt(inputWidth, inputHeight)
          step.step.name to StepTarget.of(size.width, size.height, targetFormat)
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
      linked.forEach { step ->
        val target = targets[step.step.name]
        if (target != null) {
          GlUtil.focusFramebufferUsingCurrentContext(target.framebuffer, target.width, target.height)
        } else {
          GlUtil.focusFramebufferUsingCurrentContext(output[0], width, height)
        }
        step.draw(seconds, width, height) { source ->
          if (source == INPUT) inputTexId else targets.getValue(source).texture
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
      linked.forEach { it.program.delete() }
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException(e)
    }
  }

  private fun releaseTargets() {
    targets.values.forEach { it.release() }
    targets = emptyMap()
  }

  /**
   * Holds the name a [GlStep] reads the frame entering the program by.
   */
  public companion object {
    /**
     * What a [GlStep] names the frame the whole program starts from in [GlStep.reads].
     */
    public const val INPUT: String = "input"

    private const val TEXTURE_POOL_CAPACITY = 1
    private const val MICROS_PER_SECOND = 1_000_000.0
  }
}

/**
 * The format a step's target takes on a device with these [extensions].
 *
 * Half float holds a fraction of a code between the steps, and its step of 2^-11 from a half to one costs up to 0.12 of
 * a code. Rendering to it takes either extension. OpenGL ES 3 alone only renders to RGBA8, which is that device's limit
 * and rounds each step by under a code.
 */
@InternalFilmstripApi
public fun stepTargetFormat(extensions: String): Int {
  val rendersHalfFloat = "EXT_color_buffer_float" in extensions || "EXT_color_buffer_half_float" in extensions
  return if (rendersHalfFloat) GLES30.GL_RGBA16F else GLES30.GL_RGBA8
}

private class LinkedStep(
  val step: GlStep,
) {
  val program =
    GlProgram(VERTEX_SHADER, step.fragment).apply {
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
    // GlProgram refuses a name the linked shader does not hold, and a compiler drops one the shader never reads.
    val held = step.uniformsAt(seconds, width, height).filterKeys { program.getUniformLocation(it) != -1 }
    val (arrays, values) = held.entries.partition { (name, _) -> name.endsWith(ARRAY_SUFFIX) }
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
      // A later step reads past the edge as the edge, and between texels as their linear mix.
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
      GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
      GlUtil.checkGlError()
      return StepTarget(texture[0], GlUtil.createFboForTexture(texture[0]), width, height)
    }
  }
}

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

private const val QUAD_VERTICES = 4
private const val ARRAY_SUFFIX = "[0]"

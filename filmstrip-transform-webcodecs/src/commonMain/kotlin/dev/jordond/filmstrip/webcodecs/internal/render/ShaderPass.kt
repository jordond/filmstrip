@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal.render

import dev.jordond.filmstrip.effect.WebGlPass
import dev.jordond.filmstrip.effect.WebGlStep
import dev.jordond.filmstrip.webcodecs.internal.BrowserExportFailure
import dev.jordond.filmstrip.webcodecs.internal.interop.WebGl2
import dev.jordond.filmstrip.webcodecs.internal.interop.toFloat32Array
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.time.Duration

/**
 * Runs each [WebGlPass] that carries steps over a whole frame, with each step's fragment source linked once per
 * [WebGlPass.programKey] and step name. One key handed two sources for a step is refused, since the second would draw
 * through the first.
 *
 * A frame is read from one texture and written to another target with blending off, so alpha comes through as the
 * shader writes it. Each draw hands the output viewport and the blending back, so the compositor's own draws find the
 * state they left, and the compositor puts its own program back after a run. The targets between a pass's steps come
 * from a pool of [hdr] frames, held until that pass ends. Each table a step reads comes from [tables].
 */
internal class ShaderPass(
  private val gl: WebGl2,
  private val buffer: JsAny,
  private val outputWidth: Int,
  private val outputHeight: Int,
  hdr: Boolean,
  private val tables: StepTables = StepTables(gl),
) {
  private val programs = mutableMapOf<String, LinkedStep>()
  private val pool = StepFramePool(gl, hdr)

  /**
   * The texture of every table held right now.
   */
  val tableTextures: List<JsAny> get() = tables.textures

  /**
   * Runs [passes] over the frame [frames] holds, one after the other, and answers the texture the last one wrote.
   */
  fun run(
    passes: List<WebGlPass>,
    frames: PassFrames,
    width: Int,
    height: Int,
    compositionTime: Duration,
  ): JsAny {
    passes.forEach { pass ->
      val input = frames.read(width, height).texture
      runSteps(pass, input, frames.write(width, height).framebuffer, width, height, compositionTime)
      frames.swap()
    }
    return frames.read(width, height).texture
  }

  /**
   * Runs [passes] over the frame [frames] holds, one after the other, with the last one writing [target].
   */
  fun runOnto(
    passes: List<WebGlPass>,
    frames: PassFrames,
    target: JsAny?,
    width: Int,
    height: Int,
    compositionTime: Duration,
  ) {
    run(passes.dropLast(1), frames, width, height, compositionTime)
    runSteps(passes.last(), frames.read(width, height).texture, target, width, height, compositionTime)
  }

  /**
   * Deletes every program this pass has linked and every frame in its pool.
   */
  fun release() {
    programs.values.forEach { gl.deleteProgram(it.program) }
    programs.clear()
    pool.release()
    tables.forget()
  }

  /**
   * Deletes every table uploaded so far, for a plan that replaced this one.
   */
  fun forgetTables() {
    tables.forget()
  }

  private fun runSteps(
    pass: WebGlPass,
    input: JsAny,
    target: JsAny?,
    width: Int,
    height: Int,
    compositionTime: Duration,
  ) {
    val outputs = mutableMapOf<String, PassFrame>()
    pass.steps.forEachIndexed { index, step ->
      val size = step.targetAt(width, height)
      val textures =
        step.reads.mapValues { (_, source) ->
          if (source == WebGlStep.INPUT) input else outputs.getValue(source).texture
        } + step.textures.mapValues { (_, table) -> tables.textureOf("${pass.programKey}/${step.name}", table) }
      val frame = StepFrame(pass, step, textures, width, height, compositionTime)
      if (index < pass.steps.lastIndex) {
        val output = pool.acquire(size.width, size.height)
        outputs[step.name] = output
        draw(frame, output.framebuffer, size.width, size.height)
      } else {
        if (size.width != width || size.height != height) {
          throw BrowserExportFailure(
            "Step ${step.name} of ${pass.programKey} is the last and draws at $size, not the ${width}x$height frame.",
          )
        }
        draw(frame, target, width, height)
      }
    }
    outputs.values.forEach(pool::give)
    tables.evictPastBound()
  }

  private fun draw(
    frame: StepFrame,
    target: JsAny?,
    targetWidth: Int,
    targetHeight: Int,
  ) {
    val linked = linkedFor(frame.pass, frame.step)
    gl.useProgram(linked.program)
    frame.step
      .uniformsAt(frame.compositionTime, frame.width, frame.height)
      .forEach { (name, value) -> upload(linked, frame.pass, name, value) }
    gl.bindFramebuffer(GL_FRAMEBUFFER, target)
    gl.viewport(0, 0, targetWidth, targetHeight)
    gl.disable(GL_BLEND)
    frame.textures.forEach { (sampler, texture) ->
      gl.activeTexture(GL_TEXTURE0 + linked.units.getValue(sampler))
      gl.bindTexture(GL_TEXTURE_2D, texture)
    }
    gl.drawArrays(GL_TRIANGLE_STRIP, 0, QUAD_VERTICES)
    gl.activeTexture(GL_TEXTURE0)
    gl.enable(GL_BLEND)
    gl.viewport(0, 0, outputWidth, outputHeight)
  }

  private fun linkedFor(
    pass: WebGlPass,
    step: WebGlStep,
  ): LinkedStep {
    val key = "${pass.programKey}/${step.name}"
    val linked =
      programs.getOrPut(key) {
        val program = linkedProgram(gl, PASS_VERTEX_SHADER, step.fragmentSource)
        gl.useProgram(program)
        gl.bindBuffer(GL_ARRAY_BUFFER, buffer)
        bindQuad(gl, program)
        val units =
          (step.reads.keys + step.textures.keys)
            .withIndex()
            .associate { (unit, sampler) -> sampler to unit }
        units.forEach { (sampler, unit) -> gl.uniform1i(gl.getUniformLocation(program, sampler), unit) }
        LinkedStep(program, step.fragmentSource, units)
      }
    if (linked.source != step.fragmentSource) {
      throw BrowserExportFailure("Program $key was handed two different fragment sources.")
    }
    return linked
  }

  private fun upload(
    linked: LinkedStep,
    pass: WebGlPass,
    name: String,
    value: FloatArray,
  ) {
    val at = linked.locations.getOrPut(name) { gl.getUniformLocation(linked.program, name) }
    if (name.endsWith(ARRAY_SUFFIX)) {
      gl.uniform1fv(at, value.toFloat32Array())
      return
    }
    when (value.size) {
      1 -> {
        gl.uniform1f(at, value[0])
      }
      2 -> {
        gl.uniform2f(at, value[0], value[1])
      }
      3 -> {
        gl.uniform3f(at, value[0], value[1], value[2])
      }
      4 -> {
        gl.uniform4f(at, value[0], value[1], value[2], value[3])
      }
      MAT3_FLOATS -> {
        gl.uniformMatrix3fv(at, false, value.toFloat32Array())
      }
      MAT4_FLOATS -> {
        gl.uniformMatrix4fv(at, false, value.toFloat32Array())
      }
      else -> {
        throw BrowserExportFailure(
          "Program ${pass.programKey} set uniform $name to ${value.size} floats, which is no GLSL float, vec or mat.",
        )
      }
    }
  }
}

/**
 * A pair of frames a chain of passes reads and writes in turn, which follow the size asked for and are allocated the
 * first time each is asked for.
 *
 * [hdr] allocates them at half float, which is the light a kept grade holds.
 */
internal class PassFrames(
  private val gl: WebGl2,
  private val hdr: Boolean,
) {
  private val frames = arrayOfNulls<PassFrame>(2)
  private var reading = 0

  /**
   * The frame the next pass reads, which the composite draws into first.
   */
  fun read(
    width: Int,
    height: Int,
  ): PassFrame = frameAt(reading, width, height)

  /**
   * The frame the next pass writes.
   */
  fun write(
    width: Int,
    height: Int,
  ): PassFrame = frameAt(1 - reading, width, height)

  /**
   * Makes the frame just written the one the next pass reads.
   */
  fun swap() {
    reading = 1 - reading
  }

  /**
   * Deletes both frames.
   */
  fun release() {
    frames.forEach { frame ->
      if (frame != null) {
        gl.deleteFramebuffer(frame.framebuffer)
        gl.deleteTexture(frame.texture)
      }
    }
  }

  private fun frameAt(
    index: Int,
    width: Int,
    height: Int,
  ): PassFrame {
    val held = frames[index]
    if (held != null && held.width == width && held.height == height) return held
    val frame =
      if (held == null) {
        val texture = createFramebufferTexture(gl, width, height, hdr)
        PassFrame(texture, createComplete(gl, texture), width, height)
      } else {
        allocate(gl, held.texture, width, height, hdr)
        PassFrame(held.texture, held.framebuffer, width, height)
      }
    frames[index] = frame
    return frame
  }
}

/**
 * One target of a [PassFrames], whose [texture] is +Y-up like every other texture here.
 */
internal class PassFrame(
  val texture: JsAny,
  val framebuffer: JsAny,
  val width: Int,
  val height: Int,
)

/**
 * The targets a pass's steps write ahead of the last one, reused across steps and frames.
 *
 * A frame handed back is kept and a later ask for any size takes it, resized if it has to be, so the pool never holds
 * more frames than the longest chain.
 */
private class StepFramePool(
  private val gl: WebGl2,
  private val hdr: Boolean,
) {
  private val idle = mutableListOf<PassFrame>()

  fun acquire(
    width: Int,
    height: Int,
  ): PassFrame {
    val fits = idle.firstOrNull { it.width == width && it.height == height }
    if (fits != null) {
      idle.remove(fits)
      return fits
    }
    val held = idle.removeLastOrNull()
    if (held != null) {
      allocate(gl, held.texture, width, height, hdr)
      return PassFrame(held.texture, held.framebuffer, width, height)
    }
    val texture = createFramebufferTexture(gl, width, height, hdr)
    return PassFrame(texture, createComplete(gl, texture), width, height)
  }

  fun give(frame: PassFrame) {
    idle += frame
  }

  fun release() {
    idle.forEach { frame ->
      gl.deleteFramebuffer(frame.framebuffer)
      gl.deleteTexture(frame.texture)
    }
    idle.clear()
  }
}

private class StepFrame(
  val pass: WebGlPass,
  val step: WebGlStep,
  val textures: Map<String, JsAny>,
  val width: Int,
  val height: Int,
  val compositionTime: Duration,
)

private class LinkedStep(
  val program: JsAny,
  val source: String,
  val units: Map<String, Int>,
) {
  val locations = mutableMapOf<String, JsAny?>()
}

private val PASS_VERTEX_SHADER =
  """
  #version 300 es
  in vec2 aPosition;
  out vec2 vUv;
  void main() {
    vUv = aPosition * 0.5 + 0.5;
    gl_Position = vec4(aPosition, 0.0, 1.0);
  }
  """.trimIndent()

private const val ARRAY_SUFFIX = "[0]"
private const val MAT3_FLOATS = 9
private const val MAT4_FLOATS = 16

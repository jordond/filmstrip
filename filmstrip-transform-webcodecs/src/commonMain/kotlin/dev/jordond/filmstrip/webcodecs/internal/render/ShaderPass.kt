@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal.render

import dev.jordond.filmstrip.effect.WebGlPass
import dev.jordond.filmstrip.webcodecs.internal.BrowserExportFailure
import dev.jordond.filmstrip.webcodecs.internal.interop.WebGl2
import dev.jordond.filmstrip.webcodecs.internal.interop.toFloat32Array
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.time.Duration

/**
 * Runs each [WebGlPass] that carries its own shader over a whole frame, with its fragment source linked once per
 * [WebGlPass.programKey]. One key handed two sources is refused, since the second would draw through the first.
 *
 * A frame is read from one texture and written to another target with blending off, so alpha comes through as the
 * shader writes it. Each draw hands the output viewport and the blending back, so the compositor's own draws find the
 * state they left, and the compositor puts its own program back after a run.
 */
internal class ShaderPass(
  private val gl: WebGl2,
  private val buffer: JsAny,
  private val outputWidth: Int,
  private val outputHeight: Int,
) {
  private val programs = mutableMapOf<String, LinkedShader>()

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
      draw(pass, input, frames.write(width, height).framebuffer, width, height, compositionTime)
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
    draw(passes.last(), frames.read(width, height).texture, target, width, height, compositionTime)
  }

  /**
   * Deletes every program this pass has linked.
   */
  fun release() {
    programs.values.forEach { gl.deleteProgram(it.program) }
    programs.clear()
  }

  private fun draw(
    pass: WebGlPass,
    input: JsAny,
    target: JsAny?,
    width: Int,
    height: Int,
    compositionTime: Duration,
  ) {
    val shader = checkNotNull(pass.shader) { "Program ${pass.programKey} has no fragment source to run." }
    val linked =
      programs.getOrPut(pass.programKey) {
        val program = linkedProgram(gl, PASS_VERTEX_SHADER, shader.fragmentSource)
        gl.useProgram(program)
        gl.bindBuffer(GL_ARRAY_BUFFER, buffer)
        bindQuad(gl, program)
        gl.uniform1i(gl.getUniformLocation(program, shader.textureUniform), 0)
        LinkedShader(program, shader.fragmentSource)
      }
    if (linked.source != shader.fragmentSource) {
      throw BrowserExportFailure("Program ${pass.programKey} was handed two different fragment sources.")
    }
    gl.useProgram(linked.program)
    shader.uniformsAt(compositionTime, width, height).forEach { (name, value) -> upload(linked, pass, name, value) }
    gl.bindFramebuffer(GL_FRAMEBUFFER, target)
    gl.viewport(0, 0, width, height)
    gl.disable(GL_BLEND)
    gl.activeTexture(GL_TEXTURE0)
    gl.bindTexture(GL_TEXTURE_2D, input)
    gl.drawArrays(GL_TRIANGLE_STRIP, 0, QUAD_VERTICES)
    gl.enable(GL_BLEND)
    gl.viewport(0, 0, outputWidth, outputHeight)
  }

  private fun upload(
    linked: LinkedShader,
    pass: WebGlPass,
    name: String,
    value: FloatArray,
  ) {
    val at = linked.locations.getOrPut(name) { gl.getUniformLocation(linked.program, name) }
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

private class LinkedShader(
  val program: JsAny,
  val source: String,
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

private const val MAT3_FLOATS = 9
private const val MAT4_FLOATS = 16

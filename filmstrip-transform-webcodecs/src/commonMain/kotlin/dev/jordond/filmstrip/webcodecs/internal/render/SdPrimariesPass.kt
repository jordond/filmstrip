@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal.render

import dev.jordond.filmstrip.color.BT709_ALPHA
import dev.jordond.filmstrip.color.BT709_BETA
import dev.jordond.filmstrip.color.BT709_SLOPE
import dev.jordond.filmstrip.color.EBU_PRIMARIES
import dev.jordond.filmstrip.color.POWER_SEGMENT_EXPONENT
import dev.jordond.filmstrip.color.SMPTE_C_PRIMARIES
import dev.jordond.filmstrip.color.sdPrimariesToBt709
import dev.jordond.filmstrip.webcodecs.internal.interop.WebGl2
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny

/**
 * The stage that moves an SD clip's frame onto BT.709's primaries, run on each such frame before the composite.
 *
 * With its colour conversion off, `texImage2D` decodes a frame with its real matrix and leaves its primaries alone, so
 * the uploaded texture holds the source's signal on the source's primaries. This pass draws it into an eight-bit
 * texture on BT.709's. A clip of other SD primaries gets a program of its own, built the first time one is drawn. Only
 * an SDR compositor holds this stage, since the shared planner refuses to keep an HDR grade beside an SD clip.
 *
 * Each frame puts the composite program back once it is done, along with the output viewport, so the compositor's own
 * draws find the state they left.
 */
internal class SdPrimariesPass(
  private val outputWidth: Int,
  private val outputHeight: Int,
  private val buffer: JsAny,
  private val target: JsAny,
  private val targetFbo: JsAny,
) {
  private val programs = mutableMapOf<Int, JsAny>()
  private var targetWidth = outputWidth
  private var targetHeight = outputHeight

  /**
   * Draws [uploaded], a [width] by [height] frame on color primaries code point [primaries], onto BT.709's primaries.
   *
   * @return The texture the frame was drawn into.
   */
  fun moved(
    gl: WebGl2,
    uploaded: JsAny,
    width: Int,
    height: Int,
    primaries: Int,
    composite: JsAny,
  ): JsAny {
    gl.activeTexture(GL_TEXTURE0)
    if (width != targetWidth || height != targetHeight) {
      allocate(gl, target, width, height, hdr = false)
      targetWidth = width
      targetHeight = height
    }
    gl.useProgram(programs.getOrPut(primaries) { program(gl, primaries) })
    gl.bindTexture(GL_TEXTURE_2D, uploaded)
    gl.bindFramebuffer(GL_FRAMEBUFFER, targetFbo)
    gl.viewport(0, 0, width, height)
    gl.disable(GL_BLEND)
    gl.drawArrays(GL_TRIANGLE_STRIP, 0, QUAD_VERTICES)
    gl.enable(GL_BLEND)
    gl.viewport(0, 0, outputWidth, outputHeight)
    gl.useProgram(composite)
    return target
  }

  /**
   * Deletes every program, texture and framebuffer this pass holds.
   */
  fun release(gl: WebGl2) {
    programs.values.forEach { gl.deleteProgram(it) }
    gl.deleteFramebuffer(targetFbo)
    gl.deleteTexture(target)
  }

  private fun program(
    gl: WebGl2,
    primaries: Int,
  ): JsAny {
    val program = linkedProgram(gl, FULLSCREEN_VERTEX_SHADER, sdPrimariesShader(primaries))
    gl.useProgram(program)
    gl.bindBuffer(GL_ARRAY_BUFFER, buffer)
    bindQuad(gl, program)
    gl.uniform1i(gl.getUniformLocation(program, "uFrame"), 0)
    return program
  }
}

/**
 * An SD primaries stage for a [width] by [height] output, drawing its quad from [buffer]. The target starts at the
 * output's size and follows the decoded frame from there.
 */
internal fun buildSdPrimariesPass(
  gl: WebGl2,
  width: Int,
  height: Int,
  buffer: JsAny,
): SdPrimariesPass {
  val target = createFramebufferTexture(gl, width, height, hdr = false)
  val pass =
    SdPrimariesPass(
      outputWidth = width,
      outputHeight = height,
      buffer = buffer,
      target = target,
      targetFbo = createComplete(gl, target),
    )
  gl.bindFramebuffer(GL_FRAMEBUFFER, null)
  return pass
}

/**
 * The color primaries code point of a frame whose `VideoColorSpace` names [primaries], when those are SD, or null.
 */
internal fun sdPrimariesCodePoint(primaries: String?): Int? =
  when (primaries) {
    "smpte170m" -> SMPTE_C_PRIMARIES
    "bt470bg" -> EBU_PRIMARIES
    else -> null
  }

/**
 * The fragment shader moving a frame off color primaries code point [primaries], the steps of `sdSourceDisplayed` with
 * every figure read off the shared BT.709 curve and the shared matrix.
 */
internal fun sdPrimariesShader(primaries: Int): String =
  """
  #version 300 es
  precision highp float;
  uniform sampler2D uFrame;
  in vec2 vTexCoord;
  out vec4 outColor;
  const mat3 PRIMARIES = ${sdPrimariesToBt709(primaries).glslColumnMajor()};
  vec3 bt709Light(vec3 signal) {
    vec3 power = pow(
      (signal + ${BT709_ALPHA.glsl()} - 1.0) / ${BT709_ALPHA.glsl()},
      vec3(1.0 / ${POWER_SEGMENT_EXPONENT.glsl()}));
    return mix(signal / ${BT709_SLOPE.glsl()}, power, step(${BT709_SLOPE.glsl()} * ${BT709_BETA.glsl()}, signal));
  }
  vec3 bt709Signal(vec3 light) {
    vec3 power = ${BT709_ALPHA.glsl()} * pow(light, vec3(${POWER_SEGMENT_EXPONENT.glsl()}))
      - (${BT709_ALPHA.glsl()} - 1.0);
    return mix(light * ${BT709_SLOPE.glsl()}, power, step(${BT709_BETA.glsl()}, light));
  }
  void main() {
    vec3 light = bt709Light(clamp(texture(uFrame, vTexCoord).rgb, 0.0, 1.0));
    outColor = vec4(bt709Signal(clamp(PRIMARIES * light, 0.0, 1.0)), 1.0);
  }
  """.trimIndent()

// Core stores a matrix row by row, and a GLSL constructor reads column by column.
private fun DoubleArray.glslColumnMajor(): String =
  "mat3(" +
    (0 until COLUMNS)
      .flatMap { column -> (0 until COLUMNS).map { row -> this[row * COLUMNS + column].glsl() } }
      .joinToString(", ") + ")"

private const val COLUMNS = 3

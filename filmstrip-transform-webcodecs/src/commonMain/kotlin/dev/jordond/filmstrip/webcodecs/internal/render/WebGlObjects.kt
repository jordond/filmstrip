@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal.render

import dev.jordond.filmstrip.webcodecs.internal.BrowserExportFailure
import dev.jordond.filmstrip.webcodecs.internal.interop.WebGl2
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny

/**
 * Compiles [vertexSource] and [fragmentSource] and links them into one program on [gl].
 *
 * The two shaders are deleted once the program holds them.
 *
 * @throws BrowserExportFailure when the context refuses a shader or a program, or either source fails to compile or
 * link, with the log the context gave.
 */
internal fun linkedProgram(
  gl: WebGl2,
  vertexSource: String,
  fragmentSource: String,
): JsAny {
  val vertex = compiledShader(gl, GL_VERTEX_SHADER, vertexSource)
  val fragment = compiledShader(gl, GL_FRAGMENT_SHADER, fragmentSource)
  val program = gl.createProgram() ?: throw BrowserExportFailure(NO_PROGRAM)
  gl.attachShader(program, vertex)
  gl.attachShader(program, fragment)
  gl.linkProgram(program)
  gl.deleteShader(vertex)
  gl.deleteShader(fragment)
  if (!gl.getProgramParameter(program, GL_LINK_STATUS)) {
    throw BrowserExportFailure("The compositor's program did not link: ${gl.getProgramInfoLog(program)}")
  }
  return program
}

private fun compiledShader(
  gl: WebGl2,
  type: Int,
  source: String,
): JsAny {
  val shader = gl.createShader(type) ?: throw BrowserExportFailure(NO_SHADER)
  gl.shaderSource(shader, source)
  gl.compileShader(shader)
  if (!gl.getShaderParameter(shader, GL_COMPILE_STATUS)) {
    throw BrowserExportFailure("The compositor's shader did not compile: ${gl.getShaderInfoLog(shader)}")
  }
  return shader
}

/**
 * Source over onto the fill's opaque clear, which is what puts the fill's color in a letterbox bar. The alpha factors
 * keep the result opaque, which the context's premultiplied alpha needs and which matches an encoder that discards
 * alpha anyway.
 *
 * A pass that blends another way sets its own function for its draws and puts this one back after them.
 */
internal fun WebGl2.blendOntoFill() {
  blendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
}

/**
 * An empty texture sized [width] by [height], for a framebuffer's color attachment.
 */
internal fun createFramebufferTexture(
  gl: WebGl2,
  width: Int,
  height: Int,
  hdr: Boolean,
): JsAny = createTexture(gl, GL_LINEAR).also { allocate(gl, it, width, height, hdr) }

internal fun createTexture(
  gl: WebGl2,
  filter: Int,
): JsAny {
  val texture = gl.createTexture() ?: throw BrowserExportFailure(NO_TEXTURE)
  gl.bindTexture(GL_TEXTURE_2D, texture)
  gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter)
  gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter)
  gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
  gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
  return texture
}

/**
 * Sizes the bound texture with no image behind it. A grade composites at half float, which
 * carries the headroom above reference white a kept highlight needs.
 */
internal fun allocate(
  gl: WebGl2,
  texture: JsAny,
  width: Int,
  height: Int,
  hdr: Boolean,
) {
  gl.bindTexture(GL_TEXTURE_2D, texture)
  if (hdr) {
    gl.texImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, width, height, 0, GL_RGBA, GL_HALF_FLOAT, null)
  } else {
    gl.texImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
  }
}

/**
 * A framebuffer with [colorTexture] attached, refused rather than handed back incomplete.
 */
internal fun createComplete(
  gl: WebGl2,
  colorTexture: JsAny,
): JsAny {
  val framebuffer = gl.createFramebuffer() ?: throw BrowserExportFailure(NO_FRAMEBUFFER)
  gl.bindFramebuffer(GL_FRAMEBUFFER, framebuffer)
  gl.framebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTexture, 0)
  if (gl.checkFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
    throw BrowserExportFailure(NO_FRAMEBUFFER)
  }
  return framebuffer
}

internal fun bindQuad(
  gl: WebGl2,
  program: JsAny,
) {
  val position = gl.getAttribLocation(program, "aPosition")
  gl.enableVertexAttribArray(position)
  gl.vertexAttribPointer(position, 2, GL_FLOAT, false, 0, 0)
}

private const val NO_TEXTURE = "WebGL2 refused to create a texture."
private const val NO_FRAMEBUFFER = "WebGL2 refused to give a render pass a complete framebuffer."

private const val NO_PROGRAM = "WebGL2 refused to create a program."
private const val NO_SHADER = "WebGL2 refused to create a shader."

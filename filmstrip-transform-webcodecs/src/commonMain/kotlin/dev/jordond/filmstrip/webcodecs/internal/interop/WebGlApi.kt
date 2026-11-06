@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal.interop

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny

/**
 * The render target. Nothing is added to the document, so the composite never touches layout.
 */
internal external class OffscreenCanvas(
  width: Int,
  height: Int,
) : JsAny {
  fun getContext(
    contextId: String,
    options: JsAny,
  ): WebGl2?
}

/**
 * The GL entry points the compositor uses. The call sequence lives in the render package. This
 * is only the binding.
 */
internal external interface WebGl2 : JsAny {
  fun createShader(type: Int): JsAny?

  fun shaderSource(
    shader: JsAny,
    source: String,
  )

  fun compileShader(shader: JsAny)

  fun getShaderParameter(
    shader: JsAny,
    pname: Int,
  ): Boolean

  fun getShaderInfoLog(shader: JsAny): String?

  fun deleteShader(shader: JsAny?)

  fun createProgram(): JsAny?

  fun attachShader(
    program: JsAny,
    shader: JsAny,
  )

  fun linkProgram(program: JsAny)

  fun getProgramParameter(
    program: JsAny,
    pname: Int,
  ): Boolean

  fun getProgramInfoLog(program: JsAny): String?

  fun deleteProgram(program: JsAny?)

  fun useProgram(program: JsAny?)

  fun getAttribLocation(
    program: JsAny,
    name: String,
  ): Int

  fun getUniformLocation(
    program: JsAny,
    name: String,
  ): JsAny?

  fun createBuffer(): JsAny?

  fun bindBuffer(
    target: Int,
    buffer: JsAny?,
  )

  fun bufferData(
    target: Int,
    data: Float32Array,
    usage: Int,
  )

  fun deleteBuffer(buffer: JsAny?)

  fun enableVertexAttribArray(index: Int)

  fun vertexAttribPointer(
    index: Int,
    size: Int,
    type: Int,
    normalized: Boolean,
    stride: Int,
    offset: Int,
  )

  fun createTexture(): JsAny?

  /**
   * Which texture unit the next `bindTexture` names. The unpack pass reads three planes at once, so
   * it binds one texture per unit rather than one at a time.
   */
  fun activeTexture(texture: Int)

  fun bindTexture(
    target: Int,
    texture: JsAny?,
  )

  fun texParameteri(
    target: Int,
    pname: Int,
    param: Int,
  )

  fun texImage2D(
    target: Int,
    level: Int,
    internalformat: Int,
    format: Int,
    type: Int,
    source: JsAny,
  )

  /**
   * The allocating overload, for a texture with no source image, sized outright instead of
   * uploaded into. This is what backs a framebuffer's color attachment.
   */
  fun texImage2D(
    target: Int,
    level: Int,
    internalformat: Int,
    width: Int,
    height: Int,
    border: Int,
    format: Int,
    type: Int,
    pixels: JsAny?,
  )

  fun deleteTexture(texture: JsAny?)

  fun isTexture(texture: JsAny?): Boolean

  fun createFramebuffer(): JsAny?

  fun bindFramebuffer(
    target: Int,
    framebuffer: JsAny?,
  )

  fun framebufferTexture2D(
    target: Int,
    attachment: Int,
    textarget: Int,
    texture: JsAny?,
    level: Int,
  )

  fun checkFramebufferStatus(target: Int): Int

  fun deleteFramebuffer(framebuffer: JsAny?)

  fun pixelStorei(
    pname: Int,
    param: Int,
  )

  fun viewport(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
  )

  fun clearColor(
    red: Float,
    green: Float,
    blue: Float,
    alpha: Float,
  )

  fun clear(mask: Int)

  fun enable(cap: Int)

  fun disable(cap: Int)

  fun blendFuncSeparate(
    srcRgb: Int,
    dstRgb: Int,
    srcAlpha: Int,
    dstAlpha: Int,
  )

  fun uniformMatrix3fv(
    location: JsAny?,
    transpose: Boolean,
    value: Float32Array,
  )

  fun uniformMatrix4fv(
    location: JsAny?,
    transpose: Boolean,
    value: Float32Array,
  )

  fun uniform1f(
    location: JsAny?,
    x: Float,
  )

  fun uniform1i(
    location: JsAny?,
    x: Int,
  )

  fun uniform1fv(
    location: JsAny?,
    value: Float32Array,
  )

  fun uniform2f(
    location: JsAny?,
    x: Float,
    y: Float,
  )

  fun uniform3f(
    location: JsAny?,
    x: Float,
    y: Float,
    z: Float,
  )

  fun uniform4f(
    location: JsAny?,
    x: Float,
    y: Float,
    z: Float,
    w: Float,
  )

  fun drawArrays(
    mode: Int,
    first: Int,
    count: Int,
  )

  /**
   * Reads the bound framebuffer back into [pixels]. Synchronous, and it stalls the GL pipeline
   * until the draw behind it has finished, which is what makes it the pack pass's whole cost.
   *
   * Rows come back bottom-up.
   */
  fun readPixels(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    format: Int,
    type: Int,
    pixels: Uint8Array,
  )

  fun getExtension(name: String): GlExtension?

  /**
   * Reads one of the context's numeric limits, such as `MAX_TEXTURE_SIZE`.
   *
   * Only the limits that come back as a single number are asked for through this binding.
   */
  fun getParameter(pname: Int): Int
}

/**
 * An extension object, handed back by name or null where the context has none.
 *
 * `WEBGL_lose_context` is the only one whose members are called. Everything else is asked for so
 * the answer can be null-checked, which is what a capability probe needs.
 */
internal external interface GlExtension : JsAny {
  fun loseContext()
}

@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal.render

import dev.jordond.filmstrip.color.authoredHdrNits
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.webcodecs.internal.BrowserExportFailure
import dev.jordond.filmstrip.webcodecs.internal.interop.JsOptions
import dev.jordond.filmstrip.webcodecs.internal.interop.OffscreenCanvas
import dev.jordond.filmstrip.webcodecs.internal.interop.toFloat32Array
import kotlin.js.ExperimentalWasmJsInterop

/**
 * Builds the context, programs and passes a [BrowserCompositor] for a [width] by [height] output draws with, filled
 * with [fill] wherever no clip's pixels land.
 */
internal fun compositorOf(
  width: Int,
  height: Int,
  fill: Fill,
  hdrTransfer: HdrTransfer?,
): BrowserCompositor {
  val canvas = OffscreenCanvas(width, height)
  val gl =
    canvas.getContext(
      "webgl2",
      JsOptions()
        .put("alpha", true)
        .put("premultipliedAlpha", true)
        .build(),
    ) ?: throw BrowserExportFailure(NO_CONTEXT)

  if (hdrTransfer != null && gl.getExtension(COLOR_BUFFER_FLOAT) == null) {
    throw BrowserExportFailure(NO_FLOAT_BUFFER)
  }

  val program = linkedProgram(gl, COMPOSITE_VERTEX_SHADER, compositeFragmentShader(hdrTransfer))
  gl.useProgram(program)

  val buffer = gl.createBuffer() ?: throw BrowserExportFailure(NO_BUFFER)
  gl.bindBuffer(GL_ARRAY_BUFFER, buffer)
  gl.bufferData(GL_ARRAY_BUFFER, UNIT_QUAD.toFloat32Array(), GL_STATIC_DRAW)
  bindQuad(gl, program)

  // The SDR path uploads a decoded frame straight into this. A grade uploads three planes of
  // its own instead and has no use for it.
  val texture = if (hdrTransfer == null) createTexture(gl, GL_LINEAR) else null

  gl.viewport(0, 0, width, height)
  val clearColor = fill.clearColor(hdrTransfer)
  gl.clearColor(clearColor[0], clearColor[1], clearColor[2], 1f)
  gl.enable(GL_BLEND)
  gl.blendOntoFill()

  val hdr = hdrTransfer?.let { buildHdrPass(gl, width, height, it, buffer) }
  val blur =
    if (fill is Fill.Blurred) buildBlurPass(gl, width, height, fill, buffer, hdrTransfer) else null
  // Both builders leave their own program bound to upload uniforms. Put the main one back so
  // clip() and draw() find what they expect.
  gl.useProgram(program)

  val compositor =
    BrowserCompositor(
      canvas = canvas,
      gl = gl,
      width = width,
      height = height,
      program = program,
      buffer = buffer,
      texture = texture,
      texMatrix = gl.getUniformLocation(program, "uTexMatrix"),
      quadScale = gl.getUniformLocation(program, "uQuadScale"),
      colorMatrix = gl.getUniformLocation(program, "uColorMatrix"),
      compositionColorMatrix = gl.getUniformLocation(program, "uCompositionColorMatrix"),
      canvasUniforms = ClipCanvasUniforms.of(gl, program),
      blur = blur,
      hdr = hdr,
      hdrTransfer = hdrTransfer,
      clearColor = clearColor,
    )
  if (hdrTransfer != null) {
    gl.useProgram(program)
    uploadGradeUniforms(gl, program, hdrTransfer)
  }
  return compositor
}

/**
 * The clear color [Fill.Solid] paints outright. A blurred fill has no single color of its own, so
 * every pixel it reaches is drawn over before the frame is handed back, and this is only what a
 * stray uncovered one or a slot with no clip at all falls back to.
 *
 * On a kept grade the color is linear light rather than an encoded value, held where
 * [compositorLightOf] puts it, which is the same place an overlay texel of that color lands.
 */
private fun Fill.clearColor(transfer: HdrTransfer?): FloatArray =
  when (this) {
    is Fill.Solid -> {
      if (transfer == null) {
        floatArrayOf(
          ((color shr RED_SHIFT) and BYTE_MASK) / MAX_CHANNEL,
          ((color shr GREEN_SHIFT) and BYTE_MASK) / MAX_CHANNEL,
          (color and BYTE_MASK) / MAX_CHANNEL,
        )
      } else {
        compositorLightOf(authoredHdrNits(color))
      }
    }
    is Fill.Blurred -> {
      floatArrayOf(0f, 0f, 0f)
    }
  }

private val UNIT_QUAD = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)

private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val BYTE_MASK = 0xFF
private const val MAX_CHANNEL = 255f

private const val COLOR_BUFFER_FLOAT = "EXT_color_buffer_float"
private const val NO_CONTEXT = "The page gave out no WebGL2 context, so there is nothing to composite on."
private const val NO_BUFFER = "WebGL2 refused to create a vertex buffer."
private const val NO_FLOAT_BUFFER =
  "This context cannot render into a float framebuffer, so an HDR grade has nowhere to " +
    "composite. EXT_color_buffer_float is missing."

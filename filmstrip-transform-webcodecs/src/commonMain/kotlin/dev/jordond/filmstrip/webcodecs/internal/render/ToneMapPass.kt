@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal.render

import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.webcodecs.internal.interop.VideoSample
import dev.jordond.filmstrip.webcodecs.internal.interop.WebGl2
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny

/**
 * The stage that brings an HDR clip down to SDR on a tone mapped export, run on each frame before the composite.
 *
 * The frame's ten-bit planes are uploaded and run through the shared tone curve of the clip's own transfer into an
 * eight-bit texture, which the SDR composite then samples the way it samples an uploaded SDR frame. A clip of another
 * transfer gets a program of its own, built the first time one is drawn, so PQ, HLG and SDR clips each decode by their
 * own transfer in one export.
 *
 * Each frame puts the composite program back once it is done, along with the output viewport, so the compositor's own
 * draws find the state they left.
 */
internal class ToneMapPass(
  private val outputWidth: Int,
  private val outputHeight: Int,
  private val buffer: JsAny,
  private val planes: TenBitPlanes,
  private val target: JsAny,
  private val targetFbo: JsAny,
) {
  private val programs = mutableMapOf<HdrTransfer, JsAny>()
  private var targetWidth = outputWidth
  private var targetHeight = outputHeight

  /**
   * Uploads the frame's planes and tone maps them from [transfer] into the SDR signal.
   *
   * The rows arrive top-down and the shader flips them, so the texture comes out +Y-up like an uploaded SDR frame.
   *
   * @return The texture the frame was tone mapped into.
   */
  suspend fun toneMap(
    gl: WebGl2,
    frame: VideoSample,
    transfer: HdrTransfer,
    composite: JsAny,
  ): JsAny {
    val width = frame.codedWidth
    val height = frame.codedHeight
    // The allocation binds on the active unit, so it runs before the planes take theirs.
    if (width != targetWidth || height != targetHeight) {
      gl.activeTexture(GL_TEXTURE0)
      allocate(gl, target, width, height, hdr = false)
      targetWidth = width
      targetHeight = height
    }
    planes.upload(gl, frame)
    gl.useProgram(programs.getOrPut(transfer) { planeProgram(gl, toneMapShader(transfer), buffer) })
    gl.bindFramebuffer(GL_FRAMEBUFFER, targetFbo)
    gl.viewport(0, 0, width, height)
    gl.disable(GL_BLEND)
    gl.drawArrays(GL_TRIANGLE_STRIP, 0, QUAD_VERTICES)
    gl.enable(GL_BLEND)
    gl.viewport(0, 0, outputWidth, outputHeight)
    gl.activeTexture(GL_TEXTURE0)
    gl.useProgram(composite)
    return target
  }

  /**
   * Deletes every program, texture and framebuffer this pass holds.
   */
  fun release(gl: WebGl2) {
    planes.release(gl)
    programs.values.forEach { gl.deleteProgram(it) }
    gl.deleteFramebuffer(targetFbo)
    gl.deleteTexture(target)
  }
}

/**
 * A tone map stage for a [width] by [height] output, drawing its quad from [buffer]. The target starts at the output's
 * size and follows the decoded frame from there.
 */
internal fun buildToneMapPass(
  gl: WebGl2,
  width: Int,
  height: Int,
  buffer: JsAny,
): ToneMapPass {
  val target = createFramebufferTexture(gl, width, height, hdr = false)
  val pass =
    ToneMapPass(
      outputWidth = width,
      outputHeight = height,
      buffer = buffer,
      planes = createTenBitPlanes(gl),
      target = target,
      targetFbo = createComplete(gl, target),
    )
  gl.bindFramebuffer(GL_FRAMEBUFFER, null)
  return pass
}

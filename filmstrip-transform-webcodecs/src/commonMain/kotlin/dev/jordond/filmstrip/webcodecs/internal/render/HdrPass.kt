@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal.render

import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.webcodecs.internal.interop.ArrayBuffer
import dev.jordond.filmstrip.webcodecs.internal.interop.JsOptions
import dev.jordond.filmstrip.webcodecs.internal.interop.Uint16Array
import dev.jordond.filmstrip.webcodecs.internal.interop.Uint8Array
import dev.jordond.filmstrip.webcodecs.internal.interop.VideoSample
import dev.jordond.filmstrip.webcodecs.internal.interop.WebGl2
import dev.jordond.filmstrip.webcodecs.internal.interop.jsArrayOf
import kotlinx.coroutines.await
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.get

/**
 * Everything a kept grade adds: the three integer planes a decoded frame is uploaded into, the
 * float textures it composites through, the pack pass that writes the encoder's bytes, and the
 * layout and color tag those bytes are handed over with.
 *
 * Each pass it runs puts the composite program it is handed back once it is done, along with the output viewport, so
 * the compositor's own draws find the state they left.
 *
 * @property outputWidth The output's width, which the pack pass writes and every pass hands the viewport back at.
 * @property outputHeight The same, for height.
 * @property linearWidth The size [linearTexture] is currently allocated at, which follows the
 *   decoded frame rather than the output. A clip of another size reallocates it.
 * @property packed The pack pass's read-back target, reused across frames. mediabunny copies the
 *   bytes when it takes the sample, so a frame in flight never sees the next one's.
 */
internal class HdrPass(
  val outputWidth: Int,
  val outputHeight: Int,
  val planeTextures: List<JsAny>,
  val unpackProgram: JsAny,
  val linearTexture: JsAny,
  val linearFbo: JsAny,
  var linearWidth: Int,
  var linearHeight: Int,
  val outputTexture: JsAny,
  val outputFbo: JsAny,
  val packProgram: JsAny,
  val packTexture: JsAny,
  val packFbo: JsAny,
  val packed: Uint8Array,
  val presentProgram: JsAny,
  val layout: JsArray<JsAny>,
  val colorSpace: JsAny,
) {
  private var planes: ArrayBuffer? = null

  /**
   * A buffer of at least [size] bytes for the decoder to copy into, kept between frames.
   */
  fun bufferFor(size: Int): ArrayBuffer {
    planes?.takeIf { it.byteLength >= size }?.let { return it }
    return ArrayBuffer(size).also { planes = it }
  }

  /**
   * Resizes the linear source to match the frame being decoded. Every clip of the same size after
   * the first costs nothing.
   */
  fun resize(
    gl: WebGl2,
    width: Int,
    height: Int,
  ) {
    if (width == linearWidth && height == linearHeight) return
    gl.activeTexture(GL_TEXTURE0)
    gl.bindTexture(GL_TEXTURE_2D, linearTexture)
    gl.texImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, width, height, 0, GL_RGBA, GL_HALF_FLOAT, null)
    linearWidth = width
    linearHeight = height
  }

  /**
   * Copies the frame's ten-bit planes out of the decoder, uploads them as three integer textures,
   * and decodes them to linear display light.
   *
   * The rows arrive top-down, so the flip the SDR upload asks the driver for is done in the shader
   * instead and the linear texture comes out +Y-up like every other texture here. The alignment and
   * the row length both have to be spelled out: a plane's stride is the decoder's own and a
   * chroma row of an odd width is not a multiple of four bytes.
   *
   * @return The linear texture the frame was decoded into.
   */
  suspend fun unpack(
    gl: WebGl2,
    frame: VideoSample,
    composite: JsAny,
  ): JsAny {
    val options = EMPTY_OPTIONS
    val frameWidth = frame.codedWidth
    val frameHeight = frame.codedHeight
    val bytes = bufferFor(frame.allocationSize(options))
    val layout = frame.copyTo(bytes, options).await()

    resize(gl, frameWidth, frameHeight)
    gl.useProgram(unpackProgram)
    gl.pixelStorei(GL_UNPACK_FLIP_Y_WEBGL, 0)
    gl.pixelStorei(GL_UNPACK_ALIGNMENT, SAMPLE_BYTES)
    for (index in 0 until PLANES) {
      val plane = checkNotNull(layout[index]) { "a ten-bit frame reports three planes" }
      val samplesPerRow = plane.stride / SAMPLE_BYTES
      val planeWidth = if (index == 0) frameWidth else (frameWidth + 1) / 2
      val planeHeight = if (index == 0) frameHeight else (frameHeight + 1) / 2
      gl.activeTexture(GL_TEXTURE0 + index)
      gl.bindTexture(GL_TEXTURE_2D, planeTextures[index])
      gl.pixelStorei(GL_UNPACK_ROW_LENGTH, samplesPerRow)
      gl.texImage2D(
        GL_TEXTURE_2D,
        0,
        GL_R16UI,
        planeWidth,
        planeHeight,
        0,
        GL_RED_INTEGER,
        GL_UNSIGNED_SHORT,
        Uint16Array(bytes, plane.offset, samplesPerRow * planeHeight),
      )
    }
    gl.pixelStorei(GL_UNPACK_ROW_LENGTH, 0)
    gl.pixelStorei(GL_UNPACK_ALIGNMENT, DEFAULT_ALIGNMENT)

    gl.bindFramebuffer(GL_FRAMEBUFFER, linearFbo)
    gl.viewport(0, 0, frameWidth, frameHeight)
    gl.disable(GL_BLEND)
    gl.drawArrays(GL_TRIANGLE_STRIP, 0, QUAD_VERTICES)
    gl.enable(GL_BLEND)
    gl.viewport(0, 0, outputWidth, outputHeight)
    gl.activeTexture(GL_TEXTURE0)
    gl.useProgram(composite)
    return linearTexture
  }

  /**
   * Runs the pack pass and reads its bytes back. The read is synchronous and stalls the pipeline
   * behind it, which is where a ten-bit frame spends most of its time.
   *
   * @return [packed], holding the frame's bytes.
   */
  fun pack(
    gl: WebGl2,
    composite: JsAny,
  ): Uint8Array {
    gl.useProgram(packProgram)
    gl.activeTexture(GL_TEXTURE0)
    gl.bindTexture(GL_TEXTURE_2D, outputTexture)
    gl.bindFramebuffer(GL_FRAMEBUFFER, packFbo)
    gl.viewport(0, 0, outputWidth / 2, 2 * outputHeight)
    gl.disable(GL_BLEND)
    gl.drawArrays(GL_TRIANGLE_STRIP, 0, QUAD_VERTICES)
    gl.readPixels(0, 0, outputWidth / 2, 2 * outputHeight, GL_RGBA, GL_UNSIGNED_BYTE, packed)
    gl.enable(GL_BLEND)
    gl.viewport(0, 0, outputWidth, outputHeight)
    gl.useProgram(composite)
    return packed
  }

  /**
   * Draws the composited light onto the canvas through the present pass.
   */
  fun present(
    gl: WebGl2,
    composite: JsAny,
  ) {
    gl.useProgram(presentProgram)
    gl.activeTexture(GL_TEXTURE0)
    gl.bindTexture(GL_TEXTURE_2D, outputTexture)
    gl.bindFramebuffer(GL_FRAMEBUFFER, null)
    gl.disable(GL_BLEND)
    gl.drawArrays(GL_TRIANGLE_STRIP, 0, QUAD_VERTICES)
    gl.enable(GL_BLEND)
    gl.useProgram(composite)
  }

  /**
   * Deletes every program, texture and framebuffer this pass holds.
   */
  fun release(gl: WebGl2) {
    planeTextures.forEach { gl.deleteTexture(it) }
    gl.deleteProgram(unpackProgram)
    gl.deleteFramebuffer(linearFbo)
    gl.deleteTexture(linearTexture)
    gl.deleteFramebuffer(outputFbo)
    gl.deleteTexture(outputTexture)
    gl.deleteProgram(packProgram)
    gl.deleteFramebuffer(packFbo)
    gl.deleteTexture(packTexture)
    gl.deleteProgram(presentProgram)
  }
}

/**
 * The programs, textures and buffers a kept grade needs: the three plane textures and the
 * program that decodes them, the float source and output framebuffers everything composites
 * through, the pack pass that writes the encoder's bytes, and the present pass a preview reads
 * the canvas through.
 */
internal fun buildHdrPass(
  gl: WebGl2,
  width: Int,
  height: Int,
  transfer: HdrTransfer,
  buffer: JsAny,
): HdrPass {
  val unpackProgram = linkedProgram(gl, FULLSCREEN_VERTEX_SHADER, unpackShader(transfer))
  gl.useProgram(unpackProgram)
  gl.bindBuffer(GL_ARRAY_BUFFER, buffer)
  bindQuad(gl, unpackProgram)
  listOf("uY", "uU", "uV").forEachIndexed { index, name ->
    gl.uniform1i(gl.getUniformLocation(unpackProgram, name), index)
  }
  // An integer texture cannot be filtered, and nearest is what the chroma planes want anyway:
  // a sample here is a code value, not a color to interpolate.
  val planeTextures = List(PLANES) { createTexture(gl, GL_NEAREST) }

  val linearTexture = createTexture(gl, GL_LINEAR)
  allocate(gl, linearTexture, width, height, hdr = true)
  val linearFbo = createComplete(gl, linearTexture)

  val outputTexture = createTexture(gl, GL_LINEAR)
  allocate(gl, outputTexture, width, height, hdr = true)
  val outputFbo = createComplete(gl, outputTexture)

  val packProgram = linkedProgram(gl, FULLSCREEN_VERTEX_SHADER, packShader(transfer))
  gl.useProgram(packProgram)
  gl.bindBuffer(GL_ARRAY_BUFFER, buffer)
  bindQuad(gl, packProgram)
  gl.uniform1i(gl.getUniformLocation(packProgram, "uTexSampler"), 0)
  gl.uniform2f(gl.getUniformLocation(packProgram, "uSize"), width.toFloat(), height.toFloat())
  val packTexture = createTexture(gl, GL_NEAREST)
  allocate(gl, packTexture, width / 2, 2 * height, hdr = false)
  val packFbo = createComplete(gl, packTexture)

  val presentProgram = linkedProgram(gl, FULLSCREEN_VERTEX_SHADER, PRESENT_FRAGMENT_SHADER)
  gl.useProgram(presentProgram)
  gl.bindBuffer(GL_ARRAY_BUFFER, buffer)
  bindQuad(gl, presentProgram)
  gl.uniform1i(gl.getUniformLocation(presentProgram, "uTexSampler"), 0)

  gl.bindFramebuffer(GL_FRAMEBUFFER, null)

  // Each row of the packed framebuffer is one row of one plane, so the stride is the same on
  // all three. A chroma row fills half of its stride, which the layout says outright rather
  // than the planes being interleaved: a frame's plane ranges may not overlap.
  val stride = width * SAMPLE_BYTES
  val lumaBytes = stride * height
  val chromaBytes = stride * (height / 2)
  return HdrPass(
    outputWidth = width,
    outputHeight = height,
    planeTextures = planeTextures,
    unpackProgram = unpackProgram,
    linearTexture = linearTexture,
    linearFbo = linearFbo,
    linearWidth = width,
    linearHeight = height,
    outputTexture = outputTexture,
    outputFbo = outputFbo,
    packProgram = packProgram,
    packTexture = packTexture,
    packFbo = packFbo,
    packed = Uint8Array(stride * 2 * height),
    presentProgram = presentProgram,
    layout =
      jsArrayOf(
        listOf(
          planeLayout(0, stride),
          planeLayout(lumaBytes, stride),
          planeLayout(lumaBytes + chromaBytes, stride),
        ),
      ),
    colorSpace =
      JsOptions()
        .put("primaries", "bt2020")
        .put("transfer", if (transfer == HdrTransfer.Pq) "pq" else "hlg")
        .put("matrix", "bt2020-ncl")
        .put("fullRange", false)
        .build(),
  )
}

private fun planeLayout(
  offset: Int,
  stride: Int,
): JsAny =
  JsOptions()
    .put("offset", offset)
    .put("stride", stride)
    .build()

internal const val PLANES: Int = 3
internal const val SAMPLE_BYTES: Int = 2
private const val DEFAULT_ALIGNMENT = 4

private val EMPTY_OPTIONS: JsAny = JsOptions().build()

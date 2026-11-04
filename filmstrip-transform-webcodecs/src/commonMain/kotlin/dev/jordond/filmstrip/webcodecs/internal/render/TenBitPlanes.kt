@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal.render

import dev.jordond.filmstrip.webcodecs.internal.interop.ArrayBuffer
import dev.jordond.filmstrip.webcodecs.internal.interop.JsOptions
import dev.jordond.filmstrip.webcodecs.internal.interop.Uint16Array
import dev.jordond.filmstrip.webcodecs.internal.interop.VideoSample
import dev.jordond.filmstrip.webcodecs.internal.interop.WebGl2
import kotlinx.coroutines.await
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.get

/**
 * The three integer textures a decoded ten-bit frame's planes are uploaded into, and the buffer the decoder copies them
 * out through, kept between frames.
 *
 * A program reading them takes luma on texture unit zero, Cb on one and Cr on two.
 */
internal class TenBitPlanes(
  private val textures: List<JsAny>,
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
   * Copies the frame's ten-bit planes out of the decoder and uploads them as three integer textures, bound on units
   * zero to two.
   *
   * The alignment and the row length both have to be spelled out: a plane's stride is the decoder's own and a chroma
   * row of an odd width is not a multiple of four bytes. Both go back to their defaults once the planes are up.
   */
  suspend fun upload(
    gl: WebGl2,
    frame: VideoSample,
  ) {
    val options = EMPTY_OPTIONS
    val frameWidth = frame.codedWidth
    val frameHeight = frame.codedHeight
    val bytes = bufferFor(frame.allocationSize(options))
    val layout = frame.copyTo(bytes, options).await()

    gl.pixelStorei(GL_UNPACK_FLIP_Y_WEBGL, 0)
    gl.pixelStorei(GL_UNPACK_ALIGNMENT, SAMPLE_BYTES)
    for (index in 0 until PLANES) {
      val plane = checkNotNull(layout[index]) { "a ten-bit frame reports three planes" }
      val samplesPerRow = plane.stride / SAMPLE_BYTES
      val planeWidth = if (index == 0) frameWidth else (frameWidth + 1) / 2
      val planeHeight = if (index == 0) frameHeight else (frameHeight + 1) / 2
      gl.activeTexture(GL_TEXTURE0 + index)
      gl.bindTexture(GL_TEXTURE_2D, textures[index])
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
  }

  /**
   * Deletes the three textures.
   */
  fun release(gl: WebGl2) {
    textures.forEach { gl.deleteTexture(it) }
  }
}

/**
 * Three plane textures on [gl], filtered nearest.
 *
 * An integer texture cannot be filtered, and nearest is what the chroma planes want anyway: a sample here is a code
 * value, not a color to interpolate.
 */
internal fun createTenBitPlanes(gl: WebGl2): TenBitPlanes = TenBitPlanes(List(PLANES) { createTexture(gl, GL_NEAREST) })

/**
 * Builds a program that reads [TenBitPlanes] through [fragmentSource] and draws the full-screen quad from [buffer].
 */
internal fun planeProgram(
  gl: WebGl2,
  fragmentSource: String,
  buffer: JsAny,
): JsAny {
  val program = linkedProgram(gl, FULLSCREEN_VERTEX_SHADER, fragmentSource)
  gl.useProgram(program)
  gl.bindBuffer(GL_ARRAY_BUFFER, buffer)
  bindQuad(gl, program)
  listOf("uY", "uU", "uV").forEachIndexed { index, name ->
    gl.uniform1i(gl.getUniformLocation(program, name), index)
  }
  return program
}

internal const val PLANES: Int = 3
internal const val SAMPLE_BYTES: Int = 2
private const val DEFAULT_ALIGNMENT = 4

private val EMPTY_OPTIONS: JsAny = JsOptions().build()

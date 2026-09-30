@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.authoredHdrNits
import dev.jordond.filmstrip.media.hlgDisplayNitsFromScene
import dev.jordond.filmstrip.media.hlgNitsFromScene
import dev.jordond.filmstrip.media.hlgSceneFromDisplayNits
import dev.jordond.filmstrip.media.hlgSceneFromNits
import dev.jordond.filmstrip.test.AuthoredColours
import dev.jordond.filmstrip.webcodecs.internal.BrowserCompositor
import dev.jordond.filmstrip.webcodecs.internal.FULLSCREEN_VERTEX_SHADER
import dev.jordond.filmstrip.webcodecs.internal.JsOptions
import dev.jordond.filmstrip.webcodecs.internal.OffscreenCanvas
import dev.jordond.filmstrip.webcodecs.internal.WebGl2
import dev.jordond.filmstrip.webcodecs.internal.eotfGlsl
import dev.jordond.filmstrip.webcodecs.internal.packShader
import dev.jordond.filmstrip.webcodecs.internal.toByteArray
import dev.jordond.filmstrip.webcodecs.internal.toFloat32Array
import dev.jordond.filmstrip.webcodecs.internal.toUint8Array
import dev.jordond.filmstrip.webcodecs.internal.unpackShader
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The shaders a kept grade decodes and packs through, compiled and linked on a bare WebGL2 context, and the HLG
 * opto-optical transfer they share read back off the GPU against core's.
 *
 * Every export test that reaches them first asks the browser for an HDR encoder, so a page without one would never
 * find out a shader had stopped building. This needs only the context, and skips only where WebGL2 itself is missing.
 */
class BrowserHdrShaderTest {
  @Test
  fun theUnpackAndPackShadersCompileAndLinkOnBothTransfers() {
    val gl = OffscreenCanvas(1, 1).getContext("webgl2", JsOptions().build()) ?: return
    try {
      HdrTransfer.entries.forEach { transfer ->
        mapOf("unpack" to unpackShader(transfer), "pack" to packShader(transfer)).forEach { (name, source) ->
          val program =
            try {
              BrowserCompositor.link(gl, FULLSCREEN_VERTEX_SHADER, source)
            } catch (failure: Throwable) {
              throw AssertionError("the $transfer $name shader did not build: ${failure.message}")
            }
          gl.deleteProgram(program)
        }
      }
    } finally {
      gl.getExtension("WEBGL_lose_context")?.loseContext()
    }
  }

  // A coloured value in the middle of the range, where a per channel power and the luminance driven transfer part
  // company. The GPU's pow is not bit exact, so the read is held to a relative bound far inside that gap.
  @Test
  fun theShadersForwardHlgTransferIsCores() {
    val scene = hlgSceneFromNits(authoredHdrNits(AuthoredColours.MID_GREEN))
    val read = hlgTransferOnTheGpu("hlgDisplayFromScene", scene) ?: return

    assertOnCore(hlgNitsFromScene(scene), read, FloatArray(3) { hlgDisplayNitsFromScene(scene[it]) }, "forward")
  }

  @Test
  fun theShadersInverseHlgTransferIsCores() {
    val nits = authoredHdrNits(AuthoredColours.MID_GREEN)
    val read = hlgTransferOnTheGpu("hlgSceneFromDisplay", nits) ?: return

    assertOnCore(hlgSceneFromNits(nits), read, FloatArray(3) { hlgSceneFromDisplayNits(nits[it]) }, "inverse")
  }

  /**
   * Runs the HLG transfer [function] from the compositor's own GLSL on [input] and reads its three channels back
   * exactly, as the bits of each float packed into an eight-bit texel. Null where the page has no WebGL2.
   */
  private fun hlgTransferOnTheGpu(
    function: String,
    input: FloatArray,
  ): FloatArray? {
    val gl = OffscreenCanvas(CHANNELS, 1).getContext("webgl2", JsOptions().build()) ?: return null
    try {
      val program = BrowserCompositor.link(gl, FULLSCREEN_VERTEX_SHADER, probeShader(function))
      gl.useProgram(program)
      draw(gl, program, input)

      val pixels = ByteArray(CHANNELS * RGBA).toUint8Array()
      gl.readPixels(0, 0, CHANNELS, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixels)
      val bytes = pixels.toByteArray()
      return FloatArray(CHANNELS) { channel ->
        val bits =
          (0 until RGBA).fold(0) { word, byte ->
            word or ((bytes[channel * RGBA + byte].toInt() and BYTE_MASK) shl (byte * BYTE_BITS))
          }
        Float.fromBits(bits)
      }
    } finally {
      gl.getExtension("WEBGL_lose_context")?.loseContext()
    }
  }

  private fun draw(
    gl: WebGl2,
    program: JsAny,
    input: FloatArray,
  ) {
    val texture = gl.createTexture()
    gl.bindTexture(GL_TEXTURE_2D, texture)
    gl.texImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, CHANNELS, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
    gl.bindFramebuffer(GL_FRAMEBUFFER, gl.createFramebuffer())
    gl.framebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)

    gl.bindBuffer(GL_ARRAY_BUFFER, gl.createBuffer())
    gl.bufferData(GL_ARRAY_BUFFER, QUAD.toFloat32Array(), GL_STATIC_DRAW)
    val position = gl.getAttribLocation(program, "aPosition")
    gl.enableVertexAttribArray(position)
    gl.vertexAttribPointer(position, 2, GL_FLOAT, false, 0, 0)

    gl.uniform4f(gl.getUniformLocation(program, "uInput"), input[0], input[1], input[2], 0f)
    gl.viewport(0, 0, CHANNELS, 1)
    gl.drawArrays(GL_TRIANGLE_STRIP, 0, QUAD.size / 2)
  }

  // Each pixel of the three wide target carries one channel, written as its float's bytes with the low byte in red.
  private fun probeShader(function: String): String =
    """
    #version 300 es
    precision highp float;
    precision highp int;
    uniform vec4 uInput;
    out vec4 outColor;
    ${eotfGlsl(HdrTransfer.Hlg)}
    void main() {
      vec3 result = $function(uInput.rgb);
      int channel = int(gl_FragCoord.x);
      float value = channel == 0 ? result.r : (channel == 1 ? result.g : result.b);
      uint bits = floatBitsToUint(value);
      outColor =
        vec4(float(bits & 255u), float((bits >> 8u) & 255u), float((bits >> 16u) & 255u), float(bits >> 24u)) / 255.0;
    }
    """.trimIndent()

  private fun assertOnCore(
    expected: FloatArray,
    read: FloatArray,
    perChannel: FloatArray,
    what: String,
  ) {
    repeat(CHANNELS) { channel ->
      assertTrue(
        abs(read[channel] - expected[channel]) <= abs(expected[channel]) * RELATIVE_TOLERANCE,
        "the $what HLG transfer read ${read.toList()}, core says ${expected.toList()}, and a per channel " +
          "transfer would give ${perChannel.toList()}",
      )
    }
    assertTrue(
      (0 until CHANNELS).any { abs(perChannel[it] - expected[it]) > abs(expected[it]) * RELATIVE_TOLERANCE },
      "MID_GREEN reads the same under a per channel transfer, so the $what case pins nothing",
    )
  }

  private companion object {
    const val CHANNELS = 3
    const val RGBA = 4
    const val BYTE_BITS = 8
    const val BYTE_MASK = 0xFF

    // Far inside the gap between the two transfers on a coloured value, and far outside a GPU pow's own error.
    const val RELATIVE_TOLERANCE = 1e-4f

    val QUAD = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)

    const val GL_TEXTURE_2D = 0x0DE1
    const val GL_RGBA = 0x1908
    const val GL_RGBA8 = 0x8058
    const val GL_UNSIGNED_BYTE = 0x1401
    const val GL_FRAMEBUFFER = 0x8D40
    const val GL_COLOR_ATTACHMENT0 = 0x8CE0
    const val GL_ARRAY_BUFFER = 0x8892
    const val GL_STATIC_DRAW = 0x88E4
    const val GL_FLOAT = 0x1406
    const val GL_TRIANGLE_STRIP = 0x0005
  }
}

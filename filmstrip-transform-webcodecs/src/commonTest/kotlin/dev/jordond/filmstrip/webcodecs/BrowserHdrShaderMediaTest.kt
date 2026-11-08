@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.color.authoredHdrNits
import dev.jordond.filmstrip.color.hlgDisplayNitsFromScene
import dev.jordond.filmstrip.color.hlgNitsFromScene
import dev.jordond.filmstrip.color.hlgSceneFromDisplayNits
import dev.jordond.filmstrip.color.hlgSceneFromNits
import dev.jordond.filmstrip.color.pqSignalFromNits
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.test.AuthoredColors
import dev.jordond.filmstrip.transform.internal.hdr.toneMappedSdr
import dev.jordond.filmstrip.webcodecs.internal.interop.JsOptions
import dev.jordond.filmstrip.webcodecs.internal.interop.OffscreenCanvas
import dev.jordond.filmstrip.webcodecs.internal.interop.WebGl2
import dev.jordond.filmstrip.webcodecs.internal.interop.toByteArray
import dev.jordond.filmstrip.webcodecs.internal.interop.toFloat32Array
import dev.jordond.filmstrip.webcodecs.internal.interop.toUint8Array
import dev.jordond.filmstrip.webcodecs.internal.render.FULLSCREEN_VERTEX_SHADER
import dev.jordond.filmstrip.webcodecs.internal.render.eotfGlsl
import dev.jordond.filmstrip.webcodecs.internal.render.linkedProgram
import dev.jordond.filmstrip.webcodecs.internal.render.packShader
import dev.jordond.filmstrip.webcodecs.internal.render.toneMapGlsl
import dev.jordond.filmstrip.webcodecs.internal.render.toneMapShader
import dev.jordond.filmstrip.webcodecs.internal.render.unpackShader
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.math.abs
import kotlin.test.Test
import dev.jordond.filmstrip.transform.internal.hdr.Rgb as SignalRgb

/**
 * The shaders a kept grade decodes and packs through and the one a tone map runs, compiled and linked on a bare WebGL2
 * context, with the HLG opto-optical transfer and the shared tone curve read back off the GPU against their references.
 *
 * Every export test that reaches them first asks the browser for an HDR encoder, so a page without one would never
 * find out a shader had stopped building. This needs only the context, and skips only where WebGL2 itself is missing.
 */
class BrowserHdrShaderMediaTest {
  @Test
  fun theUnpackPackAndToneMapShadersCompileAndLinkOnBothTransfers() {
    val gl = OffscreenCanvas(1, 1).getContext("webgl2", JsOptions().build()) ?: return
    try {
      HdrTransfer.entries.forEach { transfer ->
        val shaders =
          mapOf(
            "unpack" to unpackShader(transfer),
            "pack" to packShader(transfer),
            "tone map" to toneMapShader(transfer),
          )
        shaders.forEach { (name, source) ->
          val program =
            try {
              linkedProgram(gl, FULLSCREEN_VERTEX_SHADER, source)
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

  // A colored value in the middle of the range, where a per channel power and the luminance driven transfer part
  // company. The GPU's pow is not bit exact, so the read is held to a relative bound far inside that gap.
  @Test
  fun theShadersForwardHlgTransferIsCores() {
    val scene = hlgSceneFromNits(authoredHdrNits(AuthoredColors.MID_GREEN))
    val read = onTheGpu(eotfGlsl(HdrTransfer.Hlg), "hlgDisplayFromScene", scene) ?: return

    assertOnCore(hlgNitsFromScene(scene), read, FloatArray(3) { hlgDisplayNitsFromScene(scene[it]) }, "forward")
  }

  @Test
  fun theShadersInverseHlgTransferIsCores() {
    val nits = authoredHdrNits(AuthoredColors.MID_GREEN)
    val read = onTheGpu(eotfGlsl(HdrTransfer.Hlg), "hlgSceneFromDisplay", nits) ?: return

    assertOnCore(hlgSceneFromNits(nits), read, FloatArray(3) { hlgSceneFromDisplayNits(nits[it]) }, "inverse")
  }

  // PQ at 200, 500 and 800 cd/m2 reads the straight part and each Hermite piece, a colour with 2000 cd/m2 green reads
  // the clamp above the mastering peak, HLG reads mid signals, and on both an off-grey colour takes one gain on every
  // channel and a colour with no green takes the black green gain. The GPU's pow is not bit exact, so each channel is
  // held to a bound far inside one eight-bit code.
  @Test
  fun theToneMapShaderIsTheSharedCurveOnPq() {
    val greys = listOf(200.0, 500.0, 800.0).map { nits -> List(CHANNELS) { pqSignalFromNits(nits) } }
    val colours =
      listOf(
        listOf(300.0, 500.0, 100.0).map(::pqSignalFromNits),
        listOf(1500.0, 2000.0, 1200.0).map(::pqSignalFromNits),
        listOf(pqSignalFromNits(300.0), 0.0, pqSignalFromNits(100.0)),
      )
    assertToneMapped(HdrTransfer.Pq, greys + colours)
  }

  @Test
  fun theToneMapShaderIsTheSharedCurveOnHlg() {
    val greys = listOf(0.5, 0.65, 0.75).map { signal -> List(CHANNELS) { signal } }
    assertToneMapped(HdrTransfer.Hlg, greys + listOf(listOf(0.7, 0.55, 0.3), listOf(0.6, 0.0, 0.4)))
  }

  private fun assertToneMapped(
    transfer: HdrTransfer,
    signals: List<List<Double>>,
  ) {
    signals.forEach { signal ->
      val input = FloatArray(CHANNELS) { signal[it].toFloat() }
      val read = onTheGpu(toneMapGlsl(transfer), "toneMappedSdr", input) ?: return
      val expected =
        toneMappedSdr(transfer, SignalRgb(input[0].toDouble(), input[1].toDouble(), input[2].toDouble()))
          .let { floatArrayOf(it.r.toFloat(), it.g.toFloat(), it.b.toFloat()) }
      withClue(
        "$transfer ${signal.toList()} tone mapped to ${read.toList()} where the curve says ${expected.toList()}",
      ) {
        (0 until CHANNELS).all { abs(read[it] - expected[it]) <= TONE_MAP_TOLERANCE } shouldBe true
      }
    }
  }

  /**
   * Runs [function] from the compositor's own GLSL in [prelude] on [input] and reads its three channels back exactly,
   * as the bits of each float packed into an eight-bit texel. Null where the page has no WebGL2.
   */
  private fun onTheGpu(
    prelude: String,
    function: String,
    input: FloatArray,
  ): FloatArray? {
    val gl = OffscreenCanvas(CHANNELS, 1).getContext("webgl2", JsOptions().build()) ?: return null
    try {
      val program = linkedProgram(gl, FULLSCREEN_VERTEX_SHADER, probeShader(prelude, function))
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
  private fun probeShader(
    prelude: String,
    function: String,
  ): String =
    """
    #version 300 es
    precision highp float;
    precision highp int;
    uniform vec4 uInput;
    out vec4 outColor;
    $prelude
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
      withClue(
        "the $what HLG transfer read ${read.toList()}, core says ${expected.toList()}, and a per channel " +
          "transfer would give ${perChannel.toList()}",
      ) {
        (abs(read[channel] - expected[channel]) <= abs(expected[channel]) * RELATIVE_TOLERANCE) shouldBe true
      }
    }
    withClue("MID_GREEN reads the same under a per channel transfer, so the $what case pins nothing") {
      (0 until CHANNELS).any { abs(perChannel[it] - expected[it]) > abs(expected[it]) * RELATIVE_TOLERANCE } shouldBe
        true
    }
  }

  private companion object {
    const val CHANNELS = 3
    const val RGBA = 4
    const val BYTE_BITS = 8
    const val BYTE_MASK = 0xFF

    // Far inside the gap between the two transfers on a colored value, and far outside a GPU pow's own error.
    const val RELATIVE_TOLERANCE = 1e-4f

    // A fortieth of an eight-bit code on the SDR signal.
    const val TONE_MAP_TOLERANCE = 1e-4f

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

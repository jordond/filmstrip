@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.webcodecs.internal.TEN_BIT_FORMAT
import dev.jordond.filmstrip.webcodecs.internal.interop.JsOptions
import dev.jordond.filmstrip.webcodecs.internal.interop.OffscreenCanvas
import dev.jordond.filmstrip.webcodecs.internal.interop.Uint8Array
import dev.jordond.filmstrip.webcodecs.internal.interop.VideoSample
import dev.jordond.filmstrip.webcodecs.internal.interop.WebGl2
import dev.jordond.filmstrip.webcodecs.internal.interop.toUint8Array
import dev.jordond.filmstrip.webcodecs.internal.render.FULLSCREEN_VERTEX_SHADER
import dev.jordond.filmstrip.webcodecs.internal.render.HdrPass
import dev.jordond.filmstrip.webcodecs.internal.render.PRESENT_FRAGMENT_SHADER
import dev.jordond.filmstrip.webcodecs.internal.render.SAMPLE_BYTES
import dev.jordond.filmstrip.webcodecs.internal.render.buildHdrPass
import dev.jordond.filmstrip.webcodecs.internal.render.linkedProgram
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import kotlinx.coroutines.test.runTest
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.test.Test

/**
 * The pass a kept grade decodes, packs and presents through, built on a bare WebGL2 context.
 *
 * The layout and the packed buffer are what the encoder is handed, so a frame built from them is read back as the
 * browser reads it. Skips only where the page has no WebGL2.
 */
class HdrPassTest {
  // The browser refuses a frame whose planes overlap or run past its buffer, and sizes one from the layout
  // it is given, so a pack that fills the buffer exactly is the layout landing where the bytes do.
  @Test
  fun aPackedFrameFillsItsBufferWithTheLayoutItIsHandedOn() {
    HdrTransfer.entries.forEach { transfer ->
      withPass(transfer) { gl, pass, composite ->
        val packed = pass.pack(gl, composite)

        packed shouldBeSameInstanceAs pass.packed
        packed.length shouldBe WIDTH * SAMPLE_BYTES * 2 * HEIGHT
        val sample = tenBitSample(packed, pass)
        try {
          sample.format?.toString() shouldBe TEN_BIT_FORMAT
          sample.codedWidth shouldBe WIDTH
          sample.codedHeight shouldBe HEIGHT
          sample.allocationSize(JsOptions().put("layout", pass.layout).build()) shouldBe packed.length
        } finally {
          sample.close()
        }
      }
    }
  }

  @Test
  fun theDecoderBufferIsReusedUntilAFrameNeedsMore() {
    withPass(HdrTransfer.Pq) { _, pass, _ ->
      val first = pass.planes.bufferFor(LARGE)

      pass.planes.bufferFor(SMALL) shouldBeSameInstanceAs first
      pass.planes.bufferFor(LARGE) shouldBeSameInstanceAs first
      val grown = pass.planes.bufferFor(LARGE + 1)
      grown shouldNotBeSameInstanceAs first
      (grown.byteLength >= LARGE + 1) shouldBe true
    }
  }

  // The linear source follows the decoded frame rather than the output, so a clip of another size
  // reallocates it and the output stays where it was.
  @Test
  fun unpackingAFrameOfAnotherSizeFollowsTheFrame() =
    runTest {
      val gl = context() ?: return@runTest
      try {
        val pass = buildHdrPass(gl, WIDTH, HEIGHT, HdrTransfer.Hlg, checkNotNull(gl.createBuffer()))
        val composite = linkedProgram(gl, FULLSCREEN_VERTEX_SHADER, PRESENT_FRAGMENT_SHADER)
        val frame = blackTenBitFrame(SMALL_WIDTH, SMALL_HEIGHT)
        try {
          pass.unpack(gl, frame, composite) shouldBeSameInstanceAs pass.linearTexture
        } finally {
          frame.close()
        }

        (pass.linearWidth to pass.linearHeight) shouldBe (SMALL_WIDTH to SMALL_HEIGHT)
        (pass.outputWidth to pass.outputHeight) shouldBe (WIDTH to HEIGHT)
        pass.release(gl)
      } finally {
        gl.getExtension("WEBGL_lose_context")?.loseContext()
      }
    }

  private fun withPass(
    transfer: HdrTransfer,
    block: (WebGl2, HdrPass, JsAny) -> Unit,
  ) {
    val gl = context() ?: return
    try {
      val pass = buildHdrPass(gl, WIDTH, HEIGHT, transfer, checkNotNull(gl.createBuffer()))
      block(gl, pass, linkedProgram(gl, FULLSCREEN_VERTEX_SHADER, PRESENT_FRAGMENT_SHADER))
      pass.release(gl)
    } finally {
      gl.getExtension("WEBGL_lose_context")?.loseContext()
    }
  }

  // The float framebuffers the pass composites through need EXT_color_buffer_float, which the compositor
  // asks for before it builds one, so a page without it skips here the way an export refuses there.
  private fun context(): WebGl2? {
    val gl = OffscreenCanvas(WIDTH, HEIGHT).getContext("webgl2", JsOptions().build()) ?: return null
    if (gl.getExtension("EXT_color_buffer_float") != null) return gl
    gl.getExtension("WEBGL_lose_context")?.loseContext()
    return null
  }

  private fun tenBitSample(
    packed: Uint8Array,
    pass: HdrPass,
  ): VideoSample =
    VideoSample(
      packed,
      JsOptions()
        .put("format", TEN_BIT_FORMAT)
        .put("codedWidth", WIDTH)
        .put("codedHeight", HEIGHT)
        .put("layout", pass.layout)
        .put("colorSpace", pass.colorSpace)
        .put("timestamp", 0.0)
        .put("duration", FRAME_SECONDS)
        .build(),
    )

  // A tightly packed ten-bit frame of zero codes, which is all a resize needs to read.
  private fun blackTenBitFrame(
    width: Int,
    height: Int,
  ): VideoSample {
    val bytes = ByteArray(width * height * SAMPLE_BYTES + 2 * (width / 2) * (height / 2) * SAMPLE_BYTES)
    return VideoSample(
      bytes.toUint8Array(),
      JsOptions()
        .put("format", TEN_BIT_FORMAT)
        .put("codedWidth", width)
        .put("codedHeight", height)
        .put("timestamp", 0.0)
        .put("duration", FRAME_SECONDS)
        .build(),
    )
  }

  private companion object {
    const val WIDTH = 64
    const val HEIGHT = 36
    const val SMALL_WIDTH = 32
    const val SMALL_HEIGHT = 18
    const val SMALL = 1_024
    const val LARGE = 4_096
    const val FRAME_SECONDS = 1.0 / 30
  }
}

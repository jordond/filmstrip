package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.webcodecs.internal.BrowserCompositor
import dev.jordond.filmstrip.webcodecs.internal.FULLSCREEN_VERTEX_SHADER
import dev.jordond.filmstrip.webcodecs.internal.JsOptions
import dev.jordond.filmstrip.webcodecs.internal.OffscreenCanvas
import dev.jordond.filmstrip.webcodecs.internal.packShader
import dev.jordond.filmstrip.webcodecs.internal.unpackShader
import kotlin.test.Test

/**
 * The shaders a kept grade decodes and packs through, compiled and linked on a bare WebGL2 context.
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
}

@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal.render

import dev.jordond.filmstrip.effect.WebGlPass
import dev.jordond.filmstrip.webcodecs.internal.interop.WebGl2
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.time.Duration

/**
 * The frame the composition's passes under the fill run on, and the draw that lays their result over the fill.
 *
 * Under a color fill the picture is drawn onto a clear frame, so each draw onto it blends over nothing and it holds
 * premultiplied color, with alpha only where a clip or its overlays landed. Its frames say so, and the passes run on
 * it read straight color. [lay] puts it over the fill the target was cleared to as a premultiplied source over, so a
 * pixel the picture never covered keeps the fill as it was. A texel with no alpha is dropped whatever color a pass
 * wrote into it, since nothing of the picture is there. A blurred background is drawn into the frame first, so the
 * frame is opaque and [lay] covers the target.
 */
internal class UnderFillStage private constructor(
  private val gl: WebGl2,
  private val program: JsAny,
  private val frames: PassFrames,
) {
  /**
   * Binds a [width] by [height] frame cleared to nothing for the picture to draw into, and puts [clearColor] back as
   * the clear the compositor's own target takes.
   */
  fun open(
    width: Int,
    height: Int,
    clearColor: FloatArray,
  ) {
    gl.bindFramebuffer(GL_FRAMEBUFFER, frames.read(width, height).framebuffer)
    gl.clearColor(0f, 0f, 0f, 0f)
    gl.clear(GL_COLOR_BUFFER_BIT)
    gl.clearColor(clearColor[0], clearColor[1], clearColor[2], 1f)
  }

  /**
   * The framebuffer of the [width] by [height] frame the picture draws into, for a blurred background drawn there
   * ahead of it in place of [open].
   */
  fun frame(
    width: Int,
    height: Int,
  ): JsAny = frames.read(width, height).framebuffer

  /**
   * Runs [passes] through [shaderPass] over the picture [open] bound, and answers the texture the last one wrote.
   */
  fun run(
    shaderPass: ShaderPass,
    passes: List<WebGlPass>,
    width: Int,
    height: Int,
    compositionTime: Duration,
  ): JsAny = shaderPass.run(passes, frames, width, height, compositionTime)

  /**
   * Draws [picture] over the bound framebuffer and viewport and puts the compositor's own blending back after it.
   *
   * Leaves this stage's program bound, so the caller binds its own again before drawing anything else.
   */
  fun lay(picture: JsAny) {
    gl.useProgram(program)
    gl.blendFuncSeparate(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
    gl.activeTexture(GL_TEXTURE0)
    gl.bindTexture(GL_TEXTURE_2D, picture)
    gl.drawArrays(GL_TRIANGLE_STRIP, 0, QUAD_VERTICES)
    gl.blendOntoFill()
  }

  fun release() {
    frames.release()
    gl.deleteProgram(program)
  }

  companion object {
    /**
     * Links the stage's program, drawing its quad from [buffer], with frames at half float when [hdr] is set.
     */
    fun create(
      gl: WebGl2,
      buffer: JsAny,
      hdr: Boolean,
    ): UnderFillStage {
      val program = linkedProgram(gl, FULLSCREEN_VERTEX_SHADER, LAY_FRAGMENT_SHADER)
      gl.useProgram(program)
      gl.bindBuffer(GL_ARRAY_BUFFER, buffer)
      bindQuad(gl, program)
      gl.uniform1i(gl.getUniformLocation(program, "uPicture"), 0)
      return UnderFillStage(gl, program, PassFrames(gl, hdr, FrameAlpha.Premultiplied))
    }
  }
}

private val LAY_FRAGMENT_SHADER =
  """
  #version 300 es
  precision highp float;
  uniform sampler2D uPicture;
  in vec2 vTexCoord;
  out vec4 outColor;
  void main() {
    vec4 texel = texture(uPicture, vTexCoord);
    outColor = texel.a > 0.0 ? texel : vec4(0.0);
  }
  """.trimIndent()

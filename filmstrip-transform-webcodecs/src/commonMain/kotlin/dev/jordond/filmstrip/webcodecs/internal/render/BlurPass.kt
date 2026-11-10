@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal.render

import dev.jordond.filmstrip.color.BlurPlan
import dev.jordond.filmstrip.effects.color.IDENTITY_GRADE_LIGHT_GLSL
import dev.jordond.filmstrip.effects.color.displayLightGradeGlsl
import dev.jordond.filmstrip.effects.color.displayLightGradeUniforms
import dev.jordond.filmstrip.effects.spatial.SpatialBlur
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.transform.internal.geometry.backgroundGain
import dev.jordond.filmstrip.transform.internal.geometry.blurPlan
import dev.jordond.filmstrip.webcodecs.internal.interop.WebGl2
import dev.jordond.filmstrip.webcodecs.internal.interop.toFloat32Array
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny

/**
 * The GL objects a blurred fill needs to blur its cover by the fill's [BlurPlan].
 *
 * The cover is drawn into [coverFbo] at the output size, boxed down to the plan's size by [downProgram], blurred across
 * and then up by [SpatialBlur.GAUSSIAN_GLSL] in [blurProgram], and read back up to the output with linear filtering by
 * [upProgram], which divides by the blurred alpha and applies the dim. On a kept grade the cover holds linear display
 * light, [downProgram] carries it to the SDR signal it encodes to before it averages, the blur and the dim run on that
 * signal, and [upProgram] carries it back.
 *
 * @property smallWidth The width the plan blurs at.
 * @property smallHeight The height the plan blurs at.
 */
internal class BlurPass(
  private val downProgram: JsAny,
  private val blurProgram: JsAny,
  private val upProgram: JsAny,
  private val across: Map<String, FloatArray>,
  private val upward: Map<String, FloatArray>,
  val smallWidth: Int,
  val smallHeight: Int,
  val coverFbo: JsAny,
  private val coverTexture: JsAny,
  private val downFbo: JsAny,
  private val downTexture: JsAny,
  private val acrossFbo: JsAny,
  private val acrossTexture: JsAny,
) {
  /**
   * Boxes down what the cover pass drew into [coverFbo], blurs it across and then up, dims it, and draws it into
   * [target] at the [width] by [height] output, which it clears first. Leaves [target] bound and [upProgram] in use.
   *
   * The cover holds color premultiplied by alpha, so every pass writes what it computes with blending off. The last
   * divides by the blurred alpha, which is what keeps a picture's level beside pixels with none.
   */
  fun blurCoverOnto(
    gl: WebGl2,
    target: JsAny?,
    width: Int,
    height: Int,
  ) {
    gl.disable(GL_BLEND)
    gl.viewport(0, 0, smallWidth, smallHeight)
    gl.useProgram(downProgram)
    drawInto(gl, downFbo, coverTexture)

    gl.useProgram(blurProgram)
    upload(gl, blurProgram, across)
    drawInto(gl, acrossFbo, downTexture)
    upload(gl, blurProgram, upward)
    drawInto(gl, downFbo, acrossTexture)

    gl.viewport(0, 0, width, height)
    gl.useProgram(upProgram)
    drawInto(gl, target, downTexture)
    gl.enable(GL_BLEND)
  }

  /**
   * Deletes every program, texture and framebuffer this pass holds.
   */
  fun release(gl: WebGl2) {
    listOf(coverFbo, downFbo, acrossFbo).forEach { gl.deleteFramebuffer(it) }
    listOf(coverTexture, downTexture, acrossTexture).forEach { gl.deleteTexture(it) }
    listOf(downProgram, blurProgram, upProgram).forEach { gl.deleteProgram(it) }
  }

  private fun drawInto(
    gl: WebGl2,
    framebuffer: JsAny?,
    texture: JsAny,
  ) {
    gl.bindFramebuffer(GL_FRAMEBUFFER, framebuffer)
    gl.clear(GL_COLOR_BUFFER_BIT)
    gl.bindTexture(GL_TEXTURE_2D, texture)
    gl.drawArrays(GL_TRIANGLE_STRIP, 0, QUAD_VERTICES)
  }

  private fun upload(
    gl: WebGl2,
    program: JsAny,
    uniforms: Map<String, FloatArray>,
  ) {
    uniforms.forEach { (name, value) ->
      val at = gl.getUniformLocation(program, name)
      when {
        name.endsWith("[0]") -> gl.uniform1fv(at, value.toFloat32Array())
        value.size == 2 -> gl.uniform2f(at, value[0], value[1])
        else -> gl.uniform1f(at, value[0])
      }
    }
  }
}

/**
 * The programs, textures and framebuffers a blurred fill needs, for a [width] by [height] output. Built once per
 * export and never touched again outside `BrowserCompositor.drawBackground`, so a [Fill.Solid] export never pays for
 * any of it.
 */
internal fun buildBlurPass(
  gl: WebGl2,
  width: Int,
  height: Int,
  fill: Fill.Blurred,
  buffer: JsAny,
  hdrTransfer: HdrTransfer?,
): BlurPass {
  val plan = fill.blurPlan(Size(width, height))
  val smallHeight = plan.heightOf(height)
  gl.bindBuffer(GL_ARRAY_BUFFER, buffer)

  val hdr = hdrTransfer != null
  val signal = SpatialBlur.signalUniforms(hdrTransfer?.displayLightGradeUniforms())
  val gradeLight = hdrTransfer?.displayLightGradeGlsl() ?: IDENTITY_GRADE_LIGHT_GLSL
  val downProgram = quadProgram(gl, fillBoxDownShader(hdr, gradeLight))
  uploadSignal(gl, downProgram, signal)
  gl.uniform2f(gl.getUniformLocation(downProgram, "uTargetSize"), plan.width.toFloat(), smallHeight.toFloat())
  gl.uniform1f(gl.getUniformLocation(downProgram, "uBox"), plan.factor.toFloat())

  val blurProgram = quadProgram(gl, SpatialBlur.GAUSSIAN_GLSL)

  val upProgram = quadProgram(gl, fillUpShader(hdr, gradeLight))
  uploadSignal(gl, upProgram, signal)
  gl.uniform1f(gl.getUniformLocation(upProgram, "uGain"), fill.backgroundGain)

  val coverTexture = createFramebufferTexture(gl, width, height, hdr)
  val coverFbo = createComplete(gl, coverTexture)
  val downTexture = createFramebufferTexture(gl, plan.width, smallHeight, hdr)
  val downFbo = createComplete(gl, downTexture)
  val acrossTexture = createFramebufferTexture(gl, plan.width, smallHeight, hdr)
  val acrossFbo = createComplete(gl, acrossTexture)
  gl.bindFramebuffer(GL_FRAMEBUFFER, null)

  return BlurPass(
    downProgram = downProgram,
    blurProgram = blurProgram,
    upProgram = upProgram,
    across = SpatialBlur.gaussianUniforms(plan, height, horizontal = true),
    upward = SpatialBlur.gaussianUniforms(plan, height, horizontal = false),
    smallWidth = plan.width,
    smallHeight = smallHeight,
    coverFbo = coverFbo,
    coverTexture = coverTexture,
    downFbo = downFbo,
    downTexture = downTexture,
    acrossFbo = acrossFbo,
    acrossTexture = acrossTexture,
  )
}

// Sets the figures SpatialBlur.signalGlsl reads, on a program that is in use.
private fun uploadSignal(
  gl: WebGl2,
  program: JsAny,
  signal: Map<String, FloatArray>,
) {
  signal.forEach { (name, value) -> gl.uniform1f(gl.getUniformLocation(program, name), value[0]) }
}

// A program over the full-screen quad, left in use with its frame on texture unit 0.
private fun quadProgram(
  gl: WebGl2,
  fragment: String,
): JsAny {
  val program = linkedProgram(gl, PASS_VERTEX_SHADER, fragment)
  gl.useProgram(program)
  bindQuad(gl, program)
  gl.uniform1i(gl.getUniformLocation(program, "uFrame"), 0)
  return program
}

// Moves a texel between the light the cover holds and the signal the blur runs on, which is the identity for a cover
// that already holds the signal.
private fun signalSource(
  hdr: Boolean,
  gradeLight: String,
): String =
  if (hdr) {
    SpatialBlur.signalGlsl(gradeLight)
  } else {
    "vec3 toSignal(vec3 light) { return light; }\nvec3 fromSignal(vec3 signal) { return signal; }"
  }

// Boxes the cover down to the plan's size with SpatialBlur.BOX_DOWN_GLSL, each texel read as the signal. The cover is
// premultiplied by alpha, so a texel is averaged as it is.
private fun fillBoxDownShader(
  hdr: Boolean,
  gradeLight: String,
): String =
  """
  #version 300 es
  precision highp float;
  in vec2 vUv;
  out vec4 fragColor;
  uniform sampler2D uFrame;
  uniform vec2 uTargetSize;
  uniform float uBox;

${signalSource(hdr, gradeLight).prependIndent("  ")}

  vec4 premultipliedAt(ivec2 texel, ivec2 size) {
    vec4 read = texelFetch(uFrame, clamp(texel, ivec2(0), size - 1), 0);
    return vec4(toSignal(read.rgb), read.a);
  }

${SpatialBlur.BOX_DOWN_GLSL.prependIndent("  ")}

  void main() {
    fragColor = boxedDown(textureSize(uFrame, 0), vUv, uTargetSize, uBox);
  }
  """.trimIndent()

// Reads the blurred frame back up with linear filtering, divides it by its alpha, dims its signal and takes it back to
// the light the cover held. The cover it writes is opaque.
private fun fillUpShader(
  hdr: Boolean,
  gradeLight: String,
): String =
  """
  #version 300 es
  precision highp float;
  in vec2 vUv;
  out vec4 fragColor;
  uniform sampler2D uFrame;
  uniform float uGain;

${signalSource(hdr, gradeLight).prependIndent("  ")}

  void main() {
    vec4 blurred = textureLod(uFrame, vUv, 0.0);
    vec3 color = blurred.a > 0.0 ? blurred.rgb / blurred.a : vec3(0.0);
    fragColor = vec4(fromSignal(color * uGain), 1.0);
  }
  """.trimIndent()

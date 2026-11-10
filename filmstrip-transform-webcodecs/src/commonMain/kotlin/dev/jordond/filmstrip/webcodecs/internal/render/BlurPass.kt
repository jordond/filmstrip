@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal.render

import dev.jordond.filmstrip.color.linearDimGain
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.transform.internal.geometry.backgroundGain
import dev.jordond.filmstrip.transform.internal.geometry.sigmaFor
import dev.jordond.filmstrip.webcodecs.internal.interop.WebGl2
import dev.jordond.filmstrip.webcodecs.internal.interop.toFloat32Array
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.math.ceil
import kotlin.math.exp

/**
 * The extra GL objects a blurred fill needs: one program shared by the cover, horizontal blur and
 * vertical blur passes, the two framebuffers those passes render into, and the textures backing
 * them.
 *
 * @property smallWidth The width [coverFbo] and [blurFbo] render at, from [blurPlan]. Narrower than
 *   the output whenever the blur is heavy enough to need downscaling.
 * @property smallHeight The same, for height.
 */
internal class BlurPass(
  val program: JsAny,
  val texelStep: JsAny?,
  val gainUniform: JsAny?,
  val gain: Float,
  val smallWidth: Int,
  val smallHeight: Int,
  val coverFbo: JsAny,
  val coverTexture: JsAny,
  val blurFbo: JsAny,
  val blurTexture: JsAny,
) {
  /**
   * Blurs what the cover pass drew into [coverFbo] across and then down, dims it, and draws it into [target] at the
   * [width] by [height] output, which it clears first. Leaves [target] bound and this pass's program in use.
   */
  fun blurCoverOnto(
    gl: WebGl2,
    target: JsAny?,
    width: Int,
    height: Int,
  ) {
    gl.useProgram(program)

    gl.bindFramebuffer(GL_FRAMEBUFFER, blurFbo)
    gl.clear(GL_COLOR_BUFFER_BIT)
    gl.bindTexture(GL_TEXTURE_2D, coverTexture)
    gl.uniform2f(texelStep, 1f / smallWidth, 0f)
    gl.uniform1f(gainUniform, 1f)
    gl.drawArrays(GL_TRIANGLE_STRIP, 0, QUAD_VERTICES)

    gl.viewport(0, 0, width, height)
    gl.bindFramebuffer(GL_FRAMEBUFFER, target)
    gl.clear(GL_COLOR_BUFFER_BIT)
    gl.bindTexture(GL_TEXTURE_2D, blurTexture)
    gl.uniform2f(texelStep, 0f, 1f / smallHeight)
    gl.uniform1f(gainUniform, gain)
    gl.drawArrays(GL_TRIANGLE_STRIP, 0, QUAD_VERTICES)
  }

  /**
   * Deletes every program, texture and framebuffer this pass holds.
   */
  fun release(gl: WebGl2) {
    gl.deleteFramebuffer(coverFbo)
    gl.deleteTexture(coverTexture)
    gl.deleteFramebuffer(blurFbo)
    gl.deleteTexture(blurTexture)
    gl.deleteProgram(program)
  }
}

/**
 * The extra program, textures and framebuffers a blurred fill needs. Built once per export and
 * never touched again outside `BrowserCompositor.drawBackground`, so a [Fill.Solid] export
 * never pays for any of it.
 */
internal fun buildBlurPass(
  gl: WebGl2,
  width: Int,
  height: Int,
  fill: Fill.Blurred,
  buffer: JsAny,
  hdrTransfer: HdrTransfer?,
): BlurPass {
  val program = linkedProgram(gl, FULLSCREEN_VERTEX_SHADER, blurFragmentShader(hdrTransfer))
  gl.useProgram(program)

  gl.bindBuffer(GL_ARRAY_BUFFER, buffer)
  bindQuad(gl, program)

  // The plan, the weights and the tap radius depend only on the fill and the output size, both
  // fixed for the life of the compositor, so they are worked out and uploaded once here rather
  // than every frame.
  val plan = blurPlan(fill.sigmaFor(Size(width, height)), width, height)
  gl.uniform1fv(
    gl.getUniformLocation(program, "uWeights"),
    gaussianWeights(plan.sigma, plan.tapRadius).toFloat32Array(),
  )
  gl.uniform1i(gl.getUniformLocation(program, "uTapRadius"), plan.tapRadius)

  val hdr = hdrTransfer != null
  val coverTexture = createFramebufferTexture(gl, plan.smallWidth, plan.smallHeight, hdr)
  val coverFbo = createComplete(gl, coverTexture)
  val blurTexture = createFramebufferTexture(gl, plan.smallWidth, plan.smallHeight, hdr)
  val blurFbo = createComplete(gl, blurTexture)
  gl.bindFramebuffer(GL_FRAMEBUFFER, null)

  return BlurPass(
    program = program,
    texelStep = gl.getUniformLocation(program, "uTexelStep"),
    gainUniform = gl.getUniformLocation(program, "uGain"),
    // A dim is written against an encoded value, and a kept grade holds linear light, so the
    // gain is raised by the display's own curve there or the background darkens by less than
    // it was asked to.
    gain = if (hdr) linearDimGain(fill.dim) else fill.backgroundGain,
    smallWidth = plan.smallWidth,
    smallHeight = plan.smallHeight,
    coverFbo = coverFbo,
    coverTexture = coverTexture,
    blurFbo = blurFbo,
    blurTexture = blurTexture,
  )
}

// One direction at a time: uTexelStep is the horizontal step for the first pass and the vertical
// step for the second, and uGain only takes effect on the second, once the blur is done spreading.
// The kernel is symmetric, so uWeights only holds the centre weight and one weight per tap radius,
// and each side samples the same texel offset twice, once in each direction.
internal fun blurFragmentShader(transfer: HdrTransfer?): String =
  """
  #version 300 es
  precision ${if (transfer == null) "mediump" else "highp"} float;
  uniform sampler2D uTexSampler;
  uniform vec2 uTexelStep;
  uniform int uTapRadius;
  uniform float uWeights[${MAX_BLUR_TAP_RADIUS + 1}];
  uniform float uGain;
  in vec2 vTexCoord;
  out vec4 outColor;
  void main() {
    vec4 sum = texture(uTexSampler, vTexCoord) * uWeights[0];
    for (int i = 1; i <= uTapRadius; i++) {
      vec2 offset = uTexelStep * float(i);
      sum += texture(uTexSampler, vTexCoord + offset) * uWeights[i];
      sum += texture(uTexSampler, vTexCoord - offset) * uWeights[i];
    }
    outColor = vec4(sum.rgb * uGain, sum.a);
  }
  """.trimIndent()

/**
 * The downscaled resolution a blurred fill's cover and blur passes render at, and the sigma and
 * tap radius the kernel uses there.
 */
internal class BlurPlan(
  val downscale: Int,
  val smallWidth: Int,
  val smallHeight: Int,
  val sigma: Float,
  val tapRadius: Int,
)

/**
 * How a blurred fill's kernel is realised for a [width] by [height] output, given the true
 * [sigma] the fill's contract already resolved to (see [sigmaFor]). This is the one number every
 * backend's blur agrees on, so the same edit reads the same blur wherever it is exported.
 *
 * A large [sigma] is realised in full by blurring a downscaled copy of the frame and letting the
 * last pass's bilinear sampling upsample it back out.
 *
 * [downscale] keeps the downscaled sigma at [DOWNSCALE_SIGMA_TARGET] pixels or under, which keeps
 * the tap radius at three times that. For any sigma [sigmaFor] can produce, this stays well inside
 * [MAX_BLUR_TAP_RADIUS].
 */
internal fun blurPlan(
  sigma: Float,
  width: Int,
  height: Int,
): BlurPlan {
  val downscale = maxOf(1, ceil(sigma / DOWNSCALE_SIGMA_TARGET).toInt())
  val smallWidth = ceil(width.toFloat() / downscale).toInt().coerceAtLeast(1)
  val smallHeight = ceil(height.toFloat() / downscale).toInt().coerceAtLeast(1)
  val smallSigma = sigma / downscale
  val tapRadius = ceil(3f * smallSigma).toInt().coerceIn(0, MAX_BLUR_TAP_RADIUS)
  return BlurPlan(downscale, smallWidth, smallHeight, smallSigma, tapRadius)
}

/**
 * Gaussian weights for [tapRadius] taps either side of the centre, normalised so the whole kernel
 * sums to one. Padded out to [MAX_BLUR_TAP_RADIUS] entries. The shader never reads past
 * [tapRadius].
 */
private fun gaussianWeights(
  sigma: Float,
  tapRadius: Int,
): FloatArray {
  val weights = FloatArray(MAX_BLUR_TAP_RADIUS + 1)
  var total = 0f
  for (i in 0..tapRadius) {
    val weight = exp(-(i * i) / (2f * sigma * sigma))
    weights[i] = weight
    total += if (i == 0) weight else 2f * weight
  }
  for (i in 0..tapRadius) weights[i] /= total
  return weights
}

internal const val MAX_BLUR_TAP_RADIUS: Int = 60
private const val DOWNSCALE_SIGMA_TARGET = 8f

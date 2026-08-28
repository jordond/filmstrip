@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal

import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.transform.internal.backgroundGain
import dev.jordond.filmstrip.transform.internal.sigmaFor
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.math.ceil
import kotlin.math.exp

/**
 * One WebGL pass over a decoded frame: the texture matrix an effect resolved to, a brightness, and
 * a quad sized to letterbox a clip whose aspect does not match the output.
 *
 * The context is created per export and handed back on [release]. A browser holds only about
 * sixteen live contexts and drops the oldest to make room, so an exporter that never releases
 * eventually loses the context another export is still drawing on.
 */
internal class BrowserCompositor private constructor(
  private val canvas: OffscreenCanvas,
  private val gl: WebGl2,
  private val width: Int,
  private val height: Int,
  private val program: JsAny,
  private val buffer: JsAny,
  private val texture: JsAny,
  private val texMatrix: JsAny?,
  private val quadScale: JsAny?,
  private val brightness: JsAny?,
  private val blur: BlurPass?,
) {
  private var current: RenderedClip? = null

  /**
   * Sets the state one clip draws with. Everything here is constant for the length of a clip.
   *
   * The clip itself is kept around, not just its uniforms, because [draw] needs its cover geometry
   * and whether it has bars at all once the fill is blurred.
   */
  fun clip(clip: RenderedClip) {
    current = clip
    gl.uniformMatrix3fv(texMatrix, false, clip.matrix.toFloat32Array())
    gl.uniform2f(quadScale, clip.quadHalfW, clip.quadHalfH)
    gl.uniform1f(brightness, clip.brightness)
  }

  /**
   * Draws one decoded frame into the framebuffer.
   *
   * The clear is per frame rather than per export: a clip narrower than the one before it would
   * otherwise letterbox onto the previous clip's pixels. The blend composites the frame over
   * whatever the fill cleared to, so a bar keeps the fill's own colour whatever brightness the clip
   * or the composition sets. That is a requirement every backend honours, not a side effect of this
   * one's single pass, so a second pass added here later still has to draw after the clear rather
   * than fold into it.
   *
   * A clip with bars under a blurred fill draws its background first: the same frame, cover-scaled
   * and blurred, straight into the canvas, before the sharp contained quad goes on top of it. A
   * clip with no bars, or a fill with nothing to blur, skips straight to the clear.
   *
   * `UNPACK_FLIP_Y_WEBGL` is what makes the upload match the +Y-up texture convention every
   * resolver already lowers to. Without it the source's top row lands at `t = 0`, which the shader
   * maps to the bottom of the frame, and every export comes out upside down.
   */
  fun draw(frame: VideoSample) {
    val clip = checkNotNull(current) { "clip() must run before draw()" }
    gl.bindTexture(GL_TEXTURE_2D, texture)
    gl.pixelStorei(GL_UNPACK_FLIP_Y_WEBGL, 1)
    val image = frame.toVideoFrame()
    try {
      gl.texImage2D(GL_TEXTURE_2D, 0, GL_RGBA, GL_RGBA, GL_UNSIGNED_BYTE, image)
    } finally {
      image.close()
    }

    if (blur != null && clip.hasBars) {
      drawBackground(blur, clip)
      // The cover pass above reused this program's quad uniforms for its own geometry. Put the
      // clip's contained geometry back before the sharp draw below.
      gl.useProgram(program)
      gl.uniform2f(quadScale, clip.quadHalfW, clip.quadHalfH)
    } else {
      gl.bindFramebuffer(GL_FRAMEBUFFER, null)
      gl.clear(GL_COLOR_BUFFER_BIT)
    }

    gl.bindFramebuffer(GL_FRAMEBUFFER, null)
    gl.bindTexture(GL_TEXTURE_2D, texture)
    gl.drawArrays(GL_TRIANGLE_STRIP, 0, QUAD_VERTICES)
  }

  /**
   * The letterboxed clip's background: the frame scaled to cover the output, blurred across two
   * passes and dimmed, drawn straight into the canvas ahead of the sharp foreground.
   *
   * The background is the clip's own pixels, so it is drawn with the clip's brightness still set.
   * Every other backend splits the frame for its blur after the effect chain has run, and dimming
   * only the sharp copy would leave a bright halo around a dimmed frame.
   *
   * The cover and horizontal passes render at [BlurPass.smallWidth] by [BlurPass.smallHeight]
   * rather than the output size. See [blurPlan] for why. The vertical pass renders at the output
   * size again, so the small, blurred texture is upsampled by the hardware's own bilinear
   * filtering as it stretches across the bigger viewport, finishing the smoothing for free.
   */
  private fun drawBackground(
    blur: BlurPass,
    clip: RenderedClip,
  ) {
    gl.useProgram(program)
    gl.viewport(0, 0, blur.smallWidth, blur.smallHeight)
    gl.bindFramebuffer(GL_FRAMEBUFFER, blur.coverFbo)
    gl.clear(GL_COLOR_BUFFER_BIT)
    gl.bindTexture(GL_TEXTURE_2D, texture)
    gl.uniform2f(quadScale, clip.coverHalfW, clip.coverHalfH)
    gl.drawArrays(GL_TRIANGLE_STRIP, 0, QUAD_VERTICES)

    gl.useProgram(blur.program)

    gl.bindFramebuffer(GL_FRAMEBUFFER, blur.blurFbo)
    gl.clear(GL_COLOR_BUFFER_BIT)
    gl.bindTexture(GL_TEXTURE_2D, blur.coverTexture)
    gl.uniform2f(blur.texelStep, 1f / blur.smallWidth, 0f)
    gl.uniform1f(blur.gainUniform, 1f)
    gl.drawArrays(GL_TRIANGLE_STRIP, 0, QUAD_VERTICES)

    gl.viewport(0, 0, width, height)
    gl.bindFramebuffer(GL_FRAMEBUFFER, null)
    gl.clear(GL_COLOR_BUFFER_BIT)
    gl.bindTexture(GL_TEXTURE_2D, blur.blurTexture)
    gl.uniform2f(blur.texelStep, 0f, 1f / blur.smallHeight)
    gl.uniform1f(blur.gainUniform, blur.gain)
    gl.drawArrays(GL_TRIANGLE_STRIP, 0, QUAD_VERTICES)
  }

  /**
   * The composited frame, stamped for the output timeline. Timestamps are seconds here, which is
   * what mediabunny takes.
   */
  fun snapshot(
    timestampUs: Double,
    durationUs: Double,
  ): VideoSample =
    VideoSample(
      canvas,
      JsOptions()
        .put("timestamp", timestampUs / MICROS_PER_SECOND)
        .put("duration", durationUs / MICROS_PER_SECOND)
        .build(),
    )

  fun release() {
    blur?.let {
      gl.deleteFramebuffer(it.coverFbo)
      gl.deleteTexture(it.coverTexture)
      gl.deleteFramebuffer(it.blurFbo)
      gl.deleteTexture(it.blurTexture)
      gl.deleteProgram(it.program)
    }
    gl.deleteTexture(texture)
    gl.deleteBuffer(buffer)
    gl.deleteProgram(program)
    gl.getExtension("WEBGL_lose_context")?.loseContext()
  }

  companion object {
    /**
     * Builds a compositor for a [width] by [height] output, filled with [fill] wherever no clip's
     * pixels land.
     */
    fun create(
      width: Int,
      height: Int,
      fill: Fill,
    ): BrowserCompositor {
      val canvas = OffscreenCanvas(width, height)
      val gl =
        canvas.getContext(
          "webgl2",
          JsOptions()
            .put("alpha", true)
            .put("premultipliedAlpha", true)
            .build(),
        ) ?: throw BrowserExportFailure(NO_CONTEXT)

      val program = link(gl, VERTEX_SHADER, FRAGMENT_SHADER)
      gl.useProgram(program)

      val buffer = gl.createBuffer() ?: throw BrowserExportFailure(NO_BUFFER)
      gl.bindBuffer(GL_ARRAY_BUFFER, buffer)
      gl.bufferData(GL_ARRAY_BUFFER, UNIT_QUAD.toFloat32Array(), GL_STATIC_DRAW)
      val position = gl.getAttribLocation(program, "aPosition")
      gl.enableVertexAttribArray(position)
      gl.vertexAttribPointer(position, 2, GL_FLOAT, false, 0, 0)

      val texture = gl.createTexture() ?: throw BrowserExportFailure(NO_TEXTURE)
      gl.bindTexture(GL_TEXTURE_2D, texture)
      gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
      gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
      gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
      gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)

      gl.viewport(0, 0, width, height)
      val (clearRed, clearGreen, clearBlue) = fill.clearColor()
      gl.clearColor(clearRed, clearGreen, clearBlue, 1f)
      // Source-over onto the fill's opaque clear, which is what puts the fill's colour in a
      // letterbox bar. The alpha factors keep the result opaque, which the context's premultiplied
      // alpha needs and which matches an encoder that discards alpha anyway.
      gl.enable(GL_BLEND)
      gl.blendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA)

      val blur = if (fill is Fill.Blurred) buildBlurPass(gl, width, height, fill, buffer) else null
      // buildBlurPass leaves its own program bound to upload the blur's uniforms. Put the main one
      // back so clip() and draw() find what they expect.
      gl.useProgram(program)

      return BrowserCompositor(
        canvas = canvas,
        gl = gl,
        width = width,
        height = height,
        program = program,
        buffer = buffer,
        texture = texture,
        texMatrix = gl.getUniformLocation(program, "uTexMatrix"),
        quadScale = gl.getUniformLocation(program, "uQuadScale"),
        brightness = gl.getUniformLocation(program, "uBrightness"),
        blur = blur,
      )
    }

    /**
     * The extra program, textures and framebuffers a blurred fill needs. Built once per export and
     * never touched again outside [BrowserCompositor.drawBackground], so a [Fill.Solid] export
     * never pays for any of it.
     */
    private fun buildBlurPass(
      gl: WebGl2,
      width: Int,
      height: Int,
      fill: Fill.Blurred,
      buffer: JsAny,
    ): BlurPass {
      val program = link(gl, BLUR_VERTEX_SHADER, BLUR_FRAGMENT_SHADER)
      gl.useProgram(program)

      gl.bindBuffer(GL_ARRAY_BUFFER, buffer)
      val position = gl.getAttribLocation(program, "aPosition")
      gl.enableVertexAttribArray(position)
      gl.vertexAttribPointer(position, 2, GL_FLOAT, false, 0, 0)

      // The plan, the weights and the tap radius depend only on the fill and the output size, both
      // fixed for the life of the compositor, so they are worked out and uploaded once here rather
      // than every frame.
      val plan = blurPlan(fill.sigmaFor(Size(width, height)), width, height)
      gl.uniform1fv(
        gl.getUniformLocation(program, "uWeights"),
        gaussianWeights(plan.sigma, plan.tapRadius).toFloat32Array(),
      )
      gl.uniform1i(gl.getUniformLocation(program, "uTapRadius"), plan.tapRadius)

      val coverTexture = createFramebufferTexture(gl, plan.smallWidth, plan.smallHeight)
      val coverFbo = createComplete(gl, coverTexture)
      val blurTexture = createFramebufferTexture(gl, plan.smallWidth, plan.smallHeight)
      val blurFbo = createComplete(gl, blurTexture)
      gl.bindFramebuffer(GL_FRAMEBUFFER, null)

      return BlurPass(
        program = program,
        texelStep = gl.getUniformLocation(program, "uTexelStep"),
        gainUniform = gl.getUniformLocation(program, "uGain"),
        gain = fill.backgroundGain,
        smallWidth = plan.smallWidth,
        smallHeight = plan.smallHeight,
        coverFbo = coverFbo,
        coverTexture = coverTexture,
        blurFbo = blurFbo,
        blurTexture = blurTexture,
      )
    }

    /**
     * An empty texture sized [width] by [height], for a framebuffer's colour attachment.
     */
    private fun createFramebufferTexture(
      gl: WebGl2,
      width: Int,
      height: Int,
    ): JsAny {
      val texture = gl.createTexture() ?: throw BrowserExportFailure(NO_TEXTURE)
      gl.bindTexture(GL_TEXTURE_2D, texture)
      gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
      gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
      gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
      gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
      gl.texImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
      return texture
    }

    /**
     * A framebuffer with [colorTexture] attached, refused rather than handed back incomplete.
     */
    private fun createComplete(
      gl: WebGl2,
      colorTexture: JsAny,
    ): JsAny {
      val framebuffer = gl.createFramebuffer() ?: throw BrowserExportFailure(NO_FRAMEBUFFER)
      gl.bindFramebuffer(GL_FRAMEBUFFER, framebuffer)
      gl.framebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTexture, 0)
      if (gl.checkFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        throw BrowserExportFailure(NO_FRAMEBUFFER)
      }
      return framebuffer
    }

    private fun link(
      gl: WebGl2,
      vertexSource: String,
      fragmentSource: String,
    ): JsAny {
      val vertex = compile(gl, GL_VERTEX_SHADER, vertexSource)
      val fragment = compile(gl, GL_FRAGMENT_SHADER, fragmentSource)
      val program = gl.createProgram() ?: throw BrowserExportFailure(NO_PROGRAM)
      gl.attachShader(program, vertex)
      gl.attachShader(program, fragment)
      gl.linkProgram(program)
      gl.deleteShader(vertex)
      gl.deleteShader(fragment)
      if (!gl.getProgramParameter(program, GL_LINK_STATUS)) {
        throw BrowserExportFailure("The compositor's program did not link: ${gl.getProgramInfoLog(program)}")
      }
      return program
    }

    private fun compile(
      gl: WebGl2,
      type: Int,
      source: String,
    ): JsAny {
      val shader = gl.createShader(type) ?: throw BrowserExportFailure(NO_SHADER)
      gl.shaderSource(shader, source)
      gl.compileShader(shader)
      if (!gl.getShaderParameter(shader, GL_COMPILE_STATUS)) {
        throw BrowserExportFailure("The compositor's shader did not compile: ${gl.getShaderInfoLog(shader)}")
      }
      return shader
    }

    private const val NO_CONTEXT = "The page gave out no WebGL2 context, so there is nothing to composite on."
    private const val NO_PROGRAM = "WebGL2 refused to create a program."
    private const val NO_SHADER = "WebGL2 refused to create a shader."
    private const val NO_BUFFER = "WebGL2 refused to create a vertex buffer."
    private const val NO_TEXTURE = "WebGL2 refused to create a texture."
    private const val NO_FRAMEBUFFER = "WebGL2 refused to give the fill's background pass a complete framebuffer."
  }
}

/**
 * The extra GL objects a blurred fill needs: one program shared by the cover, horizontal blur and
 * vertical blur passes, the two framebuffers those passes render into, and the textures backing
 * them.
 *
 * @property smallWidth The width [coverFbo] and [blurFbo] render at, from [blurPlan]. Narrower than
 *   the output whenever the blur is heavy enough to need downscaling.
 * @property smallHeight The same, for height.
 */
private class BlurPass(
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
)

// The quad is a fixed unit square and the letterbox is a uniform, so the texture coordinates always
// span the whole frame. Deriving them from a shrunken quad instead would sample a centre crop out
// of every clip that had to be letterboxed.
private val VERTEX_SHADER =
  """
  #version 300 es
  in vec2 aPosition;
  uniform mat3 uTexMatrix;
  uniform vec2 uQuadScale;
  out vec2 vTexCoord;
  void main() {
    vec2 uv = aPosition * 0.5 + 0.5;
    vTexCoord = (uTexMatrix * vec3(uv, 1.0)).xy;
    gl_Position = vec4(aPosition * uQuadScale, 0.0, 1.0);
  }
  """.trimIndent()

private val FRAGMENT_SHADER =
  """
  #version 300 es
  precision mediump float;
  uniform sampler2D uTexSampler;
  uniform float uBrightness;
  in vec2 vTexCoord;
  out vec4 outColor;
  void main() {
    vec4 c = texture(uTexSampler, vTexCoord);
    outColor = vec4(clamp(c.rgb * uBrightness, 0.0, 1.0), c.a);
  }
  """.trimIndent()

// A full-screen quad with no letterbox and no texture matrix: both blur passes read a framebuffer
// that already covers the whole viewport, so there is nothing left to scale or warp.
private val BLUR_VERTEX_SHADER =
  """
  #version 300 es
  in vec2 aPosition;
  out vec2 vTexCoord;
  void main() {
    vTexCoord = aPosition * 0.5 + 0.5;
    gl_Position = vec4(aPosition, 0.0, 1.0);
  }
  """.trimIndent()

// One direction at a time: uTexelStep is the horizontal step for the first pass and the vertical
// step for the second, and uGain only takes effect on the second, once the blur is done spreading.
// The kernel is symmetric, so uWeights only holds the centre weight and one weight per tap radius,
// and each side samples the same texel offset twice, once in each direction.
private val BLUR_FRAGMENT_SHADER =
  """
  #version 300 es
  precision mediump float;
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
 * The clear colour [Fill.Solid] paints outright. A blurred fill has no single colour of its own, so
 * every pixel it reaches is drawn over before the frame is handed back, and this is only what a
 * stray uncovered one would fall back to.
 */
private fun Fill.clearColor(): FloatArray =
  when (this) {
    is Fill.Solid -> {
      floatArrayOf(
        ((color shr RED_SHIFT) and BYTE_MASK) / MAX_CHANNEL,
        ((color shr GREEN_SHIFT) and BYTE_MASK) / MAX_CHANNEL,
        (color and BYTE_MASK) / MAX_CHANNEL,
      )
    }
    is Fill.Blurred -> {
      floatArrayOf(0f, 0f, 0f)
    }
  }

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
 * Rather than shrinking [sigma] to fit a tap budget, a large one is instead realised by blurring a
 * downscaled copy of the frame and letting the last pass's bilinear sampling upsample it back out,
 * which is the standard way to afford a heavy blur at all.
 *
 * [downscale] keeps the downscaled sigma at [DOWNSCALE_SIGMA_TARGET] pixels or under, which keeps
 * the tap radius at three times that. For any sigma [sigmaFor] can produce, this stays well inside
 * [MAX_BLUR_TAP_RADIUS], which remains a real backstop rather than something this reaches in
 * practice.
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

private val UNIT_QUAD = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)

private const val QUAD_VERTICES = 4

private const val MAX_BLUR_TAP_RADIUS = 60
private const val DOWNSCALE_SIGMA_TARGET = 8f

private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val BYTE_MASK = 0xFF
private const val MAX_CHANNEL = 255f

private const val GL_COLOR_BUFFER_BIT = 0x4000
private const val GL_TEXTURE_2D = 0x0DE1
private const val GL_RGBA = 0x1908
private const val GL_UNSIGNED_BYTE = 0x1401
private const val GL_TEXTURE_MAG_FILTER = 0x2800
private const val GL_TEXTURE_MIN_FILTER = 0x2801
private const val GL_TEXTURE_WRAP_S = 0x2802
private const val GL_TEXTURE_WRAP_T = 0x2803
private const val GL_LINEAR = 0x2601
private const val GL_CLAMP_TO_EDGE = 0x812F
private const val GL_ARRAY_BUFFER = 0x8892
private const val GL_STATIC_DRAW = 0x88E4
private const val GL_FLOAT = 0x1406
private const val GL_TRIANGLE_STRIP = 0x0005
private const val GL_FRAGMENT_SHADER = 0x8B30
private const val GL_VERTEX_SHADER = 0x8B31
private const val GL_COMPILE_STATUS = 0x8B81
private const val GL_LINK_STATUS = 0x8B82
private const val GL_BLEND = 0x0BE2
private const val GL_ONE = 1
private const val GL_SRC_ALPHA = 0x0302
private const val GL_ONE_MINUS_SRC_ALPHA = 0x0303
private const val GL_UNPACK_FLIP_Y_WEBGL = 0x9240
private const val GL_FRAMEBUFFER = 0x8D40
private const val GL_COLOR_ATTACHMENT0 = 0x8CE0
private const val GL_FRAMEBUFFER_COMPLETE = 0x8CD5

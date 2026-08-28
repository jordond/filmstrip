package dev.jordond.filmstrip.media3.internal

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.linearDimGain
import dev.jordond.filmstrip.transform.internal.backgroundGain
import dev.jordond.filmstrip.transform.internal.containScale
import dev.jordond.filmstrip.transform.internal.coverScale
import dev.jordond.filmstrip.transform.internal.sigmaFor
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp

/**
 * Scales a clip's frame onto the output frame, filling the margin with a blurred, dimmed copy of
 * the same frame scaled to cover it.
 *
 * The frame is drawn twice: once scaled up and centre-cropped to cover the whole output, blurred
 * and dimmed, and once more scaled to fit inside it, sharp, on top of that. Presentation then
 * receives a frame already at the output size, with nothing left to letterbox.
 *
 * @property fill The radius and the dim the background is drawn at.
 * @property outputSize The composition's output frame, in pixels.
 */
internal class FillCoverBlur(
  private val fill: Fill.Blurred,
  private val outputSize: Size,
) : GlEffect {
  override fun toGlShaderProgram(
    context: Context,
    useHdr: Boolean,
  ): GlShaderProgram = FillCoverBlurShaderProgram(useHdr, fill, outputSize)

  override fun isNoOp(
    inputWidth: Int,
    inputHeight: Int,
  ): Boolean = abs(inputWidth.toFloat() / inputHeight.toFloat() - outputSize.aspect) < ASPECT_EPSILON
}

/**
 * Runs [FillCoverBlur]'s three passes.
 *
 * The cover pass and the blur run at a fraction of the output size, so that whatever [Fill.Blurred]
 * asks for, the taps a Gaussian needs stay fixed. The composite pass upsamples back to the output
 * size with the texture unit's own bilinear filtering, and draws the sharp frame over that. The
 * composite needs the frame both blurred and untouched at once, which is why this implements
 * [GlShaderProgram] directly instead of the single-pass [androidx.media3.effect.BaseGlShaderProgram].
 */
private class FillCoverBlurShaderProgram(
  private val useHdr: Boolean,
  fill: Fill.Blurred,
  private val outputSize: Size,
) : GlShaderProgram {
  private var inputListener: GlShaderProgram.InputListener = object : GlShaderProgram.InputListener {}
  private var outputListener: GlShaderProgram.OutputListener = object : GlShaderProgram.OutputListener {}
  private var errorListener = GlShaderProgram.ErrorListener {}
  private var errorListenerExecutor: Executor = Executor { it.run() }

  // Fixed for the life of this program: the output frame never changes size mid-export, so neither
  // does the blur's kernel or the small size it runs at.
  private val sigma = fill.sigmaFor(outputSize)
  private val downscale = downscaleFor(sigma)
  private val smallWidth = ceilDiv(outputSize.width, downscale)
  private val smallHeight = ceilDiv(outputSize.height, downscale)
  private val weights = gaussianWeights(tapRadiusFor(sigma / downscale), sigma / downscale)

  private val coverProgram =
    try {
      GlProgram(VERTEX_SHADER, COVER_FRAGMENT_SHADER).apply { bindFrameAttribute() }
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException(e)
    }
  private val blurProgram =
    try {
      GlProgram(VERTEX_SHADER, horizontalBlurFragmentShader(weights, 1f / smallWidth)).apply { bindFrameAttribute() }
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException(e)
    }
  private val compositeProgram =
    try {
      GlProgram(VERTEX_SHADER, compositeFragmentShader(weights, 1f / smallHeight, dimGain(useHdr, fill))).apply {
        bindFrameAttribute()
      }
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException(e)
    }

  private var coverTexture = GlTextureInfo.UNSET
  private var blurTexture = GlTextureInfo.UNSET
  private var outputTexture = GlTextureInfo.UNSET

  private var lastInputSize: Size? = null
  private var coverScaleUniform = floatArrayOf(1f, 1f)
  private var coverOffsetUniform = floatArrayOf(0f, 0f)
  private var sharpMinUniform = floatArrayOf(0f, 0f)
  private var sharpMaxUniform = floatArrayOf(1f, 1f)
  private var outputTextureInUse = false

  override fun setInputListener(inputListener: GlShaderProgram.InputListener) {
    this.inputListener = inputListener
    if (!outputTextureInUse) inputListener.onReadyToAcceptInputFrame()
  }

  override fun setOutputListener(outputListener: GlShaderProgram.OutputListener) {
    this.outputListener = outputListener
  }

  override fun setErrorListener(
    executor: Executor,
    errorListener: GlShaderProgram.ErrorListener,
  ) {
    this.errorListenerExecutor = executor
    this.errorListener = errorListener
  }

  override fun queueInputFrame(
    glObjectsProvider: GlObjectsProvider,
    inputTexture: GlTextureInfo,
    presentationTimeUs: Long,
  ) {
    check(!outputTextureInUse) { "Release the prior output frame before queuing another." }
    try {
      ensureConfigured(glObjectsProvider, inputTexture.width, inputTexture.height)
      outputTextureInUse = true

      drawCover(inputTexture)
      drawHorizontalBlur()
      drawComposite(inputTexture)

      inputListener.onInputFrameProcessed(inputTexture)
      outputListener.onOutputFrameAvailable(outputTexture, presentationTimeUs)
    } catch (e: GlUtil.GlException) {
      errorListenerExecutor.execute {
        errorListener.onError(VideoFrameProcessingException.from(e, presentationTimeUs))
      }
    }
  }

  override fun releaseOutputFrame(outputTexture: GlTextureInfo) {
    outputTextureInUse = false
    inputListener.onReadyToAcceptInputFrame()
  }

  override fun signalEndOfCurrentInputStream() {
    outputListener.onCurrentOutputStreamEnded()
  }

  override fun flush() {
    outputTextureInUse = false
    inputListener.onFlush()
    inputListener.onReadyToAcceptInputFrame()
  }

  override fun release() {
    try {
      coverTexture.release()
      blurTexture.release()
      outputTexture.release()
      coverProgram.delete()
      blurProgram.delete()
      compositeProgram.delete()
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException(e)
    }
  }

  /**
   * Allocates the three textures the first time a frame arrives, and updates the cover and sharp
   * geometry whenever the source clip's own frame size changes.
   */
  private fun ensureConfigured(
    glObjectsProvider: GlObjectsProvider,
    inputWidth: Int,
    inputHeight: Int,
  ) {
    if (coverTexture == GlTextureInfo.UNSET) {
      coverTexture = allocateTexture(glObjectsProvider, smallWidth, smallHeight)
      blurTexture = allocateTexture(glObjectsProvider, smallWidth, smallHeight)
      outputTexture = allocateTexture(glObjectsProvider, outputSize.width, outputSize.height)
    }

    val inputSize = Size(inputWidth, inputHeight)
    if (inputSize == lastInputSize) return
    lastInputSize = inputSize

    val cover = coverScale(inputSize, outputSize)
    val coverWidthFraction = outputSize.width / (cover * inputSize.width)
    val coverHeightFraction = outputSize.height / (cover * inputSize.height)
    coverScaleUniform = floatArrayOf(coverWidthFraction, coverHeightFraction)
    coverOffsetUniform = floatArrayOf((1f - coverWidthFraction) / 2f, (1f - coverHeightFraction) / 2f)

    val contain = containScale(inputSize, outputSize)
    val sharpWidthFraction = (inputSize.width * contain) / outputSize.width
    val sharpHeightFraction = (inputSize.height * contain) / outputSize.height
    val sharpMinX = (1f - sharpWidthFraction) / 2f
    val sharpMinY = (1f - sharpHeightFraction) / 2f
    sharpMinUniform = floatArrayOf(sharpMinX, sharpMinY)
    sharpMaxUniform = floatArrayOf(sharpMinX + sharpWidthFraction, sharpMinY + sharpHeightFraction)
  }

  private fun drawCover(inputTexture: GlTextureInfo) {
    GlUtil.focusFramebufferUsingCurrentContext(coverTexture.fboId, coverTexture.width, coverTexture.height)
    GlUtil.clearFocusedBuffers()

    coverProgram.use()
    coverProgram.setSamplerTexIdUniform("uTexSampler", inputTexture.texId, 0)
    coverProgram.setFloatsUniform("uCoverScale", coverScaleUniform)
    coverProgram.setFloatsUniform("uCoverOffset", coverOffsetUniform)
    coverProgram.bindAttributesAndUniforms()
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    GlUtil.checkGlError()
  }

  private fun drawHorizontalBlur() {
    GlUtil.focusFramebufferUsingCurrentContext(blurTexture.fboId, blurTexture.width, blurTexture.height)
    GlUtil.clearFocusedBuffers()

    blurProgram.use()
    blurProgram.setSamplerTexIdUniform("uTexSampler", coverTexture.texId, 0)
    blurProgram.bindAttributesAndUniforms()
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    GlUtil.checkGlError()
  }

  // The blur texture is a fraction of the output size, and sampling it here with the linear
  // filtering GlUtil already binds it with is what upsamples it back, smoothing the downscale away
  // rather than leaving it blocky.
  private fun drawComposite(inputTexture: GlTextureInfo) {
    GlUtil.focusFramebufferUsingCurrentContext(outputTexture.fboId, outputTexture.width, outputTexture.height)
    GlUtil.clearFocusedBuffers()

    compositeProgram.use()
    compositeProgram.setSamplerTexIdUniform("uBlurSampler", blurTexture.texId, 0)
    compositeProgram.setSamplerTexIdUniform("uSharpSampler", inputTexture.texId, 1)
    compositeProgram.setFloatsUniform("uSharpMin", sharpMinUniform)
    compositeProgram.setFloatsUniform("uSharpMax", sharpMaxUniform)
    compositeProgram.bindAttributesAndUniforms()
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    GlUtil.checkGlError()
  }

  private fun allocateTexture(
    glObjectsProvider: GlObjectsProvider,
    width: Int,
    height: Int,
  ): GlTextureInfo {
    val texId = GlUtil.createTexture(width, height, useHdr)
    return glObjectsProvider.createBuffersForTexture(texId, width, height)
  }

  private fun GlProgram.bindFrameAttribute() {
    setBufferAttribute(
      "aFramePosition",
      GlUtil.getNormalizedCoordinateBounds(),
      GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
    )
  }
}

/**
 * How far the blur runs below the output size, so a wide [sigma] costs no more taps than a narrow
 * one.
 *
 * `sigma / downscale` never exceeds eight: `downscale` is `sigma / 8` rounded up, so dividing
 * `sigma` by it can only bring the result back down to eight or under.
 */
internal fun downscaleFor(sigma: Float): Int = maxOf(1, ceil(sigma / DOWNSCALE_TARGET_SIGMA).toInt())

/**
 * How many taps on either side of the centre a Gaussian of this standard deviation needs.
 *
 * Three standard deviations either side keeps 99.7% of the kernel's weight. Capped defensively:
 * [downscaleFor] already keeps `sigma` here at eight or under, which never asks for more than
 * `ceil(3 * 8) = 24`, six taps under the cap.
 */
internal fun tapRadiusFor(sigma: Float): Int = minOf(ceil(TAP_STANDARD_DEVIATIONS * sigma).toInt(), MAX_TAP_RADIUS)

private fun ceilDiv(
  value: Int,
  divisor: Int,
): Int = ceil(value.toFloat() / divisor).toInt().coerceAtLeast(1)

/**
 * A discrete Gaussian kernel [2 * tapRadius + 1] taps wide, weights normalised to sum to one.
 */
private fun gaussianWeights(
  tapRadius: Int,
  sigma: Float,
): FloatArray {
  val weights = FloatArray(2 * tapRadius + 1)
  var sum = 0f
  for (i in weights.indices) {
    val x = (i - tapRadius).toFloat()
    val weight = exp(-(x * x) / (2f * sigma * sigma))
    weights[i] = weight
    sum += weight
  }
  for (i in weights.indices) weights[i] /= sum
  return weights
}

// GlProgram's uniform binding only ever uploads one element per name, so a tap count known only at
// construction time cannot travel as a `float[]` uniform. Each tap's weight and offset are written
// into the fragment shader's own source as literals instead, one term per tap.

private fun horizontalBlurFragmentShader(
  weights: FloatArray,
  texelStep: Float,
): String {
  val center = weights.size / 2
  val terms =
    weights.mapIndexed { index, weight ->
      val offset = (index - center) * texelStep
      "texture2D(uTexSampler, vTexSamplingCoord + vec2(${offset.glsl()}, 0.0)) * ${weight.glsl()}"
    }
  return """
    #version 100
    precision highp float;
    uniform sampler2D uTexSampler;
    varying vec2 vTexSamplingCoord;
    void main() {
      gl_FragColor = ${terms.joinToString(" +\n        ")};
    }
    """.trimIndent()
}

private fun compositeFragmentShader(
  weights: FloatArray,
  texelStep: Float,
  gain: Float,
): String {
  val center = weights.size / 2
  val terms =
    weights.mapIndexed { index, weight ->
      val offset = (index - center) * texelStep
      "texture2D(uBlurSampler, vTexSamplingCoord + vec2(0.0, ${offset.glsl()})) * ${weight.glsl()}"
    }
  return """
    #version 100
    precision highp float;
    uniform sampler2D uBlurSampler;
    uniform sampler2D uSharpSampler;
    uniform vec2 uSharpMin;
    uniform vec2 uSharpMax;
    varying vec2 vTexSamplingCoord;
    void main() {
      vec4 background = ${terms.joinToString(" +\n        ")};
      if (vTexSamplingCoord.x >= uSharpMin.x && vTexSamplingCoord.x <= uSharpMax.x &&
          vTexSamplingCoord.y >= uSharpMin.y && vTexSamplingCoord.y <= uSharpMax.y) {
        vec2 sharpUv = (vTexSamplingCoord - uSharpMin) / (uSharpMax - uSharpMin);
        gl_FragColor = texture2D(uSharpSampler, sharpUv);
      } else {
        gl_FragColor = vec4(background.rgb * ${gain.glsl()}, 1.0);
      }
    }
    """.trimIndent()
}

/**
 * What the background's channels are multiplied by in the space media3 hands this pass.
 *
 * SDR channels arrive encoded, which is the space the gain is defined in. HDR channels arrive as
 * linear light, so the same authored dim has to be raised by a display gamma to land at the same
 * brightness rather than several times darker.
 */
private fun dimGain(
  useHdr: Boolean,
  fill: Fill.Blurred,
): Float = if (useHdr) linearDimGain(fill.dim) else fill.backgroundGain

private fun Float.glsl(): String {
  val text = toString()
  return if ('.' in text || 'e' in text || 'E' in text) text else "$text.0"
}

private val VERTEX_SHADER =
  """
  #version 100
  attribute vec4 aFramePosition;
  varying vec2 vTexSamplingCoord;
  void main() {
    gl_Position = aFramePosition;
    vTexSamplingCoord = aFramePosition.xy * 0.5 + 0.5;
  }
  """.trimIndent()

private val COVER_FRAGMENT_SHADER =
  """
  #version 100
  precision highp float;
  uniform sampler2D uTexSampler;
  uniform vec2 uCoverScale;
  uniform vec2 uCoverOffset;
  varying vec2 vTexSamplingCoord;
  void main() {
    vec2 uv = vTexSamplingCoord * uCoverScale + uCoverOffset;
    gl_FragColor = texture2D(uTexSampler, uv);
  }
  """.trimIndent()

private const val DOWNSCALE_TARGET_SIGMA = 8f
private const val TAP_STANDARD_DEVIATIONS = 3f
private const val MAX_TAP_RADIUS = 32

private const val ASPECT_EPSILON = 0.001f

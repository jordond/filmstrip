package dev.jordond.filmstrip.media3.internal.fill

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import dev.jordond.filmstrip.effects.color.HdrColorMatrixUniforms
import dev.jordond.filmstrip.effects.color.IDENTITY_GRADE_LIGHT_GLSL
import dev.jordond.filmstrip.effects.media3SignalUniforms
import dev.jordond.filmstrip.effects.spatial.SpatialBlur
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media3.internal.PlacedFrame
import dev.jordond.filmstrip.transform.internal.geometry.PixelRect
import dev.jordond.filmstrip.transform.internal.geometry.backgroundGain
import dev.jordond.filmstrip.transform.internal.geometry.blurPlan
import dev.jordond.filmstrip.transform.internal.geometry.coverScale
import dev.jordond.filmstrip.transform.internal.geometry.inWholePixelsOf
import java.util.concurrent.Executor

/**
 * Places a clip's frame in the output frame at [rect], filling the rest with a blurred, dimmed copy of the same frame
 * scaled to cover it.
 *
 * The frame is drawn twice: once scaled up and centre-cropped to cover the whole output, blurred and dimmed, and once
 * more at [rect], sharp, on top of that. The rect lands on whole pixels the way a [PlacedFrame] does.
 *
 * @property fill The radius and the dim the background is drawn at.
 * @property outputSize The composition's output frame, in pixels.
 * @property rect Where the sharp frame lands, as a fraction of the output frame.
 * @property transfer The grade the plan keeps, which an HDR frame is blurred on the SDR signal of, or null when it
 * writes SDR.
 */
internal class FillCoverBlur(
  private val fill: Fill.Blurred,
  private val outputSize: Size,
  private val rect: NormalizedRect,
  private val transfer: HdrTransfer? = null,
) : GlEffect {
  override fun toGlShaderProgram(
    context: Context,
    useHdr: Boolean,
  ): GlShaderProgram {
    val domain =
      when {
        !useHdr -> null
        transfer != null -> transfer.media3SignalUniforms
        else -> throw VideoFrameProcessingException(
          "A blurred fill keeps no grade, and this frame processor runs in HDR.",
        )
      }
    return FillCoverBlurShaderProgram(useHdr, fill, outputSize, rect.inWholePixelsOf(outputSize), domain)
  }

  // Compared by value so a preview can tell a re-lowering that changed this pass from one that
  // produced the same pass again.
  override fun equals(other: Any?): Boolean =
    this === other || (
      other is FillCoverBlur &&
        fill == other.fill &&
        outputSize == other.outputSize &&
        rect == other.rect &&
        transfer == other.transfer
    )

  override fun hashCode(): Int =
    HASH * (HASH * (HASH * fill.hashCode() + outputSize.hashCode()) + rect.hashCode()) + transfer.hashCode()

  private companion object {
    const val HASH = 31
  }
}

/**
 * Runs [FillCoverBlur]'s passes.
 *
 * The cover pass draws the frame scaled to cover the output at the output size. The fill's [blurPlan] then boxes that
 * down with [SpatialBlur.BOX_DOWN_GLSL] and blurs it across and up with [SpatialBlur.GAUSSIAN_GLSL] at the plan's
 * size. The composite pass reads the blur back up to the output size with the texture unit's own linear filtering,
 * dims it, and draws the sharp frame over that. A frame holding the light of a kept grade is carried to the SDR signal
 * it encodes to on the way into the box, so the blur and the dim run on that signal, and back on the way out. The
 * composite needs the frame both blurred and untouched at once, which is why this implements [GlShaderProgram]
 * directly instead of the single-pass [androidx.media3.effect.BaseGlShaderProgram].
 */
private class FillCoverBlurShaderProgram(
  private val useHdr: Boolean,
  fill: Fill.Blurred,
  private val outputSize: Size,
  sharp: PixelRect,
  private val domain: HdrColorMatrixUniforms?,
) : GlShaderProgram {
  private var inputListener: GlShaderProgram.InputListener = object : GlShaderProgram.InputListener {}
  private var outputListener: GlShaderProgram.OutputListener = object : GlShaderProgram.OutputListener {}
  private var errorListener = GlShaderProgram.ErrorListener {}
  private var errorListenerExecutor: Executor = Executor { it.run() }

  // Fixed for the life of this program: the output frame never changes size mid-export, so neither
  // does the plan or the size it blurs at.
  private val plan = fill.blurPlan(outputSize)
  private val smallWidth = plan.width
  private val smallHeight = plan.heightOf(outputSize.height)
  private val across = SpatialBlur.gaussianUniforms(plan, outputSize.height, horizontal = true)
  private val upward = SpatialBlur.gaussianUniforms(plan, outputSize.height, horizontal = false)
  private val gain = fill.backgroundGain
  private val signal = SpatialBlur.signalUniforms(domain)
  private val signalGlsl =
    SpatialBlur.signalGlsl(IDENTITY_GRADE_LIGHT_GLSL).takeIf { domain != null } ?: IDENTITY_SIGNAL_GLSL

  private val coverProgram = program(VERTEX_SHADER, COVER_FRAGMENT_SHADER)
  private val downProgram = program(STEP_VERTEX_SHADER, boxDownFragmentShader(signalGlsl))
  private val blurProgram = program(STEP_VERTEX_SHADER, SpatialBlur.GAUSSIAN_GLSL)
  private val compositeProgram = program(STEP_VERTEX_SHADER, compositeFragmentShader(signalGlsl))

  private var coverTexture = GlTextureInfo.UNSET
  private var downTexture = GlTextureInfo.UNSET
  private var acrossTexture = GlTextureInfo.UNSET
  private var outputTexture = GlTextureInfo.UNSET

  private var lastInputSize: Size? = null
  private var coverScaleUniform = floatArrayOf(1f, 1f)
  private var coverOffsetUniform = floatArrayOf(0f, 0f)

  // Texture coordinates run from the bottom left, so the rect's top and bottom swap over on the way in.
  private val sharpMinUniform =
    floatArrayOf(sharp.left / outputSize.width.toFloat(), 1f - sharp.bottom / outputSize.height.toFloat())
  private val sharpMaxUniform =
    floatArrayOf(sharp.right / outputSize.width.toFloat(), 1f - sharp.top / outputSize.height.toFloat())
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
      drawBoxDown()
      drawBlur(downTexture, acrossTexture, across)
      drawBlur(acrossTexture, downTexture, upward)
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
      listOf(coverTexture, downTexture, acrossTexture, outputTexture).forEach { it.release() }
      listOf(coverProgram, downProgram, blurProgram, compositeProgram).forEach { it.delete() }
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException(e)
    }
  }

  /**
   * Allocates the textures the first time a frame arrives, and updates the cover geometry whenever the source clip's
   * own frame size changes.
   */
  private fun ensureConfigured(
    glObjectsProvider: GlObjectsProvider,
    inputWidth: Int,
    inputHeight: Int,
  ) {
    if (coverTexture == GlTextureInfo.UNSET) {
      coverTexture = allocateTexture(glObjectsProvider, outputSize.width, outputSize.height)
      downTexture = allocateTexture(glObjectsProvider, smallWidth, smallHeight)
      acrossTexture = allocateTexture(glObjectsProvider, smallWidth, smallHeight)
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
  }

  private fun drawCover(inputTexture: GlTextureInfo) {
    focus(coverTexture)
    coverProgram.use()
    coverProgram.setSamplerTexIdUniform("uTexSampler", inputTexture.texId, 0)
    coverProgram.setFloatsUniform("uCoverScale", coverScaleUniform)
    coverProgram.setFloatsUniform("uCoverOffset", coverOffsetUniform)
    draw(coverProgram)
  }

  private fun drawBoxDown() {
    focus(downTexture)
    downProgram.use()
    downProgram.setSamplerTexIdUniform(FRAME, coverTexture.texId, 0)
    downProgram.setFloatsUniform("uTargetSize", floatArrayOf(smallWidth.toFloat(), smallHeight.toFloat()))
    downProgram.setFloatsUniform("uBox", floatArrayOf(plan.factor.toFloat()))
    if (domain != null) signal.forEach { (name, value) -> downProgram.setFloatsUniform(name, value) }
    draw(downProgram)
  }

  // GlProgram binds one element of an array uniform, so the weights go straight to GL once it has bound the rest.
  private fun drawBlur(
    from: GlTextureInfo,
    into: GlTextureInfo,
    uniforms: Map<String, FloatArray>,
  ) {
    focus(into)
    blurProgram.use()
    blurProgram.setSamplerTexIdUniform(FRAME, from.texId, 0)
    val (arrays, values) = uniforms.entries.partition { (name, _) -> name.endsWith("[0]") }
    values.forEach { (name, value) -> blurProgram.setFloatsUniform(name, value) }
    blurProgram.bindAttributesAndUniforms()
    arrays.forEach { (name, value) -> GLES20.glUniform1fv(blurProgram.getUniformLocation(name), value.size, value, 0) }
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, QUAD_VERTICES)
    GlUtil.checkGlError()
  }

  private fun drawComposite(inputTexture: GlTextureInfo) {
    focus(outputTexture)
    compositeProgram.use()
    compositeProgram.setSamplerTexIdUniform(FRAME, downTexture.texId, 0)
    compositeProgram.setSamplerTexIdUniform("uSharpSampler", inputTexture.texId, 1)
    compositeProgram.setFloatsUniform("uSharpMin", sharpMinUniform)
    compositeProgram.setFloatsUniform("uSharpMax", sharpMaxUniform)
    compositeProgram.setFloatsUniform("uGain", floatArrayOf(gain))
    if (domain != null) signal.forEach { (name, value) -> compositeProgram.setFloatsUniform(name, value) }
    draw(compositeProgram)
  }

  private fun focus(texture: GlTextureInfo) {
    GlUtil.focusFramebufferUsingCurrentContext(texture.fboId, texture.width, texture.height)
    GlUtil.clearFocusedBuffers()
  }

  private fun draw(program: GlProgram) {
    program.bindAttributesAndUniforms()
    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, QUAD_VERTICES)
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

  private fun program(
    vertex: String,
    fragment: String,
  ): GlProgram =
    try {
      GlProgram(vertex, fragment).apply {
        setBufferAttribute(
          "aFramePosition",
          GlUtil.getNormalizedCoordinateBounds(),
          GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )
      }
    } catch (e: GlUtil.GlException) {
      throw VideoFrameProcessingException(e)
    }
}

private const val FRAME = "uFrame"
private const val QUAD_VERTICES = 4

// Codes are already the signal on an SDR frame, so the box and the composite take them as they are.
private const val IDENTITY_SIGNAL_GLSL =
  """
  vec3 toSignal(vec3 light) { return light; }
  vec3 fromSignal(vec3 signal) { return signal; }
  """

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

// The vertex shader the shared blur's fragments read their vUv varying from.
private val STEP_VERTEX_SHADER =
  """
  #version 300 es
  in vec4 aFramePosition;
  out vec2 vUv;
  void main() {
    gl_Position = aFramePosition;
    vUv = aFramePosition.xy * 0.5 + 0.5;
  }
  """.trimIndent()

// Boxes the cover down to the plan's size with SpatialBlur.BOX_DOWN_GLSL, each texel read as the signal. The cover is
// opaque, so a texel is averaged as it is.
private fun boxDownFragmentShader(signalGlsl: String): String =
  """
  #version 300 es
  precision highp float;
  in vec2 vUv;
  out vec4 fragColor;
  uniform sampler2D $FRAME;
  uniform vec2 uTargetSize;
  uniform float uBox;
  $signalGlsl

  vec4 premultipliedAt(ivec2 texel, ivec2 size) {
    vec4 read = texelFetch($FRAME, clamp(texel, ivec2(0), size - 1), 0);
    return vec4(toSignal(read.rgb), read.a);
  }

${SpatialBlur.BOX_DOWN_GLSL.prependIndent("  ")}

  void main() {
    fragColor = boxedDown(textureSize($FRAME, 0), vUv, uTargetSize, uBox);
  }
  """.trimIndent()

// Reads the blur back up to the output with linear filtering and dims its signal, under the sharp frame inside its
// rect. The sharp frame's alpha mixes it over that cover, and the result is opaque.
private fun compositeFragmentShader(signalGlsl: String): String =
  """
  #version 300 es
  precision highp float;
  in vec2 vUv;
  out vec4 fragColor;
  uniform sampler2D $FRAME;
  uniform sampler2D uSharpSampler;
  uniform vec2 uSharpMin;
  uniform vec2 uSharpMax;
  uniform float uGain;
  $signalGlsl

  void main() {
    vec4 background = textureLod($FRAME, vUv, 0.0);
    vec3 cover = fromSignal(background.rgb * uGain);
    if (vUv.x >= uSharpMin.x && vUv.x < uSharpMax.x && vUv.y >= uSharpMin.y && vUv.y < uSharpMax.y) {
      vec4 sharp = texture(uSharpSampler, (vUv - uSharpMin) / (uSharpMax - uSharpMin));
      fragColor = vec4(mix(cover, sharp.rgb, sharp.a), 1.0);
    } else {
      fragColor = vec4(cover, 1.0);
    }
  }
  """.trimIndent()

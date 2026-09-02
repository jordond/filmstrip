@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal

import dev.jordond.filmstrip.effects.color.HDR_COLOR_MATRIX_GLSL
import dev.jordond.filmstrip.effects.color.hdrColorMatrixUniforms
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.BT2020_CB_SCALE
import dev.jordond.filmstrip.media.BT2020_CR_SCALE
import dev.jordond.filmstrip.media.BT2020_LUMA_B
import dev.jordond.filmstrip.media.BT2020_LUMA_G
import dev.jordond.filmstrip.media.BT2020_LUMA_R
import dev.jordond.filmstrip.media.HDR_REFERENCE_WHITE_NITS
import dev.jordond.filmstrip.media.HLG_A
import dev.jordond.filmstrip.media.HLG_B
import dev.jordond.filmstrip.media.HLG_C
import dev.jordond.filmstrip.media.HLG_NOMINAL_PEAK_NITS
import dev.jordond.filmstrip.media.HLG_SYSTEM_GAMMA
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.PQ_C1
import dev.jordond.filmstrip.media.PQ_C2
import dev.jordond.filmstrip.media.PQ_C3
import dev.jordond.filmstrip.media.PQ_M1
import dev.jordond.filmstrip.media.PQ_M2
import dev.jordond.filmstrip.media.PQ_PEAK_NITS
import dev.jordond.filmstrip.media.SDR_DISPLAY_GAMMA
import dev.jordond.filmstrip.media.hlgDisplayNitsFromScene
import dev.jordond.filmstrip.media.linearDimGain
import dev.jordond.filmstrip.transform.internal.backgroundGain
import dev.jordond.filmstrip.transform.internal.hdrFillNits
import dev.jordond.filmstrip.transform.internal.hlgSceneFromNits
import dev.jordond.filmstrip.transform.internal.sigmaFor
import kotlinx.coroutines.await
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.get
import kotlin.math.ceil
import kotlin.math.exp

/**
 * One WebGL pass over a decoded frame: the texture matrix an effect resolved to, a colour matrix,
 * and a quad sized to letterbox a clip whose aspect does not match the output.
 *
 * A compositor built with a transfer function keeps the clip's grade instead. The frame's ten-bit
 * planes are uploaded and decoded to linear display light, everything composites at half-float
 * precision, and the result is packed straight into the bytes of an `I420P10` sample. The SDR path
 * is untouched by any of it.
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
  private val texture: JsAny?,
  private val texMatrix: JsAny?,
  private val quadScale: JsAny?,
  private val colorMatrix: JsAny?,
  private val compositionColorMatrix: JsAny?,
  private val blur: BlurPass?,
  private val hdr: HdrPass?,
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
    gl.useProgram(program)
    gl.uniformMatrix3fv(texMatrix, false, clip.matrix.toFloat32Array())
    gl.uniform2f(quadScale, clip.quadHalfW, clip.quadHalfH)
    gl.uniformMatrix4fv(colorMatrix, false, clip.colorMatrix.toFloat32Array())
    gl.uniformMatrix4fv(compositionColorMatrix, false, clip.compositionColorMatrix.toFloat32Array())
  }

  /**
   * Draws one decoded frame into the framebuffer.
   *
   * The clear is per frame rather than per export: a clip narrower than the one before it would
   * otherwise letterbox onto the previous clip's pixels. The blend composites the frame over
   * whatever the fill cleared to, so a bar keeps the fill's own colour whatever grade the clip
   * or the composition sets. That is a requirement every backend honours, not a side effect of this
   * one's single pass, so a second pass added here later still has to draw after the clear rather
   * than fold into it.
   *
   * A clip with bars under a blurred fill draws its background first: the same frame, cover-scaled
   * and blurred, straight into the target, before the sharp contained quad goes on top of it. A
   * clip with no bars, or a fill with nothing to blur, skips straight to the clear.
   *
   * Suspends only on a grade, where the frame's planes are copied out of the decoder before
   * anything can be uploaded.
   */
  suspend fun draw(frame: VideoSample) {
    val clip = checkNotNull(current) { "clip() must run before draw()" }
    val source = if (hdr != null) unpack(hdr, frame) else uploadImage(frame)
    val target = hdr?.outputFbo

    if (blur != null && clip.hasBars) {
      drawBackground(blur, clip, source, target)
      // The cover pass above reused this program's quad uniforms for its own geometry. Put the
      // clip's contained geometry back before the sharp draw below.
      gl.useProgram(program)
      gl.uniform2f(quadScale, clip.quadHalfW, clip.quadHalfH)
    } else {
      gl.bindFramebuffer(GL_FRAMEBUFFER, target)
      gl.clear(GL_COLOR_BUFFER_BIT)
    }

    gl.bindFramebuffer(GL_FRAMEBUFFER, target)
    gl.activeTexture(GL_TEXTURE0)
    gl.bindTexture(GL_TEXTURE_2D, source)
    gl.drawArrays(GL_TRIANGLE_STRIP, 0, QUAD_VERTICES)
  }

  /**
   * The SDR upload: the decoded frame straight into one RGBA texture.
   *
   * `UNPACK_FLIP_Y_WEBGL` is what makes the upload match the +Y-up texture convention every
   * resolver already lowers to. Without it the source's top row lands at `t = 0`, which the shader
   * maps to the bottom of the frame, and every export comes out upside down.
   */
  private fun uploadImage(frame: VideoSample): JsAny {
    val target = checkNotNull(texture) { "an SDR compositor always has an upload texture" }
    gl.activeTexture(GL_TEXTURE0)
    gl.bindTexture(GL_TEXTURE_2D, target)
    gl.pixelStorei(GL_UNPACK_FLIP_Y_WEBGL, 1)
    val image = frame.toVideoFrame()
    try {
      gl.texImage2D(GL_TEXTURE_2D, 0, GL_RGBA, GL_RGBA, GL_UNSIGNED_BYTE, image)
    } finally {
      image.close()
    }
    return target
  }

  /**
   * Copies the frame's ten-bit planes out of the decoder, uploads them as three integer textures,
   * and decodes them to linear display light.
   *
   * The rows arrive top-down, so the flip the SDR upload asks the driver for is done in the shader
   * instead and the linear texture comes out +Y-up like every other texture here. The alignment and
   * the row length both have to be spelled out: a plane's stride is the decoder's own and a
   * chroma row of an odd width is not a multiple of four bytes.
   */
  private suspend fun unpack(
    pass: HdrPass,
    frame: VideoSample,
  ): JsAny {
    val options = EMPTY_OPTIONS
    val frameWidth = frame.codedWidth
    val frameHeight = frame.codedHeight
    val bytes = pass.bufferFor(frame.allocationSize(options))
    val layout = frame.copyTo(bytes, options).await()

    pass.resize(gl, frameWidth, frameHeight)
    gl.useProgram(pass.unpackProgram)
    gl.pixelStorei(GL_UNPACK_FLIP_Y_WEBGL, 0)
    gl.pixelStorei(GL_UNPACK_ALIGNMENT, SAMPLE_BYTES)
    for (index in 0 until PLANES) {
      val plane = checkNotNull(layout[index]) { "a ten-bit frame reports three planes" }
      val samplesPerRow = plane.stride / SAMPLE_BYTES
      val planeWidth = if (index == 0) frameWidth else (frameWidth + 1) / 2
      val planeHeight = if (index == 0) frameHeight else (frameHeight + 1) / 2
      gl.activeTexture(GL_TEXTURE0 + index)
      gl.bindTexture(GL_TEXTURE_2D, pass.planeTextures[index])
      gl.pixelStorei(GL_UNPACK_ROW_LENGTH, samplesPerRow)
      gl.texImage2D(
        GL_TEXTURE_2D,
        0,
        GL_R16UI,
        planeWidth,
        planeHeight,
        0,
        GL_RED_INTEGER,
        GL_UNSIGNED_SHORT,
        Uint16Array(bytes, plane.offset, samplesPerRow * planeHeight),
      )
    }
    gl.pixelStorei(GL_UNPACK_ROW_LENGTH, 0)
    gl.pixelStorei(GL_UNPACK_ALIGNMENT, DEFAULT_ALIGNMENT)

    gl.bindFramebuffer(GL_FRAMEBUFFER, pass.linearFbo)
    gl.viewport(0, 0, frameWidth, frameHeight)
    gl.disable(GL_BLEND)
    gl.drawArrays(GL_TRIANGLE_STRIP, 0, QUAD_VERTICES)
    gl.enable(GL_BLEND)
    gl.viewport(0, 0, width, height)
    gl.activeTexture(GL_TEXTURE0)
    gl.useProgram(program)
    return pass.linearTexture
  }

  /**
   * The letterboxed clip's background: the frame scaled to cover the output, blurred across two
   * passes and dimmed, drawn straight into the target ahead of the sharp foreground.
   *
   * The background is the clip's own pixels, so it is drawn with the clip's colour matrix still set.
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
    source: JsAny,
    target: JsAny?,
  ) {
    gl.useProgram(program)
    gl.viewport(0, 0, blur.smallWidth, blur.smallHeight)
    gl.bindFramebuffer(GL_FRAMEBUFFER, blur.coverFbo)
    gl.clear(GL_COLOR_BUFFER_BIT)
    gl.activeTexture(GL_TEXTURE0)
    gl.bindTexture(GL_TEXTURE_2D, source)
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
    gl.bindFramebuffer(GL_FRAMEBUFFER, target)
    gl.clear(GL_COLOR_BUFFER_BIT)
    gl.bindTexture(GL_TEXTURE_2D, blur.blurTexture)
    gl.uniform2f(blur.texelStep, 0f, 1f / blur.smallHeight)
    gl.uniform1f(blur.gainUniform, blur.gain)
    gl.drawArrays(GL_TRIANGLE_STRIP, 0, QUAD_VERTICES)
  }

  /**
   * The composited frame, stamped for the output timeline, in the form the encoder takes.
   *
   * On SDR that is the canvas. On a kept grade it is the packed ten-bit planes, which the pack pass
   * writes as bytes rather than as an image, so nothing goes through an eight-bit surface on the
   * way out. Timestamps are seconds here, which is what mediabunny takes.
   */
  fun snapshot(
    timestampUs: Double,
    durationUs: Double,
  ): VideoSample {
    val pass = hdr ?: return canvasSample(timestampUs, durationUs)
    return VideoSample(
      pack(pass),
      JsOptions()
        .put("format", TEN_BIT_FORMAT)
        .put("codedWidth", width)
        .put("codedHeight", height)
        .put("layout", pass.layout)
        .put("colorSpace", pass.colorSpace)
        .put("timestamp", timestampUs / MICROS_PER_SECOND)
        .put("duration", durationUs / MICROS_PER_SECOND)
        .build(),
    )
  }

  /**
   * The composited frame as the canvas holds it, for a preview that draws pixels rather than
   * encoding them.
   *
   * A kept grade composites into a float framebuffer the canvas never sees, so it is presented
   * first: clamped at reference white and put through the SDR display curve, which is the picture
   * the browser's own upload of an HDR frame produces today.
   */
  fun present(
    timestampUs: Double,
    durationUs: Double,
  ): VideoSample {
    hdr?.let { presentToCanvas(it) }
    return canvasSample(timestampUs, durationUs)
  }

  private fun canvasSample(
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

  /**
   * Runs the pack pass and reads its bytes back. The read is synchronous and stalls the pipeline
   * behind it, which is where a ten-bit frame spends most of its time.
   */
  private fun pack(pass: HdrPass): Uint8Array {
    gl.useProgram(pass.packProgram)
    gl.activeTexture(GL_TEXTURE0)
    gl.bindTexture(GL_TEXTURE_2D, pass.outputTexture)
    gl.bindFramebuffer(GL_FRAMEBUFFER, pass.packFbo)
    gl.viewport(0, 0, width / 2, 2 * height)
    gl.disable(GL_BLEND)
    gl.drawArrays(GL_TRIANGLE_STRIP, 0, QUAD_VERTICES)
    gl.readPixels(0, 0, width / 2, 2 * height, GL_RGBA, GL_UNSIGNED_BYTE, pass.packed)
    gl.enable(GL_BLEND)
    gl.viewport(0, 0, width, height)
    gl.useProgram(program)
    return pass.packed
  }

  private fun presentToCanvas(pass: HdrPass) {
    gl.useProgram(pass.presentProgram)
    gl.activeTexture(GL_TEXTURE0)
    gl.bindTexture(GL_TEXTURE_2D, pass.outputTexture)
    gl.bindFramebuffer(GL_FRAMEBUFFER, null)
    gl.disable(GL_BLEND)
    gl.drawArrays(GL_TRIANGLE_STRIP, 0, QUAD_VERTICES)
    gl.enable(GL_BLEND)
    gl.useProgram(program)
  }

  fun release() {
    hdr?.let { pass ->
      pass.planeTextures.forEach { gl.deleteTexture(it) }
      gl.deleteProgram(pass.unpackProgram)
      gl.deleteFramebuffer(pass.linearFbo)
      gl.deleteTexture(pass.linearTexture)
      gl.deleteFramebuffer(pass.outputFbo)
      gl.deleteTexture(pass.outputTexture)
      gl.deleteProgram(pass.packProgram)
      gl.deleteFramebuffer(pass.packFbo)
      gl.deleteTexture(pass.packTexture)
      gl.deleteProgram(pass.presentProgram)
    }
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
    gl.getExtension(LOSE_CONTEXT)?.loseContext()
  }

  companion object {
    /**
     * Builds a compositor for a [width] by [height] output, filled with [fill] wherever no clip's
     * pixels land.
     *
     * @param hdrTransfer The transfer function the output carries, or null for SDR. A transfer
     *   here is what puts the whole pipeline on the ten-bit path, so it is settled once and never
     *   swapped underneath a running export.
     */
    fun create(
      width: Int,
      height: Int,
      fill: Fill,
      hdrTransfer: HdrTransfer? = null,
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

      if (hdrTransfer != null && gl.getExtension(COLOR_BUFFER_FLOAT) == null) {
        throw BrowserExportFailure(NO_FLOAT_BUFFER)
      }

      val program = link(gl, VERTEX_SHADER, fragmentShader(hdrTransfer))
      gl.useProgram(program)

      val buffer = gl.createBuffer() ?: throw BrowserExportFailure(NO_BUFFER)
      gl.bindBuffer(GL_ARRAY_BUFFER, buffer)
      gl.bufferData(GL_ARRAY_BUFFER, UNIT_QUAD.toFloat32Array(), GL_STATIC_DRAW)
      bindQuad(gl, program)

      // The SDR path uploads a decoded frame straight into this. A grade uploads three planes of
      // its own instead and has no use for it.
      val texture = if (hdrTransfer == null) createTexture(gl, GL_LINEAR) else null

      gl.viewport(0, 0, width, height)
      val (clearRed, clearGreen, clearBlue) = fill.clearColor(hdrTransfer)
      gl.clearColor(clearRed, clearGreen, clearBlue, 1f)
      // Source-over onto the fill's opaque clear, which is what puts the fill's colour in a
      // letterbox bar. The alpha factors keep the result opaque, which the context's premultiplied
      // alpha needs and which matches an encoder that discards alpha anyway.
      gl.enable(GL_BLEND)
      gl.blendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA)

      val hdr = hdrTransfer?.let { buildHdrPass(gl, width, height, it, buffer) }
      val blur =
        if (fill is Fill.Blurred) buildBlurPass(gl, width, height, fill, buffer, hdrTransfer) else null
      // Both builders leave their own program bound to upload uniforms. Put the main one back so
      // clip() and draw() find what they expect.
      gl.useProgram(program)

      val compositor =
        BrowserCompositor(
          canvas = canvas,
          gl = gl,
          width = width,
          height = height,
          program = program,
          buffer = buffer,
          texture = texture,
          texMatrix = gl.getUniformLocation(program, "uTexMatrix"),
          quadScale = gl.getUniformLocation(program, "uQuadScale"),
          colorMatrix = gl.getUniformLocation(program, "uColorMatrix"),
          compositionColorMatrix = gl.getUniformLocation(program, "uCompositionColorMatrix"),
          blur = blur,
          hdr = hdr,
        )
      if (hdrTransfer != null) uploadGradeUniforms(gl, program, hdrTransfer)
      return compositor
    }

    /**
     * The figures the colour matrix arm reads the frame through.
     *
     * The linear texture holds display light with reference white at one, for both transfer
     * functions alike, so both read the frame the same way and only the ceiling parts them.
     */
    private fun uploadGradeUniforms(
      gl: WebGl2,
      program: JsAny,
      transfer: HdrTransfer,
    ) {
      val uniforms = transfer.hdrColorMatrixUniforms(white = 1f, holdsSceneLight = false)
      gl.useProgram(program)
      gl.uniform1f(gl.getUniformLocation(program, "uWhite"), uniforms.white)
      gl.uniform1f(gl.getUniformLocation(program, "uDisplayGamma"), uniforms.displayGamma)
      gl.uniform1f(gl.getUniformLocation(program, "uOotfGamma"), uniforms.ootfGamma)
      gl.uniform1f(gl.getUniformLocation(program, "uCeiling"), uniforms.ceiling)
    }

    /**
     * The programs, textures and buffers a kept grade needs: the three plane textures and the
     * program that decodes them, the float source and output framebuffers everything composites
     * through, the pack pass that writes the encoder's bytes, and the present pass a preview reads
     * the canvas through.
     */
    private fun buildHdrPass(
      gl: WebGl2,
      width: Int,
      height: Int,
      transfer: HdrTransfer,
      buffer: JsAny,
    ): HdrPass {
      val unpackProgram = link(gl, FULLSCREEN_VERTEX_SHADER, unpackShader(transfer))
      gl.useProgram(unpackProgram)
      gl.bindBuffer(GL_ARRAY_BUFFER, buffer)
      bindQuad(gl, unpackProgram)
      listOf("uY", "uU", "uV").forEachIndexed { index, name ->
        gl.uniform1i(gl.getUniformLocation(unpackProgram, name), index)
      }
      // An integer texture cannot be filtered, and nearest is what the chroma planes want anyway:
      // a sample here is a code value, not a colour to interpolate.
      val planeTextures = List(PLANES) { createTexture(gl, GL_NEAREST) }

      val linearTexture = createTexture(gl, GL_LINEAR)
      allocate(gl, linearTexture, width, height, hdr = true)
      val linearFbo = createComplete(gl, linearTexture)

      val outputTexture = createTexture(gl, GL_LINEAR)
      allocate(gl, outputTexture, width, height, hdr = true)
      val outputFbo = createComplete(gl, outputTexture)

      val packProgram = link(gl, FULLSCREEN_VERTEX_SHADER, packShader(transfer))
      gl.useProgram(packProgram)
      gl.bindBuffer(GL_ARRAY_BUFFER, buffer)
      bindQuad(gl, packProgram)
      gl.uniform1i(gl.getUniformLocation(packProgram, "uTexSampler"), 0)
      gl.uniform2f(gl.getUniformLocation(packProgram, "uSize"), width.toFloat(), height.toFloat())
      val packTexture = createTexture(gl, GL_NEAREST)
      allocate(gl, packTexture, width / 2, 2 * height, hdr = false)
      val packFbo = createComplete(gl, packTexture)

      val presentProgram = link(gl, FULLSCREEN_VERTEX_SHADER, PRESENT_FRAGMENT_SHADER)
      gl.useProgram(presentProgram)
      gl.bindBuffer(GL_ARRAY_BUFFER, buffer)
      bindQuad(gl, presentProgram)
      gl.uniform1i(gl.getUniformLocation(presentProgram, "uTexSampler"), 0)

      gl.bindFramebuffer(GL_FRAMEBUFFER, null)

      // Each row of the packed framebuffer is one row of one plane, so the stride is the same on
      // all three. A chroma row fills half of its stride, which the layout says outright rather
      // than the planes being interleaved: a frame's plane ranges may not overlap.
      val stride = width * SAMPLE_BYTES
      val lumaBytes = stride * height
      val chromaBytes = stride * (height / 2)
      return HdrPass(
        planeTextures = planeTextures,
        unpackProgram = unpackProgram,
        linearTexture = linearTexture,
        linearFbo = linearFbo,
        linearWidth = width,
        linearHeight = height,
        outputTexture = outputTexture,
        outputFbo = outputFbo,
        packProgram = packProgram,
        packTexture = packTexture,
        packFbo = packFbo,
        packed = Uint8Array(stride * 2 * height),
        presentProgram = presentProgram,
        layout =
          jsArrayOf(
            listOf(
              planeLayout(0, stride),
              planeLayout(lumaBytes, stride),
              planeLayout(lumaBytes + chromaBytes, stride),
            ),
          ),
        colorSpace =
          JsOptions()
            .put("primaries", "bt2020")
            .put("transfer", if (transfer == HdrTransfer.Pq) "pq" else "hlg")
            .put("matrix", "bt2020-ncl")
            .put("fullRange", false)
            .build(),
      )
    }

    private fun planeLayout(
      offset: Int,
      stride: Int,
    ): JsAny =
      JsOptions()
        .put("offset", offset)
        .put("stride", stride)
        .build()

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
      hdrTransfer: HdrTransfer?,
    ): BlurPass {
      val program = link(gl, FULLSCREEN_VERTEX_SHADER, blurFragmentShader(hdrTransfer))
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

    /**
     * An empty texture sized [width] by [height], for a framebuffer's colour attachment.
     */
    private fun createFramebufferTexture(
      gl: WebGl2,
      width: Int,
      height: Int,
      hdr: Boolean,
    ): JsAny = createTexture(gl, GL_LINEAR).also { allocate(gl, it, width, height, hdr) }

    private fun createTexture(
      gl: WebGl2,
      filter: Int,
    ): JsAny {
      val texture = gl.createTexture() ?: throw BrowserExportFailure(NO_TEXTURE)
      gl.bindTexture(GL_TEXTURE_2D, texture)
      gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter)
      gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter)
      gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
      gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
      return texture
    }

    /**
     * Sizes the bound texture with no image behind it. A grade composites at half float, which
     * carries the headroom above reference white a kept highlight needs.
     */
    private fun allocate(
      gl: WebGl2,
      texture: JsAny,
      width: Int,
      height: Int,
      hdr: Boolean,
    ) {
      gl.bindTexture(GL_TEXTURE_2D, texture)
      if (hdr) {
        gl.texImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, width, height, 0, GL_RGBA, GL_HALF_FLOAT, null)
      } else {
        gl.texImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
      }
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

    private fun bindQuad(
      gl: WebGl2,
      program: JsAny,
    ) {
      val position = gl.getAttribLocation(program, "aPosition")
      gl.enableVertexAttribArray(position)
      gl.vertexAttribPointer(position, 2, GL_FLOAT, false, 0, 0)
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

    private val EMPTY_OPTIONS: JsAny = JsOptions().build()

    private const val NO_CONTEXT = "The page gave out no WebGL2 context, so there is nothing to composite on."
    private const val NO_PROGRAM = "WebGL2 refused to create a program."
    private const val NO_SHADER = "WebGL2 refused to create a shader."
    private const val NO_BUFFER = "WebGL2 refused to create a vertex buffer."
    private const val NO_TEXTURE = "WebGL2 refused to create a texture."
    private const val NO_FRAMEBUFFER = "WebGL2 refused to give the fill's background pass a complete framebuffer."
    private const val NO_FLOAT_BUFFER =
      "This context cannot render into a float framebuffer, so an HDR grade has nowhere to " +
        "composite. EXT_color_buffer_float is missing."
  }
}

/**
 * Whether this page can render into the float framebuffer a kept grade composites in.
 *
 * Asked by building the smallest one there is and handing the context straight back, because a
 * browser can carry the extension and still refuse the attachment on a driver it has blocklisted.
 * The companion to [browserCanComposite], and the other half of what makes an HDR encode claimable.
 */
internal fun browserCanRenderFloat(): Boolean =
  runCatching {
    val gl =
      OffscreenCanvas(1, 1).getContext("webgl2", JsOptions().build()) ?: return false
    try {
      if (gl.getExtension(COLOR_BUFFER_FLOAT) == null) return false
      val texture = gl.createTexture() ?: return false
      gl.bindTexture(GL_TEXTURE_2D, texture)
      gl.texImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, 1, 1, 0, GL_RGBA, GL_HALF_FLOAT, null)
      val framebuffer = gl.createFramebuffer() ?: return false
      gl.bindFramebuffer(GL_FRAMEBUFFER, framebuffer)
      gl.framebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
      gl.checkFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE
    } finally {
      gl.getExtension(LOSE_CONTEXT)?.loseContext()
    }
  }.getOrDefault(false)

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

/**
 * Everything a kept grade adds: the three integer planes a decoded frame is uploaded into, the
 * float textures it composites through, the pack pass that writes the encoder's bytes, and the
 * layout and colour tag those bytes are handed over with.
 *
 * @property linearWidth The size [linearTexture] is currently allocated at, which follows the
 *   decoded frame rather than the output. A clip of another size reallocates it.
 * @property packed The pack pass's read-back target, reused across frames. mediabunny copies the
 *   bytes when it takes the sample, so a frame in flight never sees the next one's.
 */
private class HdrPass(
  val planeTextures: List<JsAny>,
  val unpackProgram: JsAny,
  val linearTexture: JsAny,
  val linearFbo: JsAny,
  var linearWidth: Int,
  var linearHeight: Int,
  val outputTexture: JsAny,
  val outputFbo: JsAny,
  val packProgram: JsAny,
  val packTexture: JsAny,
  val packFbo: JsAny,
  val packed: Uint8Array,
  val presentProgram: JsAny,
  val layout: JsArray<JsAny>,
  val colorSpace: JsAny,
) {
  private var planes: ArrayBuffer? = null

  /**
   * A buffer of at least [size] bytes for the decoder to copy into, kept between frames.
   */
  fun bufferFor(size: Int): ArrayBuffer {
    planes?.takeIf { it.byteLength >= size }?.let { return it }
    return ArrayBuffer(size).also { planes = it }
  }

  /**
   * Resizes the linear source to match the frame being decoded. Every clip of the same size after
   * the first costs nothing.
   */
  fun resize(
    gl: WebGl2,
    width: Int,
    height: Int,
  ) {
    if (width == linearWidth && height == linearHeight) return
    gl.activeTexture(GL_TEXTURE0)
    gl.bindTexture(GL_TEXTURE_2D, linearTexture)
    gl.texImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, width, height, 0, GL_RGBA, GL_HALF_FLOAT, null)
    linearWidth = width
    linearHeight = height
  }
}

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

// A full-screen quad with no letterbox and no texture matrix: the blur, unpack, pack and present
// passes all read a texture that already covers the whole viewport, so there is nothing left to
// scale or warp.
private val FULLSCREEN_VERTEX_SHADER =
  """
  #version 300 es
  in vec2 aPosition;
  out vec2 vTexCoord;
  void main() {
    vTexCoord = aPosition * 0.5 + 0.5;
    gl_Position = vec4(aPosition, 0.0, 1.0);
  }
  """.trimIndent()

/**
 * The composite pass, on the signal an eight-bit frame carries or on the light a kept grade holds.
 *
 * The clip's grade and the composition's are two matrices with a clamp between them rather than one
 * product, because the planner folds a run of colour effects per stage and every backend writes the
 * clip's stage into a frame before the composition's runs. On SDR the colour arithmetic is highp:
 * mediump is fp16 on a mobile GPU, and a matrix with a bias moves an eight-bit code by one there. A
 * grade is highp throughout instead, since the light it carries runs past one and the shared body
 * takes its arguments at the default precision.
 */
private fun fragmentShader(transfer: HdrTransfer?): String =
  if (transfer == null) {
    """
    #version 300 es
    precision mediump float;
    uniform sampler2D uTexSampler;
    uniform highp mat4 uColorMatrix;
    uniform highp mat4 uCompositionColorMatrix;
    in vec2 vTexCoord;
    out vec4 outColor;
    void main() {
      vec4 c = texture(uTexSampler, vTexCoord);
      highp vec3 graded = clamp((uColorMatrix * vec4(c.rgb, 1.0)).rgb, 0.0, 1.0);
      graded = clamp((uCompositionColorMatrix * vec4(graded, 1.0)).rgb, 0.0, 1.0);
      outColor = vec4(graded, c.a);
    }
    """.trimIndent()
  } else {
    """
    #version 300 es
    precision highp float;
    uniform sampler2D uTexSampler;
    uniform mat4 uColorMatrix;
    uniform mat4 uCompositionColorMatrix;
    uniform float uWhite;
    uniform float uDisplayGamma;
    uniform float uOotfGamma;
    uniform float uCeiling;
    in vec2 vTexCoord;
    out vec4 outColor;
    $HDR_COLOR_MATRIX_GLSL
    void main() {
      vec4 c = texture(uTexSampler, vTexCoord);
      vec3 graded =
        filmstripGradeHdr(c.rgb, uColorMatrix, uWhite, uDisplayGamma, uOotfGamma, uCeiling);
      graded =
        filmstripGradeHdr(graded, uCompositionColorMatrix, uWhite, uDisplayGamma, uOotfGamma, uCeiling);
      outColor = vec4(graded, c.a);
    }
    """.trimIndent()
  }

// One direction at a time: uTexelStep is the horizontal step for the first pass and the vertical
// step for the second, and uGain only takes effect on the second, once the blur is done spreading.
// The kernel is symmetric, so uWeights only holds the centre weight and one weight per tap radius,
// and each side samples the same texel offset twice, once in each direction.
private fun blurFragmentShader(transfer: HdrTransfer?): String =
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
 * Ten-bit limited-range BT.2020 NCL planes to linear display light, with one at
 * [HDR_REFERENCE_WHITE_NITS].
 *
 * The planes arrive top-down where every texture here is +Y-up, so the sample is flipped on the way
 * in. HLG's opto-optical transfer runs per channel, which is the reading media3 and ffmpeg apply
 * and the one `sdrSignalFromHlgScene` already spells.
 */
private fun unpackShader(transfer: HdrTransfer): String =
  """
  #version 300 es
  precision highp float;
  precision highp usampler2D;
  uniform usampler2D uY;
  uniform usampler2D uU;
  uniform usampler2D uV;
  in vec2 vTexCoord;
  out vec4 outColor;
  float code(usampler2D plane) {
    return float(texture(plane, vec2(vTexCoord.x, 1.0 - vTexCoord.y)).r);
  }
  ${if (transfer == HdrTransfer.Pq) PQ_EOTF_GLSL else HLG_EOTF_GLSL}
  void main() {
    float y = (code(uY) - $LUMA_FLOOR.0) / $LUMA_RANGE.0;
    float cb = (code(uU) - $CHROMA_MID.0) / $CHROMA_RANGE.0;
    float cr = (code(uV) - $CHROMA_MID.0) / $CHROMA_RANGE.0;
    float r = y + ${BT2020_CR_SCALE.glsl()} * cr;
    float b = y + ${BT2020_CB_SCALE.glsl()} * cb;
    float g = (y - ${BT2020_LUMA_R.glsl()} * r - ${BT2020_LUMA_B.glsl()} * b) / ${BT2020_LUMA_G.glsl()};
    vec3 nits = eotf(clamp(vec3(r, g, b), 0.0, 1.0));
    outColor = vec4(nits / ${HDR_REFERENCE_WHITE_NITS.glsl()}, 1.0);
  }
  """.trimIndent()

/**
 * Linear display light back to ten-bit limited-range BT.2020 NCL planes, packed as the bytes of an
 * `I420P10` frame.
 *
 * The framebuffer is half the output's width and twice its height: one row per plane row, luma
 * first, then Cb, then Cr, two samples to an `RGBA8` texel written little-endian. Chroma is
 * averaged over the two by two block of re-derived samples rather than taken from one of them, and
 * fills half of its row, which the layout the frame is built with accounts for.
 *
 * A read back row is bottom-up, so a buffer row maps to its own framebuffer row directly and the
 * flip happens where the linear texture is sampled instead.
 */
private fun packShader(transfer: HdrTransfer): String =
  """
  #version 300 es
  precision highp float;
  uniform sampler2D uTexSampler;
  uniform vec2 uSize;
  out vec4 outColor;
  ${if (transfer == HdrTransfer.Pq) PQ_OETF_GLSL else HLG_OETF_GLSL}
  vec3 signalAt(vec2 pixel) {
    vec3 light = texture(uTexSampler, (pixel + 0.5) / uSize).rgb;
    return oetf(max(light, 0.0) * ${HDR_REFERENCE_WHITE_NITS.glsl()});
  }
  vec3 ycc(vec3 rgb) {
    float y = ${BT2020_LUMA_R.glsl()} * rgb.r + ${BT2020_LUMA_G.glsl()} * rgb.g + ${BT2020_LUMA_B.glsl()} * rgb.b;
    return vec3(y, (rgb.b - y) / ${BT2020_CB_SCALE.glsl()}, (rgb.r - y) / ${BT2020_CR_SCALE.glsl()});
  }
  vec4 pack2(float first, float second) {
    float a = clamp(floor(first + 0.5), 0.0, $MAX_TEN_BIT_CODE.0);
    float b = clamp(floor(second + 0.5), 0.0, $MAX_TEN_BIT_CODE.0);
    return vec4(mod(a, 256.0), floor(a / 256.0), mod(b, 256.0), floor(b / 256.0)) / 255.0;
  }
  void main() {
    float x = floor(gl_FragCoord.x);
    float y = floor(gl_FragCoord.y);
    float h = uSize.y;
    if (y < h) {
      float row = h - 1.0 - y;
      float first = ycc(signalAt(vec2(2.0 * x, row))).x;
      float second = ycc(signalAt(vec2(2.0 * x + 1.0, row))).x;
      outColor = pack2($LUMA_FLOOR.0 + $LUMA_RANGE.0 * first, $LUMA_FLOOR.0 + $LUMA_RANGE.0 * second);
      return;
    }
    if (x >= uSize.x / 4.0) {
      outColor = vec4(0.0);
      return;
    }
    bool isCr = y >= 1.5 * h;
    float chromaRow = isCr ? y - 1.5 * h : y - h;
    float top = h - 1.0 - 2.0 * chromaRow;
    float bottom = top - 1.0;
    float samples[2];
    for (int k = 0; k < 2; k++) {
      float left = 4.0 * x + 2.0 * float(k);
      vec3 mean =
        (ycc(signalAt(vec2(left, top))) +
          ycc(signalAt(vec2(left + 1.0, top))) +
          ycc(signalAt(vec2(left, bottom))) +
          ycc(signalAt(vec2(left + 1.0, bottom)))) * 0.25;
      samples[k] = isCr ? mean.z : mean.y;
    }
    outColor =
      pack2(
        $CHROMA_MID.0 + $CHROMA_RANGE.0 * samples[0],
        $CHROMA_MID.0 + $CHROMA_RANGE.0 * samples[1]
      );
  }
  """.trimIndent()

// What a preview draws: the same picture the browser's own upload of an HDR frame produces, which
// is reference white clipped at one through the SDR display curve.
private val PRESENT_FRAGMENT_SHADER =
  """
  #version 300 es
  precision highp float;
  uniform sampler2D uTexSampler;
  in vec2 vTexCoord;
  out vec4 outColor;
  void main() {
    vec3 light = clamp(texture(uTexSampler, vTexCoord).rgb, 0.0, 1.0);
    outColor = vec4(pow(light, vec3(1.0 / ${SDR_DISPLAY_GAMMA.glsl()})), 1.0);
  }
  """.trimIndent()

/**
 * This figure as a GLSL float literal.
 *
 * A whole number renders without a decimal point on the js target, where a Kotlin Float is a
 * JavaScript number, and GLSL will not multiply a vector by an integer literal. Spelling the point
 * back on keeps one shader source across both targets.
 */
private fun Number.glsl(): String {
  val text = toString()

  return if (text.any { it == '.' || it == 'e' || it == 'E' }) text else "$text.0"
}

private val PQ_EOTF_GLSL =
  """
  vec3 eotf(vec3 signal) {
    vec3 encoded = pow(max(signal, 0.0), vec3(1.0 / ${PQ_M2.glsl()}));
    vec3 numerator = max(encoded - ${PQ_C1.glsl()}, 0.0);
    vec3 denominator = ${PQ_C2.glsl()} - ${PQ_C3.glsl()} * encoded;
    return pow(numerator / denominator, vec3(1.0 / ${PQ_M1.glsl()})) * ${PQ_PEAK_NITS.glsl()};
  }
  """.trimIndent()

private val PQ_OETF_GLSL =
  """
  vec3 oetf(vec3 nits) {
    vec3 y = pow(clamp(nits / ${PQ_PEAK_NITS.glsl()}, 0.0, 1.0), vec3(${PQ_M1.glsl()}));
    return pow((${PQ_C1.glsl()} + ${PQ_C2.glsl()} * y) / (1.0 + ${PQ_C3.glsl()} * y), vec3(${PQ_M2.glsl()}));
  }
  """.trimIndent()

private val HLG_EOTF_GLSL =
  """
  vec3 eotf(vec3 signal) {
    vec3 low = signal * signal / 3.0;
    vec3 high = (exp((signal - ${HLG_C.glsl()}) / ${HLG_A.glsl()}) + ${HLG_B.glsl()}) / 12.0;
    vec3 scene = mix(low, high, step(0.5, signal));
    return pow(scene, vec3(${HLG_SYSTEM_GAMMA.glsl()})) * ${HLG_NOMINAL_PEAK_NITS.glsl()};
  }
  """.trimIndent()

// The log arm has to be guarded rather than left to the clamp: mix evaluates both arms, and the
// dark one would take the log of a negative and carry a NaN across the blend.
private val HLG_OETF_GLSL =
  """
  vec3 oetf(vec3 nits) {
    vec3 scene = pow(max(nits, 0.0) / ${HLG_NOMINAL_PEAK_NITS.glsl()}, vec3(1.0 / ${HLG_SYSTEM_GAMMA.glsl()}));
    vec3 low = sqrt(3.0 * scene);
    vec3 high = ${HLG_A.glsl()} * log(max(12.0 * scene - ${HLG_B.glsl()}, 1e-6)) + ${HLG_C.glsl()};
    return clamp(mix(low, high, step(1.0 / 12.0, scene)), 0.0, 1.0);
  }
  """.trimIndent()

/**
 * The clear colour [Fill.Solid] paints outright. A blurred fill has no single colour of its own, so
 * every pixel it reaches is drawn over before the frame is handed back, and this is only what a
 * stray uncovered one would fall back to.
 *
 * On a kept grade the colour is linear light rather than an encoded value. HLG is pre-distorted
 * through the inverse of the per-channel transfer the pack pass runs, so what ends up in the file
 * is the luminance-driven `hlgSignalFromNits` every other backend writes for the same fill.
 */
private fun Fill.clearColor(transfer: HdrTransfer?): FloatArray =
  when (this) {
    is Fill.Solid -> {
      if (transfer == null) {
        floatArrayOf(
          ((color shr RED_SHIFT) and BYTE_MASK) / MAX_CHANNEL,
          ((color shr GREEN_SHIFT) and BYTE_MASK) / MAX_CHANNEL,
          (color and BYTE_MASK) / MAX_CHANNEL,
        )
      } else {
        val nits = hdrFillNits(color)
        val light =
          when (transfer) {
            HdrTransfer.Pq -> {
              nits
            }
            HdrTransfer.Hlg -> {
              val scene = hlgSceneFromNits(nits)
              FloatArray(3) { hlgDisplayNitsFromScene(scene[it]) }
            }
          }
        FloatArray(3) { light[it] / HDR_REFERENCE_WHITE_NITS }
      }
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

// The limited range a ten-bit BT.709 or BT.2020 signal is stored in. Black sits at 64, white at
// 940, and a chroma channel is centred on 512 spanning 896 codes.
private const val LUMA_FLOOR = 64
private const val LUMA_RANGE = 876
private const val CHROMA_MID = 512
private const val CHROMA_RANGE = 896
private const val MAX_TEN_BIT_CODE = 1023

private const val PLANES = 3
private const val SAMPLE_BYTES = 2
private const val DEFAULT_ALIGNMENT = 4
private const val COLOR_BUFFER_FLOAT = "EXT_color_buffer_float"
private const val LOSE_CONTEXT = "WEBGL_lose_context"

private const val GL_COLOR_BUFFER_BIT = 0x4000
private const val GL_TEXTURE_2D = 0x0DE1
private const val GL_RGBA = 0x1908
private const val GL_RGBA16F = 0x881A
private const val GL_R16UI = 0x8234
private const val GL_RED_INTEGER = 0x8D94
private const val GL_UNSIGNED_BYTE = 0x1401
private const val GL_UNSIGNED_SHORT = 0x1403
private const val GL_HALF_FLOAT = 0x140B
private const val GL_TEXTURE_MAG_FILTER = 0x2800
private const val GL_TEXTURE_MIN_FILTER = 0x2801
private const val GL_TEXTURE_WRAP_S = 0x2802
private const val GL_TEXTURE_WRAP_T = 0x2803
private const val GL_NEAREST = 0x2600
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
private const val GL_UNPACK_ALIGNMENT = 0x0CF5
private const val GL_UNPACK_ROW_LENGTH = 0x0CF2
private const val GL_UNPACK_FLIP_Y_WEBGL = 0x9240
private const val GL_TEXTURE0 = 0x84C0
private const val GL_FRAMEBUFFER = 0x8D40
private const val GL_COLOR_ATTACHMENT0 = 0x8CE0
private const val GL_FRAMEBUFFER_COMPLETE = 0x8CD5

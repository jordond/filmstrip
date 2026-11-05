@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal.render

import dev.jordond.filmstrip.color.authoredHdrNits
import dev.jordond.filmstrip.effect.WebGlOverlay
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.webcodecs.internal.BrowserExportFailure
import dev.jordond.filmstrip.webcodecs.internal.BrowserRender
import dev.jordond.filmstrip.webcodecs.internal.MICROS_PER_SECOND
import dev.jordond.filmstrip.webcodecs.internal.RenderedClip
import dev.jordond.filmstrip.webcodecs.internal.TEN_BIT_FORMAT
import dev.jordond.filmstrip.webcodecs.internal.interop.JsOptions
import dev.jordond.filmstrip.webcodecs.internal.interop.OffscreenCanvas
import dev.jordond.filmstrip.webcodecs.internal.interop.VideoSample
import dev.jordond.filmstrip.webcodecs.internal.interop.WebGl2
import dev.jordond.filmstrip.webcodecs.internal.interop.toFloat32Array
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/**
 * One WebGL pass over a decoded frame: the texture matrix an effect resolved to, a color matrix,
 * and a quad the clip's own placement sizes to contain, cover or stretch it into the output.
 *
 * A compositor built with a transfer function keeps the clip's grade instead. The frame's ten-bit
 * planes are uploaded and decoded to linear display light, everything composites at half-float
 * precision, and the result is packed straight into the bytes of an `I420P10` sample. The SDR path
 * is untouched by any of it.
 *
 * On a tone mapped export the compositor is an SDR one, and an HDR clip's planes go through the shared tone curve of
 * the clip's own transfer before the composite, so from there on its frame is an SDR frame like any other.
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
  private val canvasUniforms: ClipCanvasUniforms,
  private val blur: BlurPass?,
  private val hdr: HdrPass?,
  private val hdrTransfer: HdrTransfer?,
) {
  private var current: RenderedClip? = null
  private var overlayPass: BrowserOverlayPass? = null
  private var toneMapPass: ToneMapPass? = null

  /**
   * Where the slot drawn last sits on the output timeline, in microseconds.
   *
   * [draw] and [drawFill] record it as they open. The passes inside a draw take the time they draw
   * at as an argument rather than reading it back from here.
   */
  var compositionUs: Double = 0.0
    private set

  /**
   * Sets the state one clip draws with. Everything here is constant for the length of a clip, apart from a texture
   * matrix that moves with time, which [draw] uploads on every frame instead.
   *
   * The clip itself is kept around, not just its uniforms, because [draw] needs its cover geometry
   * and whether it has bars at all once the fill is blurred.
   */
  fun clip(clip: RenderedClip) {
    current = clip
    gl.useProgram(program)
    if (!clip.matrix.varies) gl.uniformMatrix3fv(texMatrix, false, clip.matrix.at(Duration.ZERO).toFloat32Array())
    gl.uniform2f(quadScale, clip.quadHalfW, clip.quadHalfH)
    gl.uniformMatrix4fv(colorMatrix, false, clip.colorMatrix.toFloat32Array())
    gl.uniformMatrix4fv(compositionColorMatrix, false, clip.compositionColorMatrix.toFloat32Array())
    canvasUniforms.upload(gl, clip.canvases)
  }

  /**
   * Draws one decoded frame into the framebuffer.
   *
   * The clear is per frame rather than per export: a clip narrower than the one before it would
   * otherwise letterbox onto the previous clip's pixels. The blend composites the frame over
   * whatever the fill cleared to, so a bar keeps the fill's own color whatever grade the clip
   * or the composition sets. That is a requirement every backend honours, not a side effect of this
   * one's single pass, so a second pass added here later still has to draw after the clear rather
   * than fold into it.
   *
   * A clip with bars under a blurred fill draws its background first: the same frame, cover-scaled
   * and blurred, straight into the target, before the sharp contained quad goes on top of it. A
   * clip with no bars, or a fill with nothing to blur, skips straight to the clear.
   *
   * The clip's overlays go on right after it, inside its quad, and the composition's go on last over the whole
   * frame.
   *
   * Suspends only on a grade or a tone mapped clip, where the frame's planes are copied out of the
   * decoder before anything can be uploaded.
   *
   * @param compositionUs Where this slot sits on the output timeline. Everything [clip] sets holds
   *   for the length of a clip, so this is where a pass that varies with time uploads its per-frame
   *   uniforms.
   * @param compositionOverlays The render's [BrowserRender.compositionOverlays].
   */
  suspend fun draw(
    frame: VideoSample,
    compositionUs: Double,
    compositionOverlays: List<WebGlOverlay>,
  ) {
    this.compositionUs = compositionUs
    val clip = checkNotNull(current) { "clip() must run before draw()" }
    if (clip.matrix.varies) {
      gl.useProgram(program)
      gl.uniformMatrix3fv(texMatrix, false, clip.matrix.at(compositionUs.microseconds).toFloat32Array())
    }
    if (clip.canvases.any { it.contentMatrix.varies }) {
      gl.useProgram(program)
      canvasUniforms.uploadAt(gl, clip.canvases, compositionUs.microseconds)
    }
    val toneMapFrom = clip.toneMapFrom
    val source =
      when {
        hdr != null -> hdr.unpack(gl, frame, program)
        toneMapFrom != null -> toneMap().toneMap(gl, frame, toneMapFrom, program)
        else -> uploadImage(frame)
      }
    val target = hdr?.outputFbo

    // Either branch leaves the target bound for the sharp draw below.
    if (blur != null && clip.hasBars) {
      drawBackground(blur, clip, source, target, compositionUs)
      // The cover pass above reused this program's quad uniforms for its own geometry. Put the
      // clip's contained geometry back before the sharp draw below.
      gl.useProgram(program)
      gl.uniform2f(quadScale, clip.quadHalfW, clip.quadHalfH)
    } else {
      gl.bindFramebuffer(GL_FRAMEBUFFER, target)
      gl.clear(GL_COLOR_BUFFER_BIT)
    }

    gl.activeTexture(GL_TEXTURE0)
    gl.bindTexture(GL_TEXTURE_2D, source)
    gl.drawArrays(GL_TRIANGLE_STRIP, 0, QUAD_VERTICES)
    drawClipOverlays(clip, clip.quadHalfW, clip.quadHalfH, compositionUs)
    drawCompositionOverlays(compositionOverlays, compositionUs)
    gl.useProgram(program)
  }

  /**
   * Clears the framebuffer to the fill and draws no clip over it, for an output slot no clip
   * covers, like the gap in front of a primary track that starts late.
   *
   * A blurred fill has no frame to blur there, so its background passes are skipped and the slot
   * keeps the plain clear color. [snapshot] and [present] read the result like any drawn frame,
   * so a kept grade still packs the fill into ten-bit codes.
   *
   * The composition's overlays still draw over it, since they sit over the fill wherever it shows.
   *
   * @param compositionUs Where this slot sits on the output timeline, the same value [draw] takes,
   *   so a gap slot carries the clock a drawn one does.
   * @param compositionOverlays The render's [BrowserRender.compositionOverlays].
   */
  fun drawFill(
    compositionUs: Double,
    compositionOverlays: List<WebGlOverlay>,
  ) {
    this.compositionUs = compositionUs
    gl.bindFramebuffer(GL_FRAMEBUFFER, hdr?.outputFbo)
    gl.clear(GL_COLOR_BUFFER_BIT)
    drawCompositionOverlays(compositionOverlays, compositionUs)
    gl.useProgram(program)
  }

  /**
   * Drops every overlay texture uploaded so far, for a preview whose plan was replaced underneath it.
   */
  fun forgetOverlays() {
    overlayPass?.forget()
  }

  // A clip overlay takes the composition's grade and not the clip's, since it composites after the clip's own color
  // and the composition's color runs after the whole clip.
  private fun drawClipOverlays(
    clip: RenderedClip,
    halfWidth: Float,
    halfHeight: Float,
    compositionUs: Double,
  ) {
    if (clip.overlays.isEmpty()) return
    overlays().draw(
      clip.overlays,
      compositionUs,
      clip.overlayMatrix.at(compositionUs.microseconds),
      halfWidth,
      halfHeight,
      clip.compositionColorMatrix,
    )
  }

  private fun drawCompositionOverlays(
    overlays: List<WebGlOverlay>,
    compositionUs: Double,
  ) {
    if (overlays.isEmpty()) return
    overlays().draw(overlays, compositionUs, IDENTITY_3, 1f, 1f, IDENTITY_4)
  }

  private fun overlays(): BrowserOverlayPass =
    overlayPass ?: BrowserOverlayPass.create(gl, buffer, hdrTransfer).also { overlayPass = it }

  private fun toneMap(): ToneMapPass =
    toneMapPass ?: buildToneMapPass(gl, width, height, buffer).also { toneMapPass = it }

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
   * The letterboxed clip's background: the frame scaled to cover the output, blurred across two
   * passes and dimmed, drawn straight into the target ahead of the sharp foreground.
   *
   * The background is the clip's own pixels, so it is drawn with the clip's color matrix still set.
   * Every other backend splits the frame for its blur after the effect chain has run, and dimming
   * only the sharp copy would leave a bright halo around a dimmed frame. The clip's overlays are part
   * of that chain, so they go into the cover pass too, at the cover geometry.
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
    compositionUs: Double,
  ) {
    gl.useProgram(program)
    gl.viewport(0, 0, blur.smallWidth, blur.smallHeight)
    gl.bindFramebuffer(GL_FRAMEBUFFER, blur.coverFbo)
    gl.clear(GL_COLOR_BUFFER_BIT)
    gl.activeTexture(GL_TEXTURE0)
    gl.bindTexture(GL_TEXTURE_2D, source)
    gl.uniform2f(quadScale, clip.coverHalfW, clip.coverHalfH)
    gl.drawArrays(GL_TRIANGLE_STRIP, 0, QUAD_VERTICES)
    drawClipOverlays(clip, clip.coverHalfW, clip.coverHalfH, compositionUs)

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
      pass.pack(gl, program),
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
    hdr?.present(gl, program)
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

  fun release() {
    overlayPass?.release()
    toneMapPass?.release(gl)
    hdr?.release(gl)
    blur?.release(gl)
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

      val program = linkedProgram(gl, COMPOSITE_VERTEX_SHADER, compositeFragmentShader(hdrTransfer))
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
      gl.enable(GL_BLEND)
      gl.blendOntoFill()

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
          canvasUniforms = ClipCanvasUniforms.of(gl, program),
          blur = blur,
          hdr = hdr,
          hdrTransfer = hdrTransfer,
        )
      if (hdrTransfer != null) {
        gl.useProgram(program)
        uploadGradeUniforms(gl, program, hdrTransfer)
      }
      return compositor
    }

    private const val NO_CONTEXT = "The page gave out no WebGL2 context, so there is nothing to composite on."
    private const val NO_BUFFER = "WebGL2 refused to create a vertex buffer."
    private const val NO_FLOAT_BUFFER =
      "This context cannot render into a float framebuffer, so an HDR grade has nowhere to " +
        "composite. EXT_color_buffer_float is missing."
  }
}

/**
 * The clear color [Fill.Solid] paints outright. A blurred fill has no single color of its own, so
 * every pixel it reaches is drawn over before the frame is handed back, and this is only what a
 * stray uncovered one or a slot with no clip at all falls back to.
 *
 * On a kept grade the color is linear light rather than an encoded value, held where
 * [compositorLightOf] puts it, which is the same place an overlay texel of that color lands.
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
        compositorLightOf(authoredHdrNits(color))
      }
    }
    is Fill.Blurred -> {
      floatArrayOf(0f, 0f, 0f)
    }
  }

private val UNIT_QUAD = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
private val IDENTITY_3 = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
private val IDENTITY_4 = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val BYTE_MASK = 0xFF
private const val MAX_CHANNEL = 255f

private const val COLOR_BUFFER_FLOAT = "EXT_color_buffer_float"
private const val LOSE_CONTEXT = "WEBGL_lose_context"

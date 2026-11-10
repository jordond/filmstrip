@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal.render

import dev.jordond.filmstrip.effect.WebGlOverlay
import dev.jordond.filmstrip.effect.WebGlPass
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.webcodecs.internal.BrowserRender
import dev.jordond.filmstrip.webcodecs.internal.ClipPassStage
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
internal class BrowserCompositor(
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
  private val clearColor: FloatArray,
) {
  private var current: RenderedClip? = null
  private var overlayPass: BrowserOverlayPass? = null
  private var toneMapPass: ToneMapPass? = null
  private val shaderPass = ShaderPass(gl, buffer, width, height, hdrTransfer != null)
  private val clipFrames = PassFrames(gl, hdrTransfer != null, FrameAlpha.Straight)
  private val compositionFrames = PassFrames(gl, hdrTransfer != null, FrameAlpha.Straight)
  private var underFillStage: UnderFillStage? = null
  private var sdPrimariesPass: SdPrimariesPass? = null

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
    uploadClip(clip)
  }

  private fun uploadClip(clip: RenderedClip) {
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
   * A clip with a shader pass runs it ahead of all this, on a frame of its own, and the composite samples the result in
   * place of the decoded frame. The composition's shader passes need a frame they can read, so the composite draws into
   * one of [compositionFrames] when there are any and they write the target.
   *
   * The composition's passes under the fill need a frame they can read, so a clip with any is drawn on one of an
   * [UnderFillStage] instead, they run over that, and the result is laid over the fill. A blurred background is part of
   * the picture they run on, and a color fill is not, so that frame starts clear under a color fill.
   *
   * The clip's overlays go on right after the composite, inside its quad, and after the composition's passes the
   * composition's go on last over the whole frame.
   *
   * Suspends only on a grade or a tone mapped clip, where the frame's planes are copied out of the
   * decoder before anything can be uploaded.
   *
   * @param compositionUs Where this slot sits on the output timeline. Everything [clip] sets holds
   *   for the length of a clip, so this is where a pass that varies with time uploads its per-frame
   *   uniforms.
   * @param compositionOverlays The render's [BrowserRender.compositionOverlays].
   * @param compositionPasses The render's [BrowserRender.compositionPasses].
   */
  suspend fun draw(
    frame: VideoSample,
    compositionUs: Double,
    compositionOverlays: List<WebGlOverlay>,
    compositionPasses: List<WebGlPass>,
  ) {
    this.compositionUs = compositionUs
    val clip = checkNotNull(current) { "clip() must run before draw()" }
    val toneMapFrom = clip.toneMapFrom
    val decoded =
      when {
        hdr != null -> hdr.unpack(gl, frame, program)
        toneMapFrom != null -> toneMap().toneMap(gl, frame, toneMapFrom, program)
        else -> uploadImage(frame)
      }
    val source = clip.passStage?.let { drawClipStage(clip, it, decoded, compositionUs) } ?: decoded
    if (clip.matrix.varies) {
      gl.useProgram(program)
      gl.uniformMatrix3fv(texMatrix, false, clip.matrix.at(compositionUs.microseconds).toFloat32Array())
    }
    if (clip.canvases.any { it.contentMatrix.varies }) {
      gl.useProgram(program)
      canvasUniforms.uploadAt(gl, clip.canvases, compositionUs.microseconds)
    }
    val target = targetFor(compositionPasses)

    if (clip.passesUnderFill.isEmpty()) {
      drawFillUnder(clip, source, target, compositionUs)
      drawPicture(clip, source, compositionUs)
    } else {
      val stage = underFill()
      if (blur != null && clip.hasBars) {
        drawFillUnder(clip, source, stage.frame(width, height), compositionUs)
      } else {
        stage.open(width, height, clearColor)
      }
      drawPicture(clip, source, compositionUs)
      val picture = stage.run(shaderPass, clip.passesUnderFill, width, height, compositionUs.microseconds)
      gl.bindFramebuffer(GL_FRAMEBUFFER, target)
      gl.clear(GL_COLOR_BUFFER_BIT)
      stage.lay(picture)
    }
    finishComposition(compositionPasses, compositionOverlays, compositionUs)
  }

  // Clears the target to the fill, or draws a blurred fill's background into it, and leaves it bound with the clip's
  // contained geometry set.
  private fun drawFillUnder(
    clip: RenderedClip,
    source: JsAny,
    target: JsAny?,
    compositionUs: Double,
  ) {
    if (blur != null && clip.hasBars) {
      drawBackground(blur, clip, source, target, compositionUs)
      // The cover pass above reused this program's quad uniforms for its own geometry. Put the
      // clip's contained geometry back before the sharp draw.
      gl.useProgram(program)
      gl.uniform2f(quadScale, clip.quadHalfW, clip.quadHalfH)
    } else {
      gl.bindFramebuffer(GL_FRAMEBUFFER, target)
      gl.clear(GL_COLOR_BUFFER_BIT)
    }
  }

  // The sharp clip and its overlays, into the bound framebuffer.
  private fun drawPicture(
    clip: RenderedClip,
    source: JsAny,
    compositionUs: Double,
  ) {
    gl.useProgram(program)
    gl.activeTexture(GL_TEXTURE0)
    gl.bindTexture(GL_TEXTURE_2D, source)
    gl.drawArrays(GL_TRIANGLE_STRIP, 0, QUAD_VERTICES)
    drawClipOverlays(clip, clip.quadHalfW, clip.quadHalfH, compositionUs)
  }

  /**
   * Clears the framebuffer to the fill and draws no clip over it, for an output slot no clip
   * covers, like the gap in front of a primary track that starts late.
   *
   * A blurred fill has no frame to blur there, so its background passes are skipped and the slot
   * keeps the plain clear color. [snapshot] and [present] read the result like any drawn frame,
   * so a kept grade still packs the fill into ten-bit codes.
   *
   * The composition's passes and overlays still draw over it, since they sit over the fill wherever it shows.
   *
   * @param compositionUs Where this slot sits on the output timeline, the same value [draw] takes,
   *   so a gap slot carries the clock a drawn one does.
   * @param compositionOverlays The render's [BrowserRender.compositionOverlays].
   * @param compositionPasses The render's [BrowserRender.compositionPasses].
   */
  fun drawFill(
    compositionUs: Double,
    compositionOverlays: List<WebGlOverlay>,
    compositionPasses: List<WebGlPass>,
  ) {
    this.compositionUs = compositionUs
    gl.bindFramebuffer(GL_FRAMEBUFFER, targetFor(compositionPasses))
    gl.clear(GL_COLOR_BUFFER_BIT)
    finishComposition(compositionPasses, compositionOverlays, compositionUs)
  }

  // Where the composite draws: the output, or a frame the composition's passes can read when there are any.
  private fun targetFor(compositionPasses: List<WebGlPass>): JsAny? =
    if (compositionPasses.isEmpty()) hdr?.outputFbo else compositionFrames.read(width, height).framebuffer

  private fun finishComposition(
    compositionPasses: List<WebGlPass>,
    compositionOverlays: List<WebGlOverlay>,
    compositionUs: Double,
  ) {
    if (compositionPasses.isNotEmpty()) {
      val output = hdr?.outputFbo
      shaderPass.runOnto(compositionPasses, compositionFrames, output, width, height, compositionUs.microseconds)
    }
    drawCompositionOverlays(compositionOverlays, compositionUs)
    gl.useProgram(program)
  }

  // Draws the clip's chain so far on a clear frame of its own and runs its passes over that, leaving the composite's
  // uniforms holding what the clip draws with from there. Blending stays off, so a bar's alpha reaches the passes.
  private fun drawClipStage(
    clip: RenderedClip,
    stage: ClipPassStage,
    decoded: JsAny,
    compositionUs: Double,
  ): JsAny {
    val time = compositionUs.microseconds
    gl.useProgram(program)
    gl.uniformMatrix3fv(texMatrix, false, stage.matrix.at(time).toFloat32Array())
    gl.uniform2f(quadScale, 1f, 1f)
    gl.uniformMatrix4fv(colorMatrix, false, stage.colorMatrix.toFloat32Array())
    gl.uniformMatrix4fv(compositionColorMatrix, false, IDENTITY_4.toFloat32Array())
    canvasUniforms.upload(gl, stage.canvases)
    canvasUniforms.uploadAt(gl, stage.canvases, time)

    gl.bindFramebuffer(GL_FRAMEBUFFER, clipFrames.read(stage.width, stage.height).framebuffer)
    gl.viewport(0, 0, stage.width, stage.height)
    gl.clearColor(0f, 0f, 0f, 0f)
    gl.clear(GL_COLOR_BUFFER_BIT)
    gl.clearColor(clearColor[0], clearColor[1], clearColor[2], 1f)
    gl.disable(GL_BLEND)
    gl.activeTexture(GL_TEXTURE0)
    gl.bindTexture(GL_TEXTURE_2D, decoded)
    gl.drawArrays(GL_TRIANGLE_STRIP, 0, QUAD_VERTICES)
    gl.enable(GL_BLEND)
    gl.viewport(0, 0, width, height)

    val result = shaderPass.run(stage.passes, clipFrames, stage.width, stage.height, time)
    uploadClip(clip)
    return result
  }

  /**
   * The texture of every table held right now.
   */
  val tableTextures: List<JsAny> get() = shaderPass.tableTextures

  /**
   * Drops every overlay and table texture uploaded so far, for a preview whose plan was replaced underneath it.
   */
  fun forgetUploads() {
    overlayPass?.forget()
    shaderPass.forgetTables()
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

  private fun underFill(): UnderFillStage =
    underFillStage ?: UnderFillStage.create(gl, buffer, hdrTransfer != null).also { underFillStage = it }

  private fun toneMap(): ToneMapPass =
    toneMapPass ?: buildToneMapPass(gl, width, height, buffer).also { toneMapPass = it }

  /**
   * The SDR upload: the decoded frame straight into one RGBA texture.
   *
   * `UNPACK_FLIP_Y_WEBGL` is what makes the upload match the +Y-up texture convention every
   * resolver already lowers to. Without it the source's top row lands at `t = 0`, which the shader
   * maps to the bottom of the frame, and every export comes out upside down.
   *
   * An SD frame uploads with the browser's colour conversion off, which still decodes its real matrix, and goes through
   * [SdPrimariesPass] from there. Left on, Chrome moves EBU primaries itself and leaves SMPTE C's where they are.
   */
  private fun uploadImage(frame: VideoSample): JsAny {
    val target = checkNotNull(texture) { "an SDR compositor always has an upload texture" }
    gl.activeTexture(GL_TEXTURE0)
    gl.bindTexture(GL_TEXTURE_2D, target)
    gl.pixelStorei(GL_UNPACK_FLIP_Y_WEBGL, 1)
    val image = frame.toVideoFrame()
    val primaries = sdPrimariesCodePoint(image.colorSpace.primaries?.toString())
    if (primaries != null) gl.pixelStorei(GL_UNPACK_COLORSPACE_CONVERSION_WEBGL, GL_NONE)
    try {
      gl.texImage2D(GL_TEXTURE_2D, 0, GL_RGBA, GL_RGBA, GL_UNSIGNED_BYTE, image)
    } finally {
      gl.pixelStorei(GL_UNPACK_COLORSPACE_CONVERSION_WEBGL, GL_BROWSER_DEFAULT_WEBGL)
      image.close()
    }
    return primaries?.let { sdPrimaries().moved(gl, target, frame.codedWidth, frame.codedHeight, it, program) }
      ?: target
  }

  private fun sdPrimaries(): SdPrimariesPass =
    sdPrimariesPass ?: buildSdPrimariesPass(gl, width, height, buffer).also { sdPrimariesPass = it }

  /**
   * The letterboxed clip's background: the frame scaled to cover the output, blurred by the fill's
   * shared plan and dimmed, drawn straight into the target ahead of the sharp foreground.
   *
   * The background is the clip's own pixels, so it is drawn with the clip's color matrix still set.
   * Every other backend splits the frame for its blur after the effect chain has run, and dimming
   * only the sharp copy would leave a bright halo around a dimmed frame. The clip's overlays are part
   * of that chain, so they go into the cover pass too, at the cover geometry.
   *
   * The cover is drawn at the output size, so [BlurPass] boxes down the cover crop and holds its edge.
   */
  private fun drawBackground(
    blur: BlurPass,
    clip: RenderedClip,
    source: JsAny,
    target: JsAny?,
    compositionUs: Double,
  ) {
    gl.useProgram(program)
    gl.viewport(0, 0, width, height)
    gl.bindFramebuffer(GL_FRAMEBUFFER, blur.coverFbo)
    gl.clear(GL_COLOR_BUFFER_BIT)
    gl.activeTexture(GL_TEXTURE0)
    gl.bindTexture(GL_TEXTURE_2D, source)
    gl.uniform2f(quadScale, clip.coverHalfW, clip.coverHalfH)
    gl.drawArrays(GL_TRIANGLE_STRIP, 0, QUAD_VERTICES)
    drawClipOverlays(clip, clip.coverHalfW, clip.coverHalfH, compositionUs)
    blur.blurCoverOnto(gl, target, width, height)
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
    shaderPass.release()
    clipFrames.release()
    compositionFrames.release()
    underFillStage?.release()
    sdPrimariesPass?.release(gl)
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
    ): BrowserCompositor = compositorOf(width, height, fill, hdrTransfer)
  }
}

private val IDENTITY_3 = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
private val IDENTITY_4 = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)

private const val LOSE_CONTEXT = "WEBGL_lose_context"

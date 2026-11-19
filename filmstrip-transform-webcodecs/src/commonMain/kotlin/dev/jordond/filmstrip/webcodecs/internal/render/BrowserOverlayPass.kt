@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal.render

import dev.jordond.filmstrip.color.PictureColor
import dev.jordond.filmstrip.color.authoredHdrTexels
import dev.jordond.filmstrip.color.pictureLight
import dev.jordond.filmstrip.color.pictureSrgbCodes
import dev.jordond.filmstrip.effect.OverlaySource
import dev.jordond.filmstrip.effect.WebGlOverlay
import dev.jordond.filmstrip.effects.color.HDR_COLOR_MATRIX_GLSL
import dev.jordond.filmstrip.effects.color.displayLightGradeGlsl
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.webcodecs.internal.BrowserExportFailure
import dev.jordond.filmstrip.webcodecs.internal.interop.JsOptions
import dev.jordond.filmstrip.webcodecs.internal.interop.OffscreenCanvas
import dev.jordond.filmstrip.webcodecs.internal.interop.Uint8Array
import dev.jordond.filmstrip.webcodecs.internal.interop.WebGl2
import dev.jordond.filmstrip.webcodecs.internal.interop.toByteArray
import dev.jordond.filmstrip.webcodecs.internal.interop.toFloat32Array
import dev.jordond.filmstrip.webcodecs.internal.interop.toUint8Array
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.time.Duration.Companion.microseconds

/**
 * The overlay program a [BrowserCompositor] draws rasters with, and the textures it draws them from.
 *
 * Each overlay is a raster the resolver already drew. It is uploaded once, on the first frame that draws it, and
 * drawn as a premultiplied source over at the rect and the opacity its [WebGlOverlay.drawAt] answers for the frame's
 * composition time. The opacity multiplies color and alpha once, on the frame's own codes, and nothing here works
 * out a placement or an opacity of its own.
 *
 * A draw that names a [dev.jordond.filmstrip.effect.WebGlOverlayDraw.cell] reads that rectangle of the one uploaded
 * raster, stretched over the rect. The read is held half a texel inside the rectangle, so the texels of the cell beside
 * it are never blended in at any scale.
 *
 * A raster of eight-bit [OverlaySource.Codes], which is how an animated picture's atlas arrives, is converted and
 * uploaded a band of rows at a time, so it is never held in the page beside a converted copy of itself.
 *
 * On a kept grade the raster is mapped to linear display light before it is uploaded, so each texel lands where a
 * fill of its color does, and it blends with the frame in the linear display light the compositor holds on either
 * transfer.
 *
 * A raster carrying a [WebGlOverlay.color] is converted from its stored codes before it is uploaded, to the sRGB codes
 * [pictureSrgbCodes] gives it on SDR and to the light [pictureLight] gives it on a kept grade. A raster whose
 * [WebGlOverlay.source] carries samples, a PNG tagged or not, an evaluated AVIF or text, is converted from them at
 * their full depth, and any other raster from its image's codes read back through a WebGL2 framebuffer.
 */
internal class BrowserOverlayPass private constructor(
  private val gl: WebGl2,
  private val hdrTransfer: HdrTransfer?,
  private val program: JsAny,
  private val frameMatrix: JsAny?,
  private val quadScale: JsAny?,
  private val rect: JsAny?,
  private val opacity: JsAny?,
  private val colorMatrix: JsAny?,
  private val textureMatrix: JsAny?,
  private val window: JsAny?,
  private val halfTexel: JsAny?,
) {
  private val textures = mutableMapOf<WebGlOverlay, JsAny>()

  /**
   * Draws every one of [overlays] that shows at [compositionUs] into the bound framebuffer and viewport, in order.
   *
   * Leaves this pass's program bound, so the caller binds its own again before drawing anything else.
   *
   * @param frameMatrix Maps the quad onto the frame the overlays were placed on, as the nine floats of a column-major
   * `mat3`. A fragment it maps outside an overlay's rect is left alone.
   * @param halfWidth Half the quad's width in clip space, which is the picture the overlays were placed on.
   * @param halfHeight The same, for height.
   * @param colorMatrix The grade the overlays take, as the sixteen floats of a column-major `mat4`.
   */
  fun draw(
    overlays: List<WebGlOverlay>,
    compositionUs: Double,
    frameMatrix: FloatArray,
    halfWidth: Float,
    halfHeight: Float,
    colorMatrix: FloatArray,
  ) {
    val time = compositionUs.microseconds
    val drawn = overlays.mapNotNull { overlay -> overlay.drawAt(time)?.let { overlay to it } }
    if (drawn.isEmpty()) return

    gl.useProgram(program)
    gl.uniformMatrix3fv(this.frameMatrix, false, frameMatrix.toFloat32Array())
    gl.uniform2f(quadScale, halfWidth, halfHeight)
    gl.uniformMatrix4fv(this.colorMatrix, false, colorMatrix.toFloat32Array())
    gl.blendFuncSeparate(GL_ONE, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
    gl.activeTexture(GL_TEXTURE0)
    for ((overlay, draw) in drawn) {
      gl.bindTexture(GL_TEXTURE_2D, textureOf(overlay))
      gl.uniform4f(rect, draw.rect.left, draw.rect.top, draw.rect.width, draw.rect.height)
      gl.uniform1f(opacity, draw.opacity)
      gl.uniformMatrix3fv(textureMatrix, false, textureMatrixOf(overlay.orientation, overlay.size).toFloat32Array())
      val cell = cellWindowOf(draw.cell, overlay.orientation.orientedSize(overlay.size))
      gl.uniform4f(window, cell[0], cell[1], cell[2], cell[3])
      gl.uniform2f(halfTexel, cell[4], cell[5])
      gl.drawArrays(GL_TRIANGLE_STRIP, 0, QUAD_VERTICES)
    }
    gl.blendOntoFill()
  }

  /**
   * Deletes every uploaded texture, so the overlays of a plan that replaced this one upload afresh.
   */
  fun forget() {
    textures.values.forEach { gl.deleteTexture(it) }
    textures.clear()
  }

  /**
   * Deletes every uploaded texture and the overlay program. The compositor calls it once from its own
   * release, and the pass draws nothing after it.
   */
  fun release() {
    forget()
    gl.deleteProgram(program)
  }

  // Uploaded top row first, which is where the shader reads the top of the rect from.
  private fun textureOf(overlay: WebGlOverlay): JsAny =
    textures.getOrPut(overlay) {
      val texture = gl.createTexture() ?: throw BrowserExportFailure(NO_TEXTURE)
      gl.bindTexture(GL_TEXTURE_2D, texture)
      gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
      gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
      gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
      gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
      gl.pixelStorei(GL_UNPACK_FLIP_Y_WEBGL, 0)
      if (hdrTransfer == null) uploadCodes(overlay) else uploadLight(overlay)
      texture
    }

  // With no color conversion, so an authored color lands as its own codes. A PNG's samples and a tagged picture's
  // codes are converted first, and every raster is premultiplied by the same unpack flag on the way up. A bitmap is
  // never handed to the texture itself, since an upload from one ignores that flag.
  private fun uploadCodes(overlay: WebGlOverlay) {
    gl.pixelStorei(GL_UNPACK_PREMULTIPLY_ALPHA_WEBGL, 1)
    gl.pixelStorei(GL_UNPACK_COLORSPACE_CONVERSION_WEBGL, GL_NONE)
    try {
      val size = overlay.size
      val color = overlay.color
      when (val source = overlay.source) {
        is OverlaySource.Samples -> {
          uploadBytes(size, sdrOverlayCodes(source.samples, color))
        }
        is OverlaySource.Codes -> {
          uploadInBands(size, GL_RGBA, GL_UNSIGNED_BYTE) { row, rows ->
            val band = source.codes.copyOfRange(row * size.width * TEXEL_BYTES, (row + rows) * size.width * TEXEL_BYTES)
            (if (color == null) band else sdrOverlayCodes(band, color)).toUint8Array()
          }
        }
        is OverlaySource.Image -> {
          val codes = straightCodesOf(source.image, size)
          uploadBytes(size, if (color == null) codes else sdrOverlayCodes(codes, color))
        }
      }
    } finally {
      gl.pixelStorei(GL_UNPACK_PREMULTIPLY_ALPHA_WEBGL, 0)
      gl.pixelStorei(GL_UNPACK_COLORSPACE_CONVERSION_WEBGL, GL_BROWSER_DEFAULT_WEBGL)
    }
  }

  private fun uploadBytes(
    size: Size,
    codes: ByteArray,
  ) {
    val upload = codes.toUint8Array()
    gl.texImage2D(GL_TEXTURE_2D, 0, GL_RGBA, size.width, size.height, 0, GL_RGBA, GL_UNSIGNED_BYTE, upload)
  }

  // The raster's straight codes are mapped here and uploaded as half floats, so the texels arrive premultiplied in the
  // light the frame holds.
  private fun uploadLight(overlay: WebGlOverlay) {
    val size = overlay.size
    when (val source = overlay.source) {
      is OverlaySource.Samples -> {
        uploadLight(size, hdrOverlayTexels(source.samples, overlay.color))
      }
      is OverlaySource.Codes -> {
        uploadInBands(size, GL_RGBA16F, GL_FLOAT) { row, rows ->
          val band = source.codes.copyOfRange(row * size.width * TEXEL_BYTES, (row + rows) * size.width * TEXEL_BYTES)
          hdrOverlayTexels(band, overlay.color).toFloat32Array()
        }
      }
      is OverlaySource.Image -> {
        uploadLight(size, hdrOverlayTexels(straightCodesOf(source.image, size), overlay.color))
      }
    }
  }

  private fun uploadLight(
    size: Size,
    light: FloatArray,
  ) {
    gl.texImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, size.width, size.height, 0, GL_RGBA, GL_FLOAT, light.toFloat32Array())
  }

  // The texture is allocated whole and written a band of rows at a time, so a raster as large as an atlas never has
  // more than one band converted and copied into the page at once.
  private inline fun uploadInBands(
    size: Size,
    internalFormat: Int,
    type: Int,
    band: (row: Int, rows: Int) -> JsAny,
  ) {
    gl.texImage2D(GL_TEXTURE_2D, 0, internalFormat, size.width, size.height, 0, GL_RGBA, type, null)
    val step = maxOf(1, BAND_TEXELS / size.width)
    var row = 0
    while (row < size.height) {
      val rows = minOf(step, size.height - row)
      gl.texSubImage2D(GL_TEXTURE_2D, 0, 0, row, size.width, rows, GL_RGBA, type, band(row, rows))
      row += rows
    }
  }

  companion object {
    /**
     * Links the overlay program against the compositor's own quad [buffer].
     *
     * @param hdrTransfer The transfer the compositor keeps a grade with, or null for SDR. It settles how every raster
     * is uploaded and graded for the life of the pass.
     */
    fun create(
      gl: WebGl2,
      buffer: JsAny,
      hdrTransfer: HdrTransfer?,
    ): BrowserOverlayPass {
      val program = linkedProgram(gl, OVERLAY_VERTEX_SHADER, overlayFragmentShader(hdrTransfer))
      gl.useProgram(program)
      gl.bindBuffer(GL_ARRAY_BUFFER, buffer)
      val position = gl.getAttribLocation(program, "aPosition")
      gl.enableVertexAttribArray(position)
      gl.vertexAttribPointer(position, 2, GL_FLOAT, false, 0, 0)
      gl.uniform1i(gl.getUniformLocation(program, "uOverlay"), 0)
      // The same figures the compositor grades a clip through, since the texture holds display light with reference
      // white at one just as the frame does.
      hdrTransfer?.let { uploadGradeUniforms(gl, program, it) }

      return BrowserOverlayPass(
        gl = gl,
        hdrTransfer = hdrTransfer,
        program = program,
        frameMatrix = gl.getUniformLocation(program, "uFrameMatrix"),
        quadScale = gl.getUniformLocation(program, "uQuadScale"),
        rect = gl.getUniformLocation(program, "uRect"),
        opacity = gl.getUniformLocation(program, "uOpacity"),
        colorMatrix = gl.getUniformLocation(program, "uColorMatrix"),
        textureMatrix = gl.getUniformLocation(program, "uTextureMatrix"),
        window = gl.getUniformLocation(program, "uWindow"),
        halfTexel = gl.getUniformLocation(program, "uHalfTexel"),
      )
    }

    private const val NO_TEXTURE = "WebGL2 refused to create a texture for an overlay."
  }
}

/**
 * The straight codes of [image], four bytes a texel, red, green, blue then alpha, row by row from the top left, for an
 * image of [size].
 *
 * The image is uploaded to a texture of a context of its own with no premultiplying and read back through a
 * framebuffer, so a bitmap decoded without premultiplying gives back the codes it stores at any alpha, and the
 * compositor's own context is left as it was. A bitmap stores eight bits a channel, so a picture stored deeper in any
 * format but PNG reaches here at eight bits. That is the browser's limit.
 *
 * @throws BrowserExportFailure when the browser refuses a context or a framebuffer for the read.
 */
internal fun straightCodesOf(
  image: JsAny,
  size: Size,
): ByteArray {
  val read = OffscreenCanvas(1, 1).getContext(WEBGL2, JsOptions().build()) ?: throw BrowserExportFailure(NO_READBACK)
  try {
    val texture = read.createTexture() ?: throw BrowserExportFailure(NO_READBACK)
    read.bindTexture(GL_TEXTURE_2D, texture)
    read.pixelStorei(GL_UNPACK_FLIP_Y_WEBGL, 0)
    read.pixelStorei(GL_UNPACK_PREMULTIPLY_ALPHA_WEBGL, 0)
    read.pixelStorei(GL_UNPACK_COLORSPACE_CONVERSION_WEBGL, GL_NONE)
    read.texImage2D(GL_TEXTURE_2D, 0, GL_RGBA, GL_RGBA, GL_UNSIGNED_BYTE, image)
    val framebuffer = read.createFramebuffer() ?: throw BrowserExportFailure(NO_READBACK)
    read.bindFramebuffer(GL_FRAMEBUFFER, framebuffer)
    read.framebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
    if (read.checkFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) throw BrowserExportFailure(NO_READBACK)
    val pixels = Uint8Array(size.width * size.height * TEXEL_BYTES)
    read.readPixels(0, 0, size.width, size.height, GL_RGBA, GL_UNSIGNED_BYTE, pixels)
    return pixels.toByteArray()
  } finally {
    read.getExtension(LOSE_CONTEXT)?.loseContext()
  }
}

/**
 * Maps straight RGBA [codes], four bytes a texel, to the premultiplied texels an overlay uploads on a kept grade.
 *
 * Each color lands where [pictureLight] puts it for [color], which is where [authoredHdrTexels] puts an untagged
 * one, then where [compositorLightOf] holds that light, and only then is it multiplied by its alpha.
 *
 * @param color How the picture describes its codes, or null when they are untagged sRGB.
 * @return Four floats a texel, red, green, blue and alpha, in the order the codes came in.
 */
internal fun hdrOverlayTexels(
  codes: ByteArray,
  color: PictureColor? = null,
): FloatArray = premultipliedLight(pictureLight(color, argbOf(codes)))

/**
 * Maps straight sixteen-bit RGBA [samples], four a texel, to the premultiplied texels an overlay uploads on a kept
 * grade.
 *
 * The same mapping the eight-bit [hdrOverlayTexels] runs, with each color landing where the sixteen-bit [pictureLight]
 * puts it, so no sample is cut to eight bits first.
 *
 * @param color How the picture describes its samples, or null when they are untagged sRGB.
 * @return Four floats a texel, red, green, blue and alpha, in the order the samples came in.
 */
internal fun hdrOverlayTexels(
  samples: ShortArray,
  color: PictureColor?,
): FloatArray = premultipliedLight(pictureLight(color, samples))

private fun premultipliedLight(texels: FloatArray): FloatArray {
  for (offset in texels.indices step TEXEL_BYTES) {
    val alpha = texels[offset + 3]
    val light = compositorLightOf(texels.copyOfRange(offset, offset + 3))
    for (channel in 0 until 3) {
      texels[offset + channel] = light[channel] * alpha
    }
  }
  return texels
}

/**
 * Maps a tagged picture's straight RGBA [codes], four bytes a texel, to the straight sRGB codes an SDR overlay uploads.
 *
 * Each color lands where [pictureSrgbCodes] puts it for [color] and alpha is carried over unchanged.
 *
 * @return Four bytes a texel, red, green, blue and alpha, in the order the codes came in.
 */
internal fun sdrOverlayCodes(
  codes: ByteArray,
  color: PictureColor,
): ByteArray = rgbaOf(pictureSrgbCodes(color, argbOf(codes)))

/**
 * Maps straight sixteen-bit RGBA [samples], four a texel, to the straight sRGB codes an SDR overlay uploads.
 *
 * Each color lands where the sixteen-bit [pictureSrgbCodes] puts it for [color], which rounds an untagged sample to
 * its nearest code, and alpha is rounded the same way.
 *
 * @param color How the picture describes its samples, or null when they are untagged sRGB.
 * @return Four bytes a texel, red, green, blue and alpha, in the order the samples came in.
 */
internal fun sdrOverlayCodes(
  samples: ShortArray,
  color: PictureColor?,
): ByteArray = rgbaOf(pictureSrgbCodes(color, samples))

private fun rgbaOf(converted: IntArray): ByteArray {
  val out = ByteArray(converted.size * TEXEL_BYTES)
  converted.forEachIndexed { index, argb ->
    val offset = index * TEXEL_BYTES
    out[offset] = (argb ushr RED_SHIFT).toByte()
    out[offset + 1] = (argb ushr GREEN_SHIFT).toByte()
    out[offset + 2] = argb.toByte()
    out[offset + 3] = (argb ushr ALPHA_SHIFT).toByte()
  }
  return out
}

private fun argbOf(codes: ByteArray): IntArray =
  IntArray(codes.size / TEXEL_BYTES) { index ->
    val offset = index * TEXEL_BYTES
    (code(codes, offset + 3) shl ALPHA_SHIFT) or
      (code(codes, offset) shl RED_SHIFT) or
      (code(codes, offset + 1) shl GREEN_SHIFT) or
      code(codes, offset + 2)
  }

private fun code(
  codes: ByteArray,
  index: Int,
): Int = codes[index].toInt() and BYTE_MASK

// The quad is the picture's, and the frame matrix carries each of its points onto the frame the overlay was placed
// on, in texture space with +Y up.
private val OVERLAY_VERTEX_SHADER =
  """
  #version 300 es
  in vec2 aPosition;
  uniform mat3 uFrameMatrix;
  uniform vec2 uQuadScale;
  out vec2 vFrame;
  void main() {
    vec2 uv = aPosition * 0.5 + 0.5;
    vFrame = (uFrameMatrix * vec3(uv, 1.0)).xy;
    gl_Position = vec4(aPosition * uQuadScale, 0.0, 1.0);
  }
  """.trimIndent()

// The rect is measured from the top left with +Y down, the way every placement is, so the frame point is turned over
// before it is read against it. The texture matrix carries the point in the shown picture to the one in the uploaded
// raster, the way textureMatrixOf lays it out. A sprite sheet draws one window of the shown picture, and the point is
// held half a texel inside it before the matrix carries it over, so a texel beside the window is never blended in.
// A fragment outside the rect draws nothing, which is what cuts an
// overlay at the edge of the picture it was placed on. The texel is premultiplied, so the grade runs on the straight
// color and the result is premultiplied again by the texel's alpha and the opacity together. On a kept grade the
// straight color is light, and it goes through the arm a clip is graded with, in the light that arm grades in, which
// clamps it where the format runs out rather than at reference white.
private fun overlayFragmentShader(transfer: HdrTransfer?): String {
  val declarations =
    if (transfer == null) {
      ""
    } else {
      """
      uniform float uWhite;
      uniform float uDisplayGamma;
      uniform float uOotfGamma;
      uniform float uCeiling;
      $HDR_COLOR_MATRIX_GLSL
      ${transfer.displayLightGradeGlsl()}
      """
    }
  val grade =
    if (transfer == null) {
      "clamp((uColorMatrix * vec4(straight, 1.0)).rgb, 0.0, 1.0)"
    } else {
      "fromGradeLight(filmstripGradeHdr(" +
        "toGradeLight(straight), uColorMatrix, uWhite, uDisplayGamma, uOotfGamma, uCeiling))"
    }

  return """
    #version 300 es
    precision highp float;
    uniform sampler2D uOverlay;
    uniform vec4 uRect;
    uniform float uOpacity;
    uniform mat4 uColorMatrix;
    uniform mat3 uTextureMatrix;
    uniform vec4 uWindow;
    uniform vec2 uHalfTexel;
    in vec2 vFrame;
    out vec4 outColor;
    $declarations
    void main() {
      vec2 at = (vec2(vFrame.x, 1.0 - vFrame.y) - uRect.xy) / uRect.zw;
      if (any(lessThan(at, vec2(0.0))) || any(greaterThanEqual(at, vec2(1.0)))) discard;
      vec2 low = uWindow.xy + uHalfTexel;
      vec2 shown = clamp(uWindow.xy + at * uWindow.zw, low, uWindow.xy + uWindow.zw - uHalfTexel);
      vec4 texel = texture(uOverlay, (uTextureMatrix * vec3(shown, 1.0)).xy);
      vec3 straight = texel.a > 0.0 ? texel.rgb / texel.a : vec3(0.0);
      vec3 graded = $grade;
      float alpha = texel.a * uOpacity;
      outColor = vec4(graded * alpha, alpha);
    }
    """.trimIndent()
}

private const val NO_READBACK = "The browser refused a WebGL2 context to read an overlay's pixels back through."
private const val WEBGL2 = "webgl2"
private const val LOSE_CONTEXT = "WEBGL_lose_context"
private const val TEXEL_BYTES = 4
private const val ALPHA_SHIFT = 24
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val BYTE_MASK = 0xFF

// The texels of one band of an overlay upload, which holds a band of an atlas to about sixteen megabytes of floats.
private const val BAND_TEXELS = 1 shl 20

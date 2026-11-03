@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal.render

import dev.jordond.filmstrip.color.PictureColor
import dev.jordond.filmstrip.color.authoredHdrTexels
import dev.jordond.filmstrip.color.pictureLight
import dev.jordond.filmstrip.color.pictureSrgbCodes
import dev.jordond.filmstrip.effect.OverlaySource
import dev.jordond.filmstrip.effect.WebGlOverlay
import dev.jordond.filmstrip.effects.color.HDR_COLOR_MATRIX_GLSL
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.webcodecs.internal.BrowserExportFailure
import dev.jordond.filmstrip.webcodecs.internal.interop.ArrayBuffer
import dev.jordond.filmstrip.webcodecs.internal.interop.OffscreenCanvas
import dev.jordond.filmstrip.webcodecs.internal.interop.Uint8Array
import dev.jordond.filmstrip.webcodecs.internal.interop.WebGl2
import dev.jordond.filmstrip.webcodecs.internal.interop.toByteArray
import dev.jordond.filmstrip.webcodecs.internal.interop.toFloat32Array
import dev.jordond.filmstrip.webcodecs.internal.interop.toUint8Array
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsName
import kotlin.time.Duration.Companion.microseconds

/**
 * The overlay program a [BrowserCompositor] draws rasters with, and the textures it draws them from.
 *
 * Each overlay is a raster the resolver already drew. It is uploaded once, on the first frame that draws it, and
 * drawn as a premultiplied source over at the rect and the opacity its [WebGlOverlay.drawAt] answers for the frame's
 * composition time. The opacity multiplies color and alpha once, on the frame's own codes, and nothing here works
 * out a placement or an opacity of its own.
 *
 * On a kept grade the raster is mapped to linear display light before it is uploaded, so each texel lands where a
 * fill of its color does, and it blends with the frame in the linear display light the compositor holds on either
 * transfer.
 *
 * A raster carrying a [WebGlOverlay.color] is converted from its stored codes before it is uploaded, to the sRGB codes
 * [pictureSrgbCodes] gives it on SDR and to the light [pictureLight] gives it on a kept grade. A raster whose
 * [WebGlOverlay.source] carries samples, a PNG tagged or not, an evaluated AVIF or text, is converted from them at
 * their full depth, and any other raster from its image's codes read back through a 2D canvas.
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
  // codes are converted first, and premultiplied by the same unpack flag on the way up.
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
        is OverlaySource.Image -> {
          val samples = source.samples
          when {
            samples != null -> uploadBytes(size, sdrOverlayCodes(samples, color))
            color != null -> uploadBytes(size, sdrOverlayCodes(readBack(source.image, size), color))
            else -> gl.texImage2D(GL_TEXTURE_2D, 0, GL_RGBA, GL_RGBA, GL_UNSIGNED_BYTE, source.image)
          }
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
    val light =
      when (val source = overlay.source) {
        is OverlaySource.Samples -> {
          hdrOverlayTexels(source.samples, overlay.color)
        }
        is OverlaySource.Image -> {
          source.samples?.let { hdrOverlayTexels(it, overlay.color) }
            ?: hdrOverlayTexels(readBack(source.image, size), overlay.color)
        }
      }
    val texels = light.toFloat32Array()
    gl.texImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, size.width, size.height, 0, GL_RGBA, GL_FLOAT, texels)
  }

  // The image's straight codes, read back through a 2D canvas. A browser bitmap holds eight bits a channel, so a
  // picture stored deeper in any format but PNG reaches here at eight bits. That is the browser's limit.
  private fun readBack(
    image: JsAny,
    size: Size,
  ): ByteArray {
    val canvas = ReadbackCanvas(size.width, size.height)
    val context = canvas.getContext(CONTEXT_2D) ?: throw BrowserExportFailure(NO_READBACK)
    context.drawImage(image, 0.0, 0.0)
    return Uint8Array(context.getImageData(0, 0, size.width, size.height).data.buffer).toByteArray()
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
      )
    }

    private const val NO_TEXTURE = "WebGL2 refused to create a texture for an overlay."
    private const val NO_READBACK = "The browser refused a 2D canvas to read an overlay's pixels back through."
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

/**
 * An OffscreenCanvas asked for a 2D context, which the compositor's own binding types as WebGL only.
 *
 * A raster that carries no samples is drawn into one to read its codes back.
 */
@JsName("OffscreenCanvas")
internal external class ReadbackCanvas(
  width: Int,
  height: Int,
) : JsAny {
  fun getContext(contextId: String): ReadbackContext?
}

internal external interface ReadbackContext : JsAny {
  fun drawImage(
    image: JsAny,
    dx: Double,
    dy: Double,
  )

  fun getImageData(
    sx: Int,
    sy: Int,
    sw: Int,
    sh: Int,
  ): ReadbackImage
}

internal external interface ReadbackImage : JsAny {
  val data: ReadbackBytes
}

internal external interface ReadbackBytes : JsAny {
  val buffer: ArrayBuffer
}

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
// raster, the way textureMatrixOf lays it out. A fragment outside the rect draws nothing, which is what cuts an
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
      ${gradeLightGlsl(transfer)}
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
    in vec2 vFrame;
    out vec4 outColor;
    $declarations
    void main() {
      vec2 at = (vec2(vFrame.x, 1.0 - vFrame.y) - uRect.xy) / uRect.zw;
      if (any(lessThan(at, vec2(0.0))) || any(greaterThanEqual(at, vec2(1.0)))) discard;
      vec4 texel = texture(uOverlay, (uTextureMatrix * vec3(at, 1.0)).xy);
      vec3 straight = texel.a > 0.0 ? texel.rgb / texel.a : vec3(0.0);
      vec3 graded = $grade;
      float alpha = texel.a * uOpacity;
      outColor = vec4(graded * alpha, alpha);
    }
    """.trimIndent()
}

private const val CONTEXT_2D = "2d"
private const val TEXEL_BYTES = 4
private const val ALPHA_SHIFT = 24
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val BYTE_MASK = 0xFF

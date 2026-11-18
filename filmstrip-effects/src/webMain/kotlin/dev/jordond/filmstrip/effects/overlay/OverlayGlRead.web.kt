@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.media.ArrayBuffer
import dev.jordond.filmstrip.media.ImageBitmap
import dev.jordond.filmstrip.media.toByteArray
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsName

/**
 * The straight codes of [bitmap], four bytes a texel, red, green, blue then alpha, row by row from the top left, or
 * null when no WebGL2 context is to be had or the bitmap is wider than a texture may be.
 *
 * The bitmap is uploaded to a texture with no premultiplying and no color conversion and read back through a
 * framebuffer, so a bitmap decoded with `premultiplyAlpha` set to `none` gives back the codes it was stored with at any
 * alpha. A 2D canvas holds premultiplied texels, which loses color wherever alpha is below its top.
 */
internal fun straightCodesOf(bitmap: ImageBitmap): ByteArray? {
  val width = bitmap.width
  val height = bitmap.height
  if (width <= 0 || height <= 0 || width.toLong() * height * TEXEL_BYTES > Int.MAX_VALUE) return null
  val gl = GlReadCanvas(1, 1).getContext(WEBGL2, JSON.parse(GL_OPTIONS)) ?: return null
  try {
    val texture = gl.createTexture() ?: return null
    gl.bindTexture(GL_TEXTURE_2D, texture)
    gl.pixelStorei(GL_UNPACK_FLIP_Y_WEBGL, 0)
    gl.pixelStorei(GL_UNPACK_PREMULTIPLY_ALPHA_WEBGL, 0)
    gl.pixelStorei(GL_UNPACK_COLORSPACE_CONVERSION_WEBGL, 0)
    gl.texImage2D(GL_TEXTURE_2D, 0, GL_RGBA, GL_RGBA, GL_UNSIGNED_BYTE, bitmap)
    val framebuffer = gl.createFramebuffer() ?: return null
    gl.bindFramebuffer(GL_FRAMEBUFFER, framebuffer)
    gl.framebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
    if (gl.checkFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) return null
    val read = ReadBytes(width * height * TEXEL_BYTES)
    gl.readPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, read)
    return read.buffer.toByteArray()
  } finally {
    gl.getExtension(LOSE_CONTEXT)?.loseContext()
  }
}

/**
 * An OffscreenCanvas asked for a WebGL2 context, which the 2D binding of this package types as 2D only.
 */
@JsName("OffscreenCanvas")
internal external class GlReadCanvas(
  width: Int,
  height: Int,
) : JsAny {
  fun getContext(
    contextId: String,
    options: JsAny,
  ): ReadGl?
}

/**
 * The few entry points of a WebGL2 context a straight read of a bitmap takes.
 */
internal external interface ReadGl : JsAny {
  fun createTexture(): JsAny?

  fun bindTexture(
    target: Int,
    texture: JsAny?,
  )

  fun pixelStorei(
    pname: Int,
    param: Int,
  )

  fun texImage2D(
    target: Int,
    level: Int,
    internalformat: Int,
    format: Int,
    type: Int,
    source: JsAny,
  )

  fun createFramebuffer(): JsAny?

  fun bindFramebuffer(
    target: Int,
    framebuffer: JsAny?,
  )

  fun framebufferTexture2D(
    target: Int,
    attachment: Int,
    textarget: Int,
    texture: JsAny?,
    level: Int,
  )

  fun checkFramebufferStatus(target: Int): Int

  fun readPixels(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    format: Int,
    type: Int,
    pixels: ReadBytes,
  )

  fun getExtension(name: String): ReadLoseContext?
}

internal external interface ReadLoseContext : JsAny {
  fun loseContext()
}

/**
 * A byte array the framebuffer is read into, whose buffer is read whole.
 */
@JsName("Uint8Array")
internal external class ReadBytes(
  length: Int,
) : JsAny {
  val buffer: ArrayBuffer
}

private const val WEBGL2 = "webgl2"
private const val GL_OPTIONS = """{"antialias":false,"depth":false,"stencil":false}"""
private const val LOSE_CONTEXT = "WEBGL_lose_context"
private const val TEXEL_BYTES = 4
private const val GL_TEXTURE_2D = 0x0DE1
private const val GL_RGBA = 0x1908
private const val GL_UNSIGNED_BYTE = 0x1401
private const val GL_FRAMEBUFFER = 0x8D40
private const val GL_COLOR_ATTACHMENT0 = 0x8CE0
private const val GL_FRAMEBUFFER_COMPLETE = 0x8CD5
private const val GL_UNPACK_FLIP_Y_WEBGL = 0x9240
private const val GL_UNPACK_PREMULTIPLY_ALPHA_WEBGL = 0x9241
private const val GL_UNPACK_COLORSPACE_CONVERSION_WEBGL = 0x9243

@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs.internal

import dev.jordond.filmstrip.transform.internal.probe.TextureSideProbe
import dev.jordond.filmstrip.webcodecs.internal.interop.JsOptions
import dev.jordond.filmstrip.webcodecs.internal.interop.OffscreenCanvas
import dev.jordond.filmstrip.webcodecs.internal.render.GL_COLOR_ATTACHMENT0
import dev.jordond.filmstrip.webcodecs.internal.render.GL_FRAMEBUFFER
import dev.jordond.filmstrip.webcodecs.internal.render.GL_FRAMEBUFFER_COMPLETE
import dev.jordond.filmstrip.webcodecs.internal.render.GL_HALF_FLOAT
import dev.jordond.filmstrip.webcodecs.internal.render.GL_MAX_TEXTURE_SIZE
import dev.jordond.filmstrip.webcodecs.internal.render.GL_RGBA
import dev.jordond.filmstrip.webcodecs.internal.render.GL_RGBA16F
import dev.jordond.filmstrip.webcodecs.internal.render.GL_TEXTURE_2D
import kotlin.js.ExperimentalWasmJsInterop

/**
 * The least `MAX_TEXTURE_SIZE` WebGL2 promises any context, 2048 pixels on a side.
 *
 * This is WebGL2's own floor. A page that gives no context, or reads less than this, is held to it.
 */
internal const val WEBGL2_MIN_TEXTURE_SIDE: Int = 2048

/**
 * Whether this page can render into the float framebuffer a kept grade composites in.
 *
 * Asked by building the smallest one there is and handing the context straight back, because a
 * browser can carry the extension and still refuse the attachment on a driver it has blocklisted.
 * Together with [browserEncodesHdrVp9] it decides whether an HDR encode is claimable.
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
 * The longest side a texture may have on this page, WebGL's `MAX_TEXTURE_SIZE`, which `texImage2D` refuses past.
 *
 * Read from a throwaway context that is handed straight back, since a page holds only a few contexts, through
 * [webGlTextureSideProbe], so it is never below [WEBGL2_MIN_TEXTURE_SIDE] and is read again until a read succeeds.
 */
internal fun browserMaxTextureSide(): Int = pageTextureSide.side()

/**
 * A [TextureSideProbe] floored at [WEBGL2_MIN_TEXTURE_SIDE], reading the page's own side by default.
 *
 * @param read The page's `MAX_TEXTURE_SIZE`, or null when it could not be read.
 */
internal fun webGlTextureSideProbe(read: () -> Int? = ::readPageTextureSide): TextureSideProbe =
  TextureSideProbe(WEBGL2_MIN_TEXTURE_SIDE, read)

private val pageTextureSide = webGlTextureSideProbe()

private fun readPageTextureSide(): Int? =
  runCatching {
    val gl = OffscreenCanvas(1, 1).getContext("webgl2", JsOptions().build()) ?: return@runCatching null
    try {
      gl.getParameter(GL_MAX_TEXTURE_SIZE)
    } finally {
      gl.getExtension(LOSE_CONTEXT)?.loseContext()
    }
  }.getOrNull()

private const val COLOR_BUFFER_FLOAT = "EXT_color_buffer_float"
private const val LOSE_CONTEXT = "WEBGL_lose_context"

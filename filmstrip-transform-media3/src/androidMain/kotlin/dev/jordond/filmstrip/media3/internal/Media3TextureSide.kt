package dev.jordond.filmstrip.media3.internal

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import dev.jordond.filmstrip.transform.internal.TextureSideProbe

/**
 * The most pixels an OpenGL ES texture holds on a side on this device, its `GL_MAX_TEXTURE_SIZE`.
 *
 * It is read in a throwaway ES context on a 1 by 1 pbuffer, which is released before the read returns, and whatever
 * context the calling thread had current is made current again. Read through [media3TextureSideProbe], a read that
 * succeeds is kept for the process. A device that gives no display, config, context or answer reads as
 * [ES3_MIN_TEXTURE_SIDE], the least an OpenGL ES 3.0 device must hold, and is asked again next time.
 */
internal fun media3MaxTextureSide(): Int = deviceTextureSide.side()

/**
 * A [TextureSideProbe] floored at [ES3_MIN_TEXTURE_SIDE], reading the device's own side unless a test sets [read].
 *
 * @param read The device's `GL_MAX_TEXTURE_SIZE`, or null when it could not be read.
 */
internal fun media3TextureSideProbe(read: () -> Int? = ::probeMaxTextureSide): TextureSideProbe =
  TextureSideProbe(ES3_MIN_TEXTURE_SIDE, read)

private val deviceTextureSide = media3TextureSideProbe()

private fun probeMaxTextureSide(): Int? {
  val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
  if (display == EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display, IntArray(1), 0, IntArray(1), 0)) return null
  val configs = arrayOfNulls<EGLConfig>(1)
  val count = IntArray(1)
  if (!EGL14.eglChooseConfig(display, CONFIG_ATTRIBUTES, 0, configs, 0, 1, count, 0) || count[0] == 0) return null
  val config = configs[0] ?: return null
  val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, CONTEXT_ATTRIBUTES, 0)
  if (context == EGL14.EGL_NO_CONTEXT) return null
  val surface = EGL14.eglCreatePbufferSurface(display, config, PBUFFER_ATTRIBUTES, 0)

  val heldDisplay = EGL14.eglGetCurrentDisplay()
  val heldContext = EGL14.eglGetCurrentContext()
  val heldDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
  val heldRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
  try {
    if (surface == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(display, surface, surface, context)) return null
    val side = IntArray(1)
    GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, side, 0)
    return side[0].takeIf { GLES20.glGetError() == GLES20.GL_NO_ERROR && it > 0 }
  } finally {
    if (heldContext != EGL14.EGL_NO_CONTEXT) {
      EGL14.eglMakeCurrent(heldDisplay, heldDraw, heldRead, heldContext)
    } else {
      EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
    }
    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
    EGL14.eglDestroyContext(display, context)
    // The default display is shared with media3 in this process, so it is never terminated here.
    if (heldContext == EGL14.EGL_NO_CONTEXT) EGL14.eglReleaseThread()
  }
}

/**
 * The fewest pixels on a side an OpenGL ES 3.0 device's textures must hold, its spec's floor for `GL_MAX_TEXTURE_SIZE`.
 *
 * [media3MaxTextureSide] answers this when the device cannot be asked.
 */
internal const val ES3_MIN_TEXTURE_SIDE: Int = 2_048

private val CONFIG_ATTRIBUTES =
  intArrayOf(
    EGL14.EGL_RED_SIZE,
    8,
    EGL14.EGL_GREEN_SIZE,
    8,
    EGL14.EGL_BLUE_SIZE,
    8,
    EGL14.EGL_ALPHA_SIZE,
    8,
    EGL14.EGL_RENDERABLE_TYPE,
    EGL14.EGL_OPENGL_ES2_BIT,
    EGL14.EGL_SURFACE_TYPE,
    EGL14.EGL_PBUFFER_BIT,
    EGL14.EGL_NONE,
  )

private val CONTEXT_ATTRIBUTES = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)

private val PBUFFER_ATTRIBUTES = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)

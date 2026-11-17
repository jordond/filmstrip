package dev.jordond.filmstrip.effects.filters.tape

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * An OpenGL ES 3 context on a one pixel pbuffer, current on the thread that makes it, for a test that draws into a
 * target of its own.
 */
internal class FloatTargetContext {
  private var display = EGL14.EGL_NO_DISPLAY
  private var context = EGL14.EGL_NO_CONTEXT
  private var surface = EGL14.EGL_NO_SURFACE

  /**
   * Makes the context current and says whether the device renders to a 32 bit float target.
   */
  fun make(): Boolean {
    display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    val version = IntArray(2)
    EGL14.eglInitialize(display, version, 0, version, 1)
    val configs = arrayOfNulls<EGLConfig>(1)
    val found = IntArray(1)
    EGL14.eglChooseConfig(display, CONFIG, 0, configs, 0, 1, found, 0)
    context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, CONTEXT, 0)
    surface = EGL14.eglCreatePbufferSurface(display, configs[0], PBUFFER, 0)
    EGL14.eglMakeCurrent(display, surface, surface, context)
    return GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty().contains("EXT_color_buffer_float")
  }

  fun drop() {
    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
    EGL14.eglDestroySurface(display, surface)
    EGL14.eglDestroyContext(display, context)
  }

  private companion object {
    val CONFIG =
      intArrayOf(
        EGL14.EGL_RENDERABLE_TYPE,
        0x40,
        EGL14.EGL_SURFACE_TYPE,
        EGL14.EGL_PBUFFER_BIT,
        EGL14.EGL_NONE,
      )
    val CONTEXT = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
    val PBUFFER = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
  }
}

/**
 * A half float texture of [width] by [height] whose every row holds [source] of each column, a colour of three
 * channels read as opaque.
 */
internal fun floatSourceTexture(
  width: Int,
  height: Int,
  source: (Int) -> FloatArray,
): Int {
  val texels = ByteBuffer.allocateDirect(width * height * CHANNELS * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
  for (y in 0 until height) {
    for (x in 0 until width) {
      val rgb = source(x)
      rgb.forEach(texels::putFloat)
      if (rgb.size == RGB) texels.putFloat(1f)
    }
  }
  texels.rewind()
  val texture = IntArray(1)
  GLES20.glGenTextures(1, texture, 0)
  GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
  GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
  GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
  GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
  GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
  GLES20.glTexImage2D(
    GLES20.GL_TEXTURE_2D,
    0,
    GLES30.GL_RGBA16F,
    width,
    height,
    0,
    GLES20.GL_RGBA,
    GLES20.GL_FLOAT,
    texels,
  )
  return texture[0]
}

private const val CHANNELS = 4
private const val RGB = 3

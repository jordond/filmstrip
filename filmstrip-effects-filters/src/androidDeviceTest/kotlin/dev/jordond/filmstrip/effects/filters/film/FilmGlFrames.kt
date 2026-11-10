package dev.jordond.filmstrip.effects.filters.film

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.opengl.GLES30
import androidx.media3.common.util.GlUtil
import org.junit.Assume.assumeTrue
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal const val CHANNELS = 4

/**
 * An offscreen GLES 3 context for a film test, made current by [open] and dropped by [close].
 *
 * A device with no float color target skips at [open], since 8 bits cannot read the signal to 1e-4.
 */
internal class FilmGlContext {
  private var display = EGL14.EGL_NO_DISPLAY
  private var context = EGL14.EGL_NO_CONTEXT
  private var surface = EGL14.EGL_NO_SURFACE

  fun open() {
    display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    val version = IntArray(2)
    EGL14.eglInitialize(display, version, 0, version, 1)
    val configs = arrayOfNulls<EGLConfig>(1)
    val found = IntArray(1)
    EGL14.eglChooseConfig(display, CONFIG, 0, configs, 0, 1, found, 0)
    context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, CONTEXT, 0)
    surface = EGL14.eglCreatePbufferSurface(display, configs[0], PBUFFER, 0)
    EGL14.eglMakeCurrent(display, surface, surface, context)
    assumeTrue(
      "this device renders to no 32 bit float target",
      GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty().contains("EXT_color_buffer_float"),
    )
  }

  fun close() {
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
 * Runs [film]'s chain over a frame [width] by [height] whose straight RGBA [source] gives for each pixel counted from
 * the top, at [seconds], and reads the result into a 32 bit float target.
 *
 * @return The frame's RGBA floats, bottom row first the way GL hands them back.
 */
internal fun drawFilm(
  film: Film,
  width: Int,
  height: Int,
  seconds: Double,
  targetFormat: Int,
  source: (x: Int, y: Int) -> FloatArray,
): FloatArray {
  val input = sourceTexture(width, height, source)
  val target = IntArray(1)
  val framebuffer = IntArray(1)
  GLES20.glGenTextures(1, target, 0)
  GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, target[0])
  GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, GLES30.GL_RGBA32F, width, height)
  GLES20.glGenFramebuffers(1, framebuffer, 0)
  GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer[0])
  GLES20.glFramebufferTexture2D(
    GLES20.GL_FRAMEBUFFER,
    GLES20.GL_COLOR_ATTACHMENT0,
    GLES20.GL_TEXTURE_2D,
    target[0],
    0,
  )
  GLES20.glViewport(0, 0, width, height)

  val program = filmShaderProgram(film, targetFormat)
  program.configure(width, height)
  GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer[0])
  program.drawFrame(input, (seconds * 1_000_000.0).toLong())
  GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer[0])

  val bytes = ByteBuffer.allocateDirect(width * height * CHANNELS * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
  GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_FLOAT, bytes)
  GlUtil.checkGlError()
  val read = FloatArray(width * height * CHANNELS)
  bytes.asFloatBuffer().get(read)

  program.release()
  GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
  GLES20.glDeleteFramebuffers(1, framebuffer, 0)
  GLES20.glDeleteTextures(2, intArrayOf(target[0], input), 0)
  return read
}

// The rows run from the top of the picture, so the texture takes them from its last row down.
private fun sourceTexture(
  width: Int,
  height: Int,
  source: (Int, Int) -> FloatArray,
): Int {
  val texels = ByteBuffer.allocateDirect(width * height * CHANNELS * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
  for (row in height - 1 downTo 0) {
    for (x in 0 until width) source(x, row).forEach(texels::putFloat)
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

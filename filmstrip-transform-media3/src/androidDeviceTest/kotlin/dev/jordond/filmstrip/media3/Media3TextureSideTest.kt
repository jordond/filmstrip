package dev.jordond.filmstrip.media3

import android.opengl.GLES20
import dev.jordond.filmstrip.media3.internal.ES3_MIN_TEXTURE_SIDE
import dev.jordond.filmstrip.media3.internal.media3RenderCapabilities
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import kotlin.test.Test

/**
 * The texture side media3's capabilities report, against the device's own `GL_MAX_TEXTURE_SIZE`.
 *
 * The device is asked again here through the older EGL10 binding in a context of its own, so the probe is read against
 * a second route to the same answer rather than against itself.
 */
class Media3TextureSideTest {
  @Test
  fun theTextureSideIsTheOneTheDeviceGives() {
    val side = media3RenderCapabilities(hdr = false).maxTextureSize

    side shouldBe deviceTextureSide()
    withClue("a $side pixel side is under the OpenGL ES 3.0 floor") { (side >= ES3_MIN_TEXTURE_SIDE) shouldBe true }
  }

  private fun deviceTextureSide(): Int {
    val egl = EGLContext.getEGL() as EGL10
    val display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
    check(egl.eglInitialize(display, IntArray(2))) { "no EGL display" }
    val configs = arrayOfNulls<EGLConfig>(1)
    val count = IntArray(1)
    check(egl.eglChooseConfig(display, CONFIG, configs, 1, count) && count[0] > 0) { "no pbuffer EGL config" }
    val context = egl.eglCreateContext(display, configs[0], EGL10.EGL_NO_CONTEXT, CONTEXT)
    withClue("no EGL context") { context shouldNotBe EGL10.EGL_NO_CONTEXT }
    val surface = egl.eglCreatePbufferSurface(display, configs[0], PBUFFER)
    try {
      check(egl.eglMakeCurrent(display, surface, surface, context)) { "the EGL context did not go current" }
      val side = IntArray(1)
      GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, side, 0)
      return side[0]
    } finally {
      egl.eglMakeCurrent(display, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
      egl.eglDestroySurface(display, surface)
      egl.eglDestroyContext(display, context)
    }
  }

  private companion object {
    const val EGL_OPENGL_ES2_BIT = 4
    const val EGL_CONTEXT_CLIENT_VERSION = 0x3098

    val CONFIG =
      intArrayOf(
        EGL10.EGL_RENDERABLE_TYPE,
        EGL_OPENGL_ES2_BIT,
        EGL10.EGL_SURFACE_TYPE,
        EGL10.EGL_PBUFFER_BIT,
        EGL10.EGL_NONE,
      )
    val CONTEXT = intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE)
    val PBUFFER = intArrayOf(EGL10.EGL_WIDTH, 1, EGL10.EGL_HEIGHT, 1, EGL10.EGL_NONE)
  }
}

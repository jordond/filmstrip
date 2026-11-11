package dev.jordond.filmstrip.effects.filters.vhs

import android.opengl.EGL14
import android.opengl.EGLConfig
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlUtil
import androidx.test.platform.app.InstrumentationRegistry
import dev.jordond.filmstrip.effect.PreviewClock
import dev.jordond.filmstrip.effects.filters.internal.requireGles3
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/**
 * What [VhsEffect] refuses by name before media3 builds its pass: a context below OpenGL ES 3 and a frame processor in
 * HDR. An EGL implementation may hand back a newer context than asked for, and the real context test skips there.
 * Also what makes two of them equal, which is how a preview tells a re-lowering that changed the pass from one that
 * produced it again.
 */
class VhsEffectLimitsTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  @Test
  fun aContextBelowGles3IsRefusedByName() {
    val refused = assertFailsWith<VideoFrameProcessingException> { requireGles3("Vhs", 2) }

    refused.message.orEmpty() shouldContain "Vhs needs OpenGL ES 3"
    refused.message.orEmpty() shouldContain "OpenGL ES 2"
  }

  @Test
  fun aGles3ContextIsLetThrough() {
    requireGles3("Vhs", 3)
  }

  @Test
  fun aRealGles2ContextIsRefusedByName() {
    onContext(version = 2) {
      assumeTrue("this device hands back a newer context for ES 2", GlUtil.getContextMajorVersion() < 3)
      val refused =
        assertFailsWith<VideoFrameProcessingException> { VhsEffect(Vhs()).toGlShaderProgram(context, false) }

      refused.message.orEmpty() shouldContain "Vhs needs OpenGL ES 3"
    }
  }

  @Test
  fun aFrameProcessorInHdrIsRefusedByName() {
    onContext(version = 3) {
      val refused = assertFailsWith<VideoFrameProcessingException> { VhsEffect(Vhs()).toGlShaderProgram(context, true) }

      refused.message.orEmpty() shouldContain "Vhs requires SDR"
    }
  }

  @Test
  fun anSdrFrameProcessorOnGles3BuildsThePass() {
    onContext(version = 3) {
      VhsEffect(Vhs()).toGlShaderProgram(context, false).release()
    }
  }

  @Test
  fun twoPassesOfOneTapeOnOnePreviewClockAreEqual() {
    val clock = PreviewClock { 2.seconds }

    VhsEffect(Vhs(), clock) shouldBe VhsEffect(Vhs(), clock)
    VhsEffect(Vhs()) shouldBe VhsEffect(Vhs())
    VhsEffect(Vhs(), clock).hashCode() shouldBe VhsEffect(Vhs(), clock).hashCode()
  }

  @Test
  fun aPassOnAnotherPreviewClockIsADifferentPass() {
    val clock = PreviewClock { 2.seconds }
    val readsTheSame = PreviewClock { 2.seconds }

    VhsEffect(Vhs(), clock) shouldNotBe VhsEffect(Vhs(), readsTheSame)
    VhsEffect(Vhs(), clock) shouldNotBe VhsEffect(Vhs())
    VhsEffect(Vhs(), clock) shouldNotBe VhsEffect(Vhs(seed = 7), clock)
  }

  private fun onContext(
    version: Int,
    block: () -> Unit,
  ) {
    val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    val versions = IntArray(2)
    EGL14.eglInitialize(display, versions, 0, versions, 1)
    val configs = arrayOfNulls<EGLConfig>(1)
    val found = IntArray(1)
    val config = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE)
    EGL14.eglChooseConfig(display, config, 0, configs, 0, 1, found, 0)
    val attributes = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, version, EGL14.EGL_NONE)
    val gl = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, attributes, 0)
    val pbuffer = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
    val surface = EGL14.eglCreatePbufferSurface(display, configs[0], pbuffer, 0)
    EGL14.eglMakeCurrent(display, surface, surface, gl)
    try {
      block()
    } finally {
      EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
      EGL14.eglDestroySurface(display, surface)
      EGL14.eglDestroyContext(display, gl)
    }
  }
}

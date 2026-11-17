package dev.jordond.filmstrip.effects.filters.tape

import android.opengl.EGL14
import android.opengl.EGLConfig
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlUtil
import androidx.test.platform.app.InstrumentationRegistry
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.Media3Handle
import dev.jordond.filmstrip.effect.PreviewClock
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effects.filters.FilterEffectResolver
import dev.jordond.filmstrip.effects.filters.WHOLE_SPAN
import dev.jordond.filmstrip.effects.filters.hi8.Hi8
import dev.jordond.filmstrip.effects.filters.internal.requireGles3
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/**
 * What [TapePictureEffect] refuses by name before media3 builds its pass: a context below OpenGL ES 3 and a frame
 * processor in HDR. An EGL implementation may hand back a newer context than asked for, and the real context test skips
 * there. Also what makes two of them equal, which is how a preview tells a re-lowering that changed the pass from one
 * that produced it again.
 */
class TapePictureEffectLimitsTest {
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
        assertFailsWith<VideoFrameProcessingException> {
          TapePictureEffect(Vhs(), WHOLE_SPAN).toGlShaderProgram(context, false)
        }

      refused.message.orEmpty() shouldContain "filmstrip.filters.vhs needs OpenGL ES 3"
    }
  }

  @Test
  fun aFrameProcessorInHdrIsRefusedByName() {
    onContext(version = 3) {
      val refused =
        assertFailsWith<VideoFrameProcessingException> {
          TapePictureEffect(
            Hi8(),
            WHOLE_SPAN,
          ).toGlShaderProgram(context, true)
        }

      refused.message.orEmpty() shouldContain "filmstrip.filters.hi8 requires SDR"
    }
  }

  @Test
  fun anSdrFrameProcessorOnGles3BuildsThePass() {
    onContext(version = 3) {
      TapePictureEffect(Vhs(), WHOLE_SPAN).toGlShaderProgram(context, false).release()
    }
  }

  @Test
  fun theResolverHandsATapeTheSlotOfItsClip() {
    val late = TimeRange(10.seconds, 40.seconds)
    val resolve = { span: TimeRange ->
      FilterEffectResolver()
        .resolve(Vhs(settle = TapeSettle(1f, 2.seconds)), CAPABILITIES, attributesOf(span))
        .shouldBeInstanceOf<EffectResolution.Resolved>()
        .effect.handle
        .shouldBeInstanceOf<Media3Handle.Video>()
        .effect
    }

    resolve(late) shouldBe TapePictureEffect(Vhs(settle = TapeSettle(1f, 2.seconds)), late)
    resolve(late) shouldNotBe resolve(WHOLE_SPAN)
  }

  private fun attributesOf(span: TimeRange): Attributes {
    val size = Size(WIDTH, HEIGHT)
    return Attributes(size, size, size, ColorSpace.Bt709, null, 30f, span)
  }

  @Test
  fun twoPassesOfOneTapeOnOnePreviewClockAreEqual() {
    val clock = PreviewClock { 2.seconds }

    TapePictureEffect(Vhs(), WHOLE_SPAN, clock) shouldBe TapePictureEffect(Vhs(), WHOLE_SPAN, clock)
    TapePictureEffect(Vhs(), WHOLE_SPAN) shouldBe TapePictureEffect(Vhs(), WHOLE_SPAN)
    TapePictureEffect(Vhs(), WHOLE_SPAN, clock).hashCode() shouldBe
      TapePictureEffect(Vhs(), WHOLE_SPAN, clock).hashCode()
  }

  @Test
  fun aPassOnAnotherPreviewClockIsADifferentPass() {
    val clock = PreviewClock { 2.seconds }
    val readsTheSame = PreviewClock { 2.seconds }

    TapePictureEffect(Vhs(), WHOLE_SPAN, clock) shouldNotBe TapePictureEffect(Vhs(), WHOLE_SPAN, readsTheSame)
    TapePictureEffect(Vhs(), WHOLE_SPAN, clock) shouldNotBe TapePictureEffect(Vhs(), WHOLE_SPAN)
    TapePictureEffect(Vhs(), WHOLE_SPAN, clock) shouldNotBe TapePictureEffect(Vhs(seed = 7), WHOLE_SPAN, clock)
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

  private companion object {
    const val WIDTH = 640
    const val HEIGHT = 360
    const val MAX_TEXTURE = 4096
    val CAPABILITIES =
      RenderCapabilities(
        api = RenderApi.OpenGlEs,
        supportsFragmentShader = true,
        supportsComputeShader = false,
        supportsHdr = false,
        colorSpaces = emptySet(),
        maxTextureSize = MAX_TEXTURE,
        features = emptySet(),
      )
  }
}

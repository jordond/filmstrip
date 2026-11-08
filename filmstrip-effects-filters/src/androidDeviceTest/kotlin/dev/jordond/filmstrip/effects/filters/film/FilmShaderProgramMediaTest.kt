package dev.jordond.filmstrip.effects.filters.film

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.opengl.GLES30
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlUtil
import androidx.test.platform.app.InstrumentationRegistry
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.Assume.assumeTrue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Reads the chain's own output, with no encoder in the way, against [FilmSignal].
 *
 * The input is flat patches whose codes half floats hold exactly, drawn into a 32 bit float target, so a read resolves
 * the signal to well under one 8 bit code. A device with no float color target skips, since 8 bits cannot read 1e-4.
 */
class FilmShaderProgramMediaTest {
  private var display = EGL14.EGL_NO_DISPLAY
  private var context = EGL14.EGL_NO_CONTEXT
  private var surface = EGL14.EGL_NO_SURFACE

  @BeforeTest
  fun makeContext() {
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

  @AfterTest
  fun dropContext() {
    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
    EGL14.eglDestroySurface(display, surface)
    EGL14.eglDestroyContext(display, context)
  }

  @Test
  fun grainAloneLandsOnTheSignalAtAMidStrength() {
    val film = Film(grain = 0.5f, halation = 0f)
    val params = FilmParams.of(film)
    val frame = draw(film, WIDTH, HEIGHT)

    assertFrameMatches(frame, WIDTH, HEIGHT, TOLERANCE) { x, y, codes ->
      FilmSignal.grained(
        codes[0],
        codes[1],
        codes[2],
        FilmSignal.grainCell(x, y, WIDTH, HEIGHT),
        FilmSignal.grainTickAt(SECONDS),
        params,
      )
    }
  }

  @Test
  fun halationAloneLandsOnTheSignalAtAMidStrength() {
    val film = Film(grain = 0f, halation = 0.5f)
    val params = FilmParams.of(film)
    val glow = glowOf(WIDTH, HEIGHT)
    val frame = draw(film, WIDTH, HEIGHT)

    withClue("the patch has to glow past its edge for this to tell") {
      (glow.at(patchRight(WIDTH) + 2, HEIGHT / 3)[0] > 0.01) shouldBe true
    }
    assertFrameMatches(frame, WIDTH, HEIGHT, TOLERANCE) { x, y, codes ->
      FilmSignal.halated(codes[0], codes[1], codes[2], glow.at(x, y), params)
    }
  }

  @Test
  fun theWholeChainLandsOnTheSignalAtAMidStrength() {
    assertChainMatches(WIDTH, HEIGHT)
  }

  @Test
  fun aSmallerFrameDrawsTheSameLookAtItsOwnSize() {
    assertChainMatches(WIDTH / 2, HEIGHT / 2)
  }

  @Test
  fun aLargeFrameBlursAtThePlansSmallerSize() {
    withClue("the plan has to scale for this to tell") {
      (FilmSignal.halationBlurPlan(LARGE_WIDTH).factor > 1) shouldBe true
    }
    assertChainMatches(LARGE_WIDTH, LARGE_HEIGHT)
  }

  @Test
  fun a1080pFrameBlursAtAFactorPastTwo() {
    withClue("the plan has to scale past two for this to tell") {
      (FilmSignal.halationBlurPlan(FULL_HD_WIDTH).factor > 2) shouldBe true
    }
    assertChainMatches(FULL_HD_WIDTH, FULL_HD_HEIGHT)
  }

  @Test
  fun aFrameItsFactorDoesNotDivideBlursAtThePlansSize() {
    withClue("the factor has to leave a remainder for this to tell") {
      (UNEVEN_WIDTH % FilmSignal.halationBlurPlan(UNEVEN_WIDTH).factor != 0) shouldBe true
    }
    assertChainMatches(UNEVEN_WIDTH, UNEVEN_HEIGHT)
  }

  @Test
  fun eightBitTargetsLandWithinTheirRounding() {
    assertChainMatches(WIDTH, HEIGHT, GLES30.GL_RGBA8, EIGHT_BIT_TOLERANCE)
  }

  @Test
  fun aDeviceRendersHalfFloatOnEitherExtensionAndEightBitsOnNeither() {
    stepTargetFormat("GL_EXT_color_buffer_float") shouldBe GLES30.GL_RGBA16F
    stepTargetFormat("GL_EXT_color_buffer_half_float") shouldBe GLES30.GL_RGBA16F
    stepTargetFormat("GL_OES_texture_float") shouldBe GLES30.GL_RGBA8
  }

  @Test
  fun theSameTimeDrawsTheSamePictureAfterASeek() {
    val film = Film()
    val first = draw(film, WIDTH, HEIGHT)
    val elsewhere = draw(film, WIDTH, HEIGHT, seconds = SECONDS + 1.0)
    val again = draw(film, WIDTH, HEIGHT)

    withClue("the grain moves between the two times") { elsewhere.contentEquals(first) shouldBe false }
    withClue("the same time draws the same picture") { again.contentEquals(first) shouldBe true }
  }

  @Test
  fun aBarKeepsItsAlphaAndGivesTheGlowNoLight() {
    val film = Film(grain = 1f, halation = 1f)
    val dark = floatArrayOf(0f, 0f, 0f, 0f)
    val bright = floatArrayOf(BAR_BRIGHT, BAR_BRIGHT, BAR_BRIGHT, 0f)
    val overDark = draw(film, WIDTH, HEIGHT, source = canvas(dark))
    val overBright = draw(film, WIDTH, HEIGHT, source = canvas(bright))

    val y = HEIGHT / 2
    val alpha = { column: Int -> overDark[(y * WIDTH + column) * CHANNELS + 3].toDouble() }
    withClue("a bar stays clear") { alpha(CANVAS_LEFT / 2) shouldBe (0.0 plusOrMinus TOLERANCE) }
    withClue("the picture stays opaque") { alpha(WIDTH / 2) shouldBe (1.0 plusOrMinus TOLERANCE) }
    val inPicture = (y * WIDTH + WIDTH / 2) * CHANNELS
    withClue("what a clear bar holds never reaches the picture") {
      overBright[inPicture].toDouble() shouldBe (overDark[inPicture].toDouble() plusOrMinus TOLERANCE)
    }
  }

  @Test
  fun anHdrFrameProcessorIsRefusedByName() {
    val app = InstrumentationRegistry.getInstrumentation().targetContext
    val refused = assertFailsWith<VideoFrameProcessingException> { FilmEffect(Film()).toGlShaderProgram(app, true) }

    refused.message.orEmpty() shouldContain "Film requires SDR"
  }

  @Test
  fun anSdrFrameProcessorOnGles3BuildsThePass() {
    val app = InstrumentationRegistry.getInstrumentation().targetContext

    FilmEffect(Film()).toGlShaderProgram(app, false).release()
  }

  @Test
  fun twoEffectsOfOneFilmCompareEqual() {
    (FilmEffect(Film(0.25f, 0.75f)) == FilmEffect(Film(0.25f, 0.75f))) shouldBe true
    (FilmEffect(Film(0.25f, 0.75f)) == FilmEffect(Film(0.25f, 0.5f))) shouldBe false
  }

  private fun assertChainMatches(
    width: Int,
    height: Int,
    targetFormat: Int = GLES30.GL_RGBA16F,
    tolerance: Double = CHAIN_TOLERANCE,
  ) {
    val film = Film()
    val params = FilmParams.of(film)
    val glow = glowOf(width, height)
    val frame = draw(film, width, height, targetFormat = targetFormat)

    assertFrameMatches(frame, width, height, tolerance) { x, y, codes ->
      FilmSignal.pixelAt(
        codes[0],
        codes[1],
        codes[2],
        glow.at(x, y),
        FilmSignal.grainCell(x, y, width, height),
        FilmSignal.grainTickAt(SECONDS),
        params,
      )
    }
  }

  private fun assertFrameMatches(
    frame: FloatArray,
    width: Int,
    height: Int,
    tolerance: Double,
    expected: (x: Int, y: Int, codes: DoubleArray) -> DoubleArray,
  ) {
    val columns = listOf(width / 8, patchRight(width) - 1, patchRight(width) + 2, width / 2 + width / 16, width - 2)
    listOf(height / 8, height / 3, height / 2 + height / 16, height - 2).forEach { y ->
      columns.forEach { x ->
        val wanted = expected(x, y, codesAt(x, y, width, height))
        // The frame reads bottom up, the way GL hands it back.
        val at = ((height - 1 - y) * width + x) * CHANNELS
        wanted.forEachIndexed { channel, value ->
          withClue("row $y column $x channel $channel of a ${width}x$height frame") {
            frame[at + channel].toDouble() shouldBe (value plusOrMinus tolerance)
          }
        }
      }
    }
  }

  private fun glowOf(
    width: Int,
    height: Int,
  ) = HalationGlow.of(width, height) { x, y -> codesAt(x, y, width, height) }

  // A bright patch in the upper left, which sits on the middle of the response's ramp, over a midtone.
  private fun codesAt(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
  ): DoubleArray {
    val inPatch = x < patchRight(width) && y < height / 2
    return if (inPatch) PATCH.copyOf() else GROUND.copyOf()
  }

  private fun patchRight(width: Int) = width / 2

  private fun canvas(bar: FloatArray): (Int, Int) -> FloatArray =
    { x, _ ->
      if (x in CANVAS_LEFT until CANVAS_RIGHT) {
        floatArrayOf(PATCH[0].toFloat(), PATCH[1].toFloat(), PATCH[2].toFloat(), 1f)
      } else {
        bar
      }
    }

  private fun draw(
    film: Film,
    width: Int,
    height: Int,
    seconds: Double = SECONDS,
    targetFormat: Int = GLES30.GL_RGBA16F,
    source: (Int, Int) -> FloatArray = { x, y ->
      val codes = codesAt(x, y, width, height)
      floatArrayOf(codes[0].toFloat(), codes[1].toFloat(), codes[2].toFloat(), 1f)
    },
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

    val program = FilmShaderProgram(film, targetFormat)
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

  private companion object {
    const val WIDTH = 512
    const val HEIGHT = 480
    const val LARGE_WIDTH = 1280
    const val LARGE_HEIGHT = 720
    const val FULL_HD_WIDTH = 1920
    const val FULL_HD_HEIGHT = 1080
    const val UNEVEN_WIDTH = 1366
    const val UNEVEN_HEIGHT = 768
    const val CHANNELS = 4
    const val CANVAS_LEFT = 128
    const val CANVAS_RIGHT = 384
    const val BAR_BRIGHT = 0.875f
    const val SECONDS = 25.0
    const val TOLERANCE = 1e-4

    // The chain's frame sized step rounds to half float, whose step is 2^-11 from a half to one. That's the 0.12 of a
    // code stepTargetFormat names, and this literal pins it.
    const val CHAIN_TOLERANCE = 5e-4

    // Half a code on the frame sized step, and half a code on each of the three glow steps under the mid strength's
    // gain.
    const val EIGHT_BIT_TOLERANCE = 1.0 / 255.0
    val PATCH = doubleArrayOf(0.875, 0.8125, 0.75)
    val GROUND = doubleArrayOf(0.375, 0.5, 0.25)
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

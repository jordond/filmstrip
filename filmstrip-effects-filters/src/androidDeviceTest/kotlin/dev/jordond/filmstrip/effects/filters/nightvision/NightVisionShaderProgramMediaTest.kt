package dev.jordond.filmstrip.effects.filters.nightvision

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.opengl.GLES30
import androidx.media3.common.util.GlUtil
import dev.jordond.filmstrip.effects.filters.film.FilmSignal
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.Assume.assumeTrue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Reads the shader's own output, with no encoder in the way, against [NightVisionSignal.pixelAt].
 *
 * The input is a pair of ramps whose codes half floats hold exactly, drawn into a 32 bit float target. A texture here
 * runs up the picture the way media3's do, so its first row is the picture's bottom row. A device with no float color
 * target skips.
 */
class NightVisionShaderProgramMediaTest {
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
  fun halfIntensityLandsOnTheSignal() = assertLandsOnTheSignal(HALF_LOOK, WIDTH, HEIGHT, READ_US)

  @Test
  fun aSmallerFrameLandsOnTheSignalAtItsOwnSize() = assertLandsOnTheSignal(HALF_LOOK, WIDTH / 2, HEIGHT / 2, READ_US)

  @Test
  fun aThreeQuarterFrameLandsOnTheSignalAtItsOwnSize() =
    assertLandsOnTheSignal(HALF_LOOK, WIDTH * 3 / 4, HEIGHT * 3 / 4, READ_US)

  @Test
  fun theSameTimeDrawsTheSameFrameAfterASeek() {
    val first = draw(HALF_LOOK, WIDTH, HEIGHT, READ_US) { column, row -> ramps(column, row) }
    val elsewhere = draw(HALF_LOOK, WIDTH, HEIGHT, OTHER_US) { column, row -> ramps(column, row) }
    val again = draw(HALF_LOOK, WIDTH, HEIGHT, READ_US) { column, row -> ramps(column, row) }

    // Compared as arrays, since boxing a 720p float frame into a list for a matcher takes minutes on an emulator.
    withClue("the grain moves between the two times") { elsewhere.contentEquals(first) shouldBe false }
    withClue("the same time draws the same frame") { again.contentEquals(first) shouldBe true }
  }

  @Test
  fun aClearBarStaysClear() {
    val frame =
      draw(NightVision(), WIDTH, HEIGHT, READ_US) { column, row ->
        if (column < BAR) FloatArray(CHANNELS) else ramps(column, row)
      }

    pixel(frame, WIDTH, 0, HEIGHT / 2).toList() shouldBe listOf(0f, 0f, 0f, 0f)
  }

  private fun assertLandsOnTheSignal(
    look: NightVision,
    width: Int,
    height: Int,
    timeUs: Long,
  ) {
    val frame = draw(look, width, height, timeUs) { column, row -> ramps(column, row) }
    val params = NightVisionParams.of(look)
    val tick = FilmSignal.grainTickAt(timeUs / MICROS_PER_SECOND)

    reads(height).forEach { y ->
      reads(width).forEach { x ->
        // The picture's rows from the top, as the signal counts them.
        val shown = ramps(x, height - 1 - y)
        val wanted =
          NightVisionSignal.pixelAt(
            shown[0].toDouble(),
            shown[1].toDouble(),
            shown[2].toDouble(),
            x,
            y,
            width,
            height,
            tick,
            params,
          )
        val read = pixel(frame, width, x, height - 1 - y)
        for (channel in 0 until RGB) {
          withClue("column $x row $y channel $channel of $width by $height") {
            abs(read[channel] - wanted[channel]) shouldBeLessThan TOLERANCE
          }
        }
      }
    }
  }

  private fun reads(size: Int): List<Int> = ((0 until size step SPREAD_STRIDE) + (size / 2) + (size - 1)).distinct()

  private fun pixel(
    frame: FloatArray,
    width: Int,
    x: Int,
    y: Int,
  ): FloatArray = FloatArray(CHANNELS) { frame[(y * width + x) * CHANNELS + it] }

  private fun draw(
    look: NightVision,
    width: Int,
    height: Int,
    timeUs: Long,
    source: (Int, Int) -> FloatArray,
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

    val program = NightVisionShaderProgram(look)
    program.configure(width, height)
    program.drawFrame(input, timeUs)

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

  private fun sourceTexture(
    width: Int,
    height: Int,
    source: (Int, Int) -> FloatArray,
  ): Int {
    val texels = ByteBuffer.allocateDirect(width * height * CHANNELS * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
    for (y in 0 until height) {
      for (x in 0 until width) {
        source(x, y).forEach(texels::putFloat)
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

  // Red runs across and green runs down the texture, each on a step a half float holds exactly.
  private fun ramps(
    column: Int,
    row: Int,
  ): FloatArray = floatArrayOf(RAMP_START + column / RAMP_STEPS, RAMP_START + row / RAMP_STEPS, BLUE, 1f)

  private companion object {
    val HALF_LOOK = NightVision(intensity = 0.5f, seed = 7)
    const val WIDTH = 1280
    const val HEIGHT = 720
    const val READ_US = 200_000L
    const val OTHER_US = 33_333L
    const val MICROS_PER_SECOND = 1_000_000.0
    const val CHANNELS = 4
    const val RGB = 3
    const val SPREAD_STRIDE = 37
    const val BAR = 160
    const val RAMP_START = 0.03125f
    const val RAMP_STEPS = 2048f
    const val BLUE = 0.375f

    // The GPU's single precision against the signal's doubles, well under a code.
    const val TOLERANCE = 1e-3
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

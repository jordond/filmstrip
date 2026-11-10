package dev.jordond.filmstrip.effects.filters.glitch

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.opengl.GLES30
import androidx.media3.common.util.GlUtil
import dev.jordond.filmstrip.effects.filters.toDoubles
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.Assume.assumeTrue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Reads the shader's own output, with no encoder in the way, against [GlitchSignal].
 *
 * The input is a ramp whose codes half floats hold exactly, drawn into a 32 bit float target, so a read resolves the
 * signal to well under one 8 bit code. A device with no float color target skips, since 8 bits cannot read 1e-4. The
 * seed is the first whose burst is rising at the read, found through the signal.
 */
class GlitchShaderProgramMediaTest {
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
  fun everyRowLandsOnTheSignalMidAttack() {
    val frame = draw(BURSTING, width = WIDTH, height = HEIGHT)

    withClue("some row slides at least a pixel") {
      (0 until HEIGHT).any { y -> abs(shiftOf(y, HEIGHT)) * WIDTH > 1.0 } shouldBe true
    }
    (0 until HEIGHT step ROW_STEP).forEach { y -> assertRowMatches(frame, BURSTING, y, HEIGHT) }
  }

  @Test
  fun aQuietTickSplitsTheColoursAndMovesNoRow() {
    val frame = draw(QUIET, width = WIDTH, height = HEIGHT)

    (0 until HEIGHT step ROW_STEP).forEach { y -> assertRowMatches(frame, QUIET, y, HEIGHT) }
  }

  @Test
  fun aSmallerFrameDrawsTheSameBurstAtItsOwnVirtualRows() {
    val height = HEIGHT / 3
    val frame = draw(BURSTING, width = WIDTH, height = height)

    (0 until height step ROW_STEP).forEach { y -> assertRowMatches(frame, BURSTING, y, height) }
  }

  @Test
  fun aRowOnAnExactMultipleOfTheVirtualRowsLandsOnTheSignal() {
    val frame = draw(BURSTING, width = WIDTH, height = TALL_HEIGHT)

    (0 until TALL_HEIGHT step ROW_STEP).forEach { y -> assertRowMatches(frame, BURSTING, y, TALL_HEIGHT) }
  }

  @Test
  fun theSameTimeDrawsTheSamePictureAfterASeek() {
    val first = draw(BURSTING, width = WIDTH, height = HEIGHT)
    val elsewhere = draw(BURSTING, width = WIDTH, height = HEIGHT, seconds = QUIET_SECONDS)
    val again = draw(BURSTING, width = WIDTH, height = HEIGHT)

    elsewhere.contentEquals(first) shouldBe false
    again.contentEquals(first) shouldBe true
  }

  @Test
  fun alphaMovesWithGreen() {
    val frame = draw(BURSTING, width = WIDTH, height = HEIGHT, source = ::canvas)
    val y = (0 until HEIGHT).maxBy { abs(shiftOf(it, HEIGHT)) }
    val shift = shiftOf(y, HEIGHT) * WIDTH
    val leading = if (shift > 0) CANVAS_RIGHT else CANVAS_LEFT
    val trailing = if (shift > 0) CANVAS_LEFT else CANVAS_RIGHT
    val alpha = { column: Double -> frame[((HEIGHT - 1 - y) * WIDTH + column.toInt()) * CHANNELS + 3].toDouble() }

    withClue("the row slides far enough to tell") { abs(shift) shouldBeGreaterThan 4.0 }
    withClue("picture moved past its own edge") {
      alpha(leading + shift / 2) shouldBe (1.0 plusOrMinus TOLERANCE)
    }
    withClue("the bar the picture left") {
      alpha(trailing + shift / 2) shouldBe (0.0 plusOrMinus TOLERANCE)
    }
  }

  // Straight colour at half alpha left of the middle, so a split or a slide reading across the middle mixes the two.
  @Test
  fun aHalfAlphaPictureLandsOnTheSignalInStraightColour() {
    val frame = draw(BURSTING, width = WIDTH, height = HEIGHT, source = ::halfAlpha)
    val params = GlitchParams.of(BURSTING)

    (0 until HEIGHT step ROW_STEP).forEach { y ->
      val row = GlitchSignal.virtualRow(y, HEIGHT)
      for (column in 0 until WIDTH) {
        val expected =
          GlitchSignal.pixelAt(
            (column + 0.5) / WIDTH,
            row,
            TICK,
            BURSTING.seed,
            WIDTH,
            params,
          ) { halfAlpha(it).toDoubles() }
        val at = ((HEIGHT - 1 - y) * WIDTH + column) * CHANNELS
        expected.forEachIndexed { channel, value ->
          withClue("row $y column $column channel $channel") {
            frame[at + channel].toDouble() shouldBe (value plusOrMinus TOLERANCE)
          }
        }
      }
    }
  }

  private fun halfAlpha(column: Int): FloatArray = ramp(column) + if (column < WIDTH / 2) 0.5f else 1f

  private fun shiftOf(
    y: Int,
    height: Int,
  ): Double =
    GlitchSignal.rowShift(
      BURSTING.seed,
      GlitchSignal.tickAt(SECONDS),
      GlitchSignal.virtualRow(y, height),
      GlitchParams.of(BURSTING),
    )

  private fun assertRowMatches(
    frame: FloatArray,
    glitch: Glitch,
    y: Int,
    height: Int,
  ) {
    val params = GlitchParams.of(glitch)
    val tick = GlitchSignal.tickAt(SECONDS)
    val row = GlitchSignal.virtualRow(y, height)
    READ_COLUMNS.forEach { column ->
      val x = (column + 0.5) / WIDTH
      val expected = GlitchSignal.pixelAt(x, row, tick, glitch.seed, WIDTH, params) { ramp(it).toDoubles() }
      // The frame reads bottom up, the way GL hands it back.
      val at = ((height - 1 - y) * WIDTH + column) * CHANNELS
      expected.forEachIndexed { channel, value ->
        withClue("row $y column $column channel $channel") {
          frame[at + channel].toDouble() shouldBe (value plusOrMinus TOLERANCE)
        }
      }
    }
  }

  private fun draw(
    glitch: Glitch,
    width: Int,
    height: Int,
    seconds: Double = SECONDS,
    source: (Int) -> FloatArray = ::ramp,
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

    val program = GlitchShaderProgram(glitch)
    program.configure(width, height)
    program.drawFrame(input, (seconds * 1_000_000.0).toLong())

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

  // A ramp whose step every half float from a quarter to one holds exactly, red and blue running opposite ways.
  private fun ramp(column: Int): FloatArray {
    val code = 0.25f + column / RAMP_STEPS
    return floatArrayOf(code, 0.5f, 1f - code)
  }

  // A canvas: clear bars either side of a mid grey picture, with the alpha to tell them apart.
  private fun canvas(column: Int): FloatArray =
    if (column in CANVAS_LEFT until CANVAS_RIGHT) floatArrayOf(0.5f, 0.5f, 0.5f, 1f) else floatArrayOf(0f, 0f, 0f, 0f)

  private companion object {
    const val WIDTH = 512
    const val HEIGHT = 480
    const val TALL_HEIGHT = 1080
    const val ROW_STEP = 9
    const val CHANNELS = 4
    const val RGB = 3
    const val CANVAS_LEFT = 128
    const val CANVAS_RIGHT = 384
    const val RAMP_STEPS = 1024f
    const val SECONDS = 25.0
    const val TOLERANCE = 1e-4
    const val SEED_SEARCH = 100_000
    val READ_COLUMNS = listOf(0, 128, 256, 384, WIDTH - 1)
    val MID = GlitchParams.at(0.5)
    val TICK = GlitchSignal.tickAt(SECONDS)

    // The first seed whose burst at the read is still rising, and the first with no burst there.
    val BURSTING =
      Glitch(
        0.5f,
        seed =
          (1..SEED_SEARCH).first { seed ->
            val now = GlitchSignal.burstAt(seed, TICK, MID)?.strength ?: 0.0
            val next = GlitchSignal.burstAt(seed, TICK + 1, MID)?.strength ?: 0.0
            now > 0.0 && next > now
          },
      )
    val QUIET = Glitch(0.5f, seed = (1..SEED_SEARCH).first { GlitchSignal.burstAt(it, TICK, MID) == null })

    // A time the bursting seed draws something else at.
    val QUIET_SECONDS = SECONDS / 2

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

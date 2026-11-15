package dev.jordond.filmstrip.effects.filters.tape

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.opengl.GLES30
import androidx.media3.common.util.GlUtil
import dev.jordond.filmstrip.effects.filters.betamax.Betamax
import dev.jordond.filmstrip.effects.filters.hi8.Hi8
import dev.jordond.filmstrip.effects.filters.toDoubles
import dev.jordond.filmstrip.effects.filters.vhs.Tracking
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.Assume.assumeTrue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Reads the shader's own output, with no encoder in the way, against [TapePictureSignal].
 *
 * The input is a ramp whose codes half floats hold exactly, drawn into a 32 bit float target, so a read resolves the
 * signal to well under one 8 bit code. A device with no float color target skips, since 8 bits cannot read 1e-4.
 */
class TapePictureShaderProgramMediaTest {
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
  fun everyRowLandsOnTheSignalAtAMidIntensity() {
    val vhs = Vhs(intensity = 0.5f)
    val frame = draw(vhs, seconds = SECONDS, width = WIDTH, height = HEIGHT)

    READ_ROWS.forEach { y -> assertRowMatches(frame, vhs, y, HEIGHT) }
  }

  @Test
  fun aSmallerFrameDrawsTheSameTapeAtItsOwnVirtualRows() {
    val vhs = Vhs(intensity = 0.5f)
    val height = HEIGHT / 3
    val frame = draw(vhs, seconds = SECONDS, width = WIDTH, height = height)

    listOf(height / 4, height / 2, height - 1).forEach { y -> assertRowMatches(frame, vhs, y, height) }
  }

  @Test
  fun snowInTheMiddleOfARollingBandLandsOnTheSignal() {
    val frame = draw(SNOWY, seconds = SECONDS, width = WIDTH, height = HEIGHT)
    val row = TapePictureSignal.virtualRow(HEIGHT / 2, HEIGHT)

    TapePictureSignal
      .snowAt(
        SNOWY.seed,
        TapePictureSignal.tickAt(SECONDS),
        row,
        0.5,
        TapePictureParams.of(SNOWY),
      ).weight shouldBeGreaterThan
      0.25
    listOf(HEIGHT * 2 / 5, HEIGHT / 2, HEIGHT * 3 / 5).forEach { y -> assertRowMatches(frame, SNOWY, y, HEIGHT) }
  }

  @Test
  fun aSmallerFrameDrawsTheSameSnowAtItsOwnVirtualRows() {
    val height = HEIGHT / 3
    val frame = draw(SNOWY, seconds = SECONDS, width = WIDTH, height = height)

    listOf(height * 2 / 5, height / 2, height * 3 / 5).forEach { y -> assertRowMatches(frame, SNOWY, y, height) }
  }

  @Test
  fun aHi8DropoutLandsOnTheSignalInTheMiddleOfItsFall() {
    val (hi8, y, column) = midFall(HEIGHT) { seed -> Hi8(intensity = 0.6f, seed = seed) }
    val frame = draw(hi8, seconds = SECONDS, width = WIDTH, height = HEIGHT)

    assertRowMatches(frame, hi8, y, HEIGHT, columns = READ_COLUMNS + column)
  }

  @Test
  fun aBetamaxDropoutLandsOnTheSignalInTheMiddleOfItsFall() {
    val (betamax, y, column) = midFall(HEIGHT) { seed -> Betamax(intensity = 0.6f, seed = seed) }
    val frame = draw(betamax, seconds = SECONDS, width = WIDTH, height = HEIGHT)

    assertRowMatches(frame, betamax, y, HEIGHT, columns = READ_COLUMNS + column)
  }

  @Test
  fun aSmallerFrameDrawsTheSameHi8DropoutAtItsOwnVirtualRows() {
    val height = HEIGHT / 3
    val (hi8, y, column) = midFall(height) { seed -> Hi8(intensity = 0.6f, seed = seed) }
    val frame = draw(hi8, seconds = SECONDS, width = WIDTH, height = height)

    assertRowMatches(frame, hi8, y, height, columns = READ_COLUMNS + column)
    listOf(height / 4, height / 2, height - 1).forEach { row -> assertRowMatches(frame, hi8, row, height) }
  }

  // At 1080 every ninth row lands on an exact multiple of the virtual rows, which a float's rounding can land under.
  @Test
  fun aRowOnAnExactMultipleOfTheVirtualRowsLandsOnTheSignal() {
    val vhs = Vhs(intensity = 0.5f)
    val frame = draw(vhs, seconds = SECONDS, width = WIDTH, height = TALL_HEIGHT)

    (0 until TALL_HEIGHT step EXACT_ROW_STEP).forEach { y -> assertRowMatches(frame, vhs, y, TALL_HEIGHT) }
  }

  @Test
  fun aChromaEdgeSmearsAsTheSignalSays() {
    val vhs = Vhs(intensity = 0.5f)
    val frame = draw(vhs, seconds = SECONDS, width = WIDTH, height = HEIGHT, source = ::edge)

    assertRowMatches(frame, vhs, HEIGHT / 2, HEIGHT, ::edge)
  }

  @Test
  fun theSameTimeDrawsTheSamePictureAfterASeek() {
    val vhs = Vhs(intensity = 0.5f)
    val first = draw(vhs, seconds = SECONDS, width = WIDTH, height = HEIGHT)
    draw(vhs, seconds = SECONDS / 2, width = WIDTH, height = HEIGHT)
    val again = draw(vhs, seconds = SECONDS, width = WIDTH, height = HEIGHT)

    again.contentEquals(first) shouldBe true
  }

  @Test
  fun alphaMovesWithTheRowAndColourNeverRaisesIt() {
    val vhs = Vhs(intensity = 0.5f)
    val frame = draw(vhs, seconds = SECONDS, width = WIDTH, height = HEIGHT, source = ::canvas)
    val y = HEIGHT - 1
    val row = TapePictureSignal.virtualRow(y, HEIGHT)
    val shift =
      TapePictureSignal.rowShift(vhs.seed, TapePictureSignal.tickAt(SECONDS), row, TapePictureParams.of(vhs)) * WIDTH
    val alpha = { column: Double -> frame[column.toInt() * CHANNELS + 3].toDouble() }

    withClue("picture moved into the right bar") {
      alpha(CANVAS_RIGHT + shift / 2) shouldBe (1.0 plusOrMinus TOLERANCE)
    }
    withClue("past the moved picture") {
      alpha(CANVAS_RIGHT + shift + BAR_MARGIN) shouldBe (0.0 plusOrMinus TOLERANCE)
    }
    withClue("the start of the picture's own rect") {
      alpha(CANVAS_LEFT + shift / 2) shouldBe (0.0 plusOrMinus TOLERANCE)
    }
    withClue("a bar the lift and the noise have coloured") {
      alpha(BAR_MARGIN.toDouble()) shouldBe (0.0 plusOrMinus TOLERANCE)
    }
  }

  // Straight colour at half alpha left of the middle, so the blurs and the slide reading across the middle mix the two.
  @Test
  fun aHalfAlphaPictureLandsOnTheSignalInStraightColour() {
    val vhs = Vhs(intensity = 0.5f)
    val params = TapePictureParams.of(vhs)
    val tick = TapePictureSignal.tickAt(SECONDS)
    val frame = draw(vhs, seconds = SECONDS, width = WIDTH, height = HEIGHT, source = ::halfAlpha)

    READ_ROWS.forEach { y ->
      val row = TapePictureSignal.virtualRow(y, HEIGHT)
      (READ_COLUMNS + HALF_ALPHA_EDGE).forEach { column ->
        val x = (column + 0.5) / WIDTH
        val expected = TapePictureSignal.pixelAt(x, row, tick, vhs.seed, WIDTH, params) { halfAlpha(it).toDoubles() }
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

  // The first seed, row and column where a dropout of the look [lookOf] builds is in the middle of its fall at the read
  // time, on a frame [height] tall.
  private fun midFall(
    height: Int,
    lookOf: (Int) -> TapeLook,
  ): Triple<TapeLook, Int, Int> {
    val tick = TapePictureSignal.tickAt(SECONDS)
    for (seed in 1..SEEDS) {
      val look = lookOf(seed)
      val params = TapePictureParams.of(look)
      for (y in 0 until height) {
        val row = TapePictureSignal.virtualRow(y, height)
        for (column in 1 until WIDTH) {
          val here = TapePictureSignal.dropout(seed, tick, row, (column + 0.5) / WIDTH, params)
          val before = TapePictureSignal.dropout(seed, tick, row, (column - 0.5) / WIDTH, params)
          if (here in MID_FALL && before > here) return Triple(look, y, column)
        }
      }
    }
    error("no dropout in the middle of its fall in $SEEDS seeds")
  }

  private fun assertRowMatches(
    frame: FloatArray,
    vhs: TapeLook,
    y: Int,
    height: Int,
    source: (Int) -> FloatArray = ::ramp,
    columns: List<Int> = READ_COLUMNS,
  ) {
    val params = TapePictureParams.of(vhs)
    val tick = TapePictureSignal.tickAt(SECONDS)
    val row = TapePictureSignal.virtualRow(y, height)
    columns.forEach { column ->
      val x = (column + 0.5) / WIDTH
      val expected = TapePictureSignal.pixelAt(x, row, tick, vhs.seed, WIDTH, params) { source(it).toDoubles() }
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
    vhs: TapeLook,
    seconds: Double,
    width: Int,
    height: Int,
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

    val program = TapePictureShaderProgram(vhs)
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

  private fun edge(column: Int): FloatArray = if (column < WIDTH / 2) EDGE_LEFT else EDGE_RIGHT

  private companion object {
    // Mid snow in a tall band that rolls through the frame once every two read times, so it sits mid frame at the read.
    val SNOWY = Vhs(tracking = Tracking.Rolling((2 * SECONDS).seconds, height = 0.3f, snow = 0.5f))
    const val WIDTH = 512
    const val HEIGHT = 480
    const val CHANNELS = 4
    const val RGB = 3
    const val CANVAS_LEFT = 128
    const val CANVAS_RIGHT = 384
    const val BAR_MARGIN = 4
    const val RAMP_STEPS = 1024f
    const val SECONDS = 25.0
    const val TOLERANCE = 1e-4
    const val SEEDS = 200
    val MID_FALL = 0.3..0.7
    val READ_ROWS = listOf(60, 240, 300, 475)
    val READ_COLUMNS = listOf(0, 128, 256, 384, WIDTH - 1)
    val HALF_ALPHA_EDGE = (WIDTH / 2 - 6..WIDTH / 2 + 6).toList()
    const val TALL_HEIGHT = 1080
    const val EXACT_ROW_STEP = 9
    val EDGE_LEFT = floatArrayOf(0.75f, 0.5f, 0.25f)
    val EDGE_RIGHT = floatArrayOf(0.25f, 0.5f, 0.75f)
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

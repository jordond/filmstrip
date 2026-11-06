package dev.jordond.filmstrip.effects.filters.crt

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.opengl.GLES30
import androidx.media3.common.util.GlUtil
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.Assume.assumeTrue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Reads the shader's own output, with no encoder in the way, against [CrtSignal].
 *
 * The input is a ramp whose codes half floats hold exactly, drawn into a 32 bit float target, so a read resolves the
 * signal to well under one 8 bit code. A device with no float color target skips, since 8 bits cannot read 1e-4.
 */
class CrtShaderProgramTest {
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
  fun everyChannelLandsOnTheSignalAtAMidIntensity() = assertLandsOnTheSignal(WIDTH, HEIGHT)

  @Test
  fun a1080pFrameLandsOnTheSignal() = assertLandsOnTheSignal(FULL_HD_WIDTH, FULL_HD_HEIGHT)

  @Test
  fun aFrameOddToThePatternsLandsOnTheSignal() = assertLandsOnTheSignal(UNEVEN_WIDTH, UNEVEN_HEIGHT)

  @Test
  fun aTallFrameLandsOnTheSignal() = assertLandsOnTheSignal(HEIGHT, WIDTH)

  @Test
  fun aSmallerFrameIsTheLargerFrameBoxedDown() {
    val crt = Crt(MID)
    val flat = { _: Int, _: Int -> floatArrayOf(GREY, GREY, GREY, 1f) }
    val large = draw(crt, LARGE_WIDTH, LARGE_HEIGHT, flat)
    val small = draw(crt, LARGE_WIDTH / 2, LARGE_HEIGHT / 2, flat)

    var worst = 0.0
    var worstPoint = 0.0
    for (row in 0 until LARGE_HEIGHT / 2) {
      for (column in 0 until LARGE_WIDTH / 2) {
        for (channel in 0 until RGB) {
          val read = small[(row * LARGE_WIDTH / 2 + column) * CHANNELS + channel].toDouble()
          var sum = 0.0
          for (dy in 0..1) {
            for (dx in 0..1) {
              sum += large[((row * 2 + dy) * LARGE_WIDTH + column * 2 + dx) * CHANNELS + channel]
            }
          }
          worst = max(worst, abs(read - sum / 4.0))
          worstPoint = max(worstPoint, abs(read - large[(row * 2 * LARGE_WIDTH + column * 2) * CHANNELS + channel]))
        }
      }
    }
    withClue("a nearest read of the large frame has to miss for this to tell") {
      worstPoint shouldBeGreaterThan PARITY * 2
    }
    worst shouldBeLessThan PARITY
  }

  @Test
  fun aClearBarStaysClearAndThePicturesEdgeEndsInBlackWithItsAlpha() {
    val crt = Crt(1f)
    val params = CrtParams.of(crt)
    val frame =
      draw(crt, WIDTH, HEIGHT) { column, _ ->
        if (column in CANVAS_LEFT until CANVAS_RIGHT) floatArrayOf(BRIGHT, BRIGHT, BRIGHT, 1f) else FloatArray(CHANNELS)
      }

    val bar = pixel(frame, WIDTH, CANVAS_LEFT / 2, HEIGHT / 2)
    bar.toList() shouldBe listOf(0f, 0f, 0f, 0f)
    val top = (0 until HEIGHT).first { row -> coverage(WIDTH / 2, row, WIDTH, HEIGHT, params) > 0.0 } - 1
    val edge = pixel(frame, WIDTH, WIDTH / 2, top)
    withClue("past the curve the picture is black, with the alpha of the edge it clamps to") {
      edge[0].toDouble() shouldBe (0.0 plusOrMinus TOLERANCE)
      edge[3].toDouble() shouldBe (1.0 plusOrMinus TOLERANCE)
    }
  }

  @Test
  fun theGainStopsAtAPremultipliedPixelsOwnAlpha() {
    val crt = Crt(1f)
    val params = CrtParams.of(crt)
    val frame = draw(crt, FULL_HD_WIDTH, FULL_HD_HEIGHT) { _, _ -> floatArrayOf(HALF, HALF, HALF, HALF) }

    val (x, y) =
      (0 until CREST_READS)
        .flatMap { dy -> (0 until CREST_READS).map { dx -> FULL_HD_WIDTH / 2 + dx to FULL_HD_HEIGHT / 2 + dy } }
        .first { (x, y) -> unclamped(x, y, params) > 1.0 + CREST_MARGIN }

    val read = pixel(frame, FULL_HD_WIDTH, x, y)
    read[0].toDouble() shouldBe (HALF.toDouble() plusOrMinus TOLERANCE)
    read[3].toDouble() shouldBe (HALF.toDouble() plusOrMinus TOLERANCE)
  }

  // What the look multiplies red by at a 1080p pixel, before anything stops it.
  private fun unclamped(
    x: Int,
    y: Int,
    params: CrtParams,
  ): Double {
    val u = (x + 0.5) / FULL_HD_WIDTH
    val v = (y + 0.5) / FULL_HD_HEIGHT
    val source = CrtSignal.barrelMap(u, v, FULL_HD_WIDTH, FULL_HD_HEIGHT, params.barrelK)
    return CrtSignal.scanlineAt(source[1], 1.0 / FULL_HD_HEIGHT, params.scanlineDepth) * params.gain *
      CrtSignal.maskAt(source[0], 0, 1.0 / FULL_HD_WIDTH, params.maskDepth) *
      CrtSignal.vignetteAt(u, v, FULL_HD_WIDTH, FULL_HD_HEIGHT, params.vignette)
  }

  private fun assertLandsOnTheSignal(
    width: Int,
    height: Int,
  ) {
    val crt = Crt(MID)
    val params = CrtParams.of(crt)
    val frame = draw(crt, width, height) { column, _ -> ramp(column) }
    val read = { x: Int, y: Int -> pixel(frame, width, x, y) }

    readRows(height, width, params).forEach { y ->
      readColumns(width, height, params).forEach { x ->
        val wanted =
          CrtSignal.pixelAt((x + 0.5) / width, (y + 0.5) / height, width, height, params) { u, _ -> rampAt(u, width) }
        val tolerance = if (coverage(x, y, width, height, params) in EDGE_RANGE) EDGE_TOLERANCE else TOLERANCE
        for (channel in 0 until RGB) {
          withClue("column $x row $y channel $channel of $width by $height") {
            abs(read(x, y)[channel] - wanted[channel]) shouldBeLessThan tolerance
          }
        }
      }
    }
  }

  private fun coverage(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    params: CrtParams,
  ): Double = CrtSignal.coverageAt((x + 0.5) / width, (y + 0.5) / height, width, height, params.barrelK)

  // A spread, the middle, and one pixel either side of the curve's edge.
  private fun readRows(
    height: Int,
    width: Int,
    params: CrtParams,
  ): List<Int> = readsAcross(height) { coverage(width / 2, it, width, height, params) > 0.0 }

  private fun readColumns(
    width: Int,
    height: Int,
    params: CrtParams,
  ): List<Int> = readsAcross(width) { coverage(it, height / 2, width, height, params) > 0.0 }

  private fun readsAcross(
    size: Int,
    lit: (Int) -> Boolean,
  ): List<Int> {
    val edge = (0 until size).first(lit)
    val spread = (0 until SPREAD).map { it * (size - 1) / (SPREAD - 1) }
    val atEdge = listOf(edge - 1, edge, edge + 1, size - edge - 2, size - edge - 1, size - edge)
    return (spread + atEdge + (size / 2)).filter { it in 0 until size }.distinct()
  }

  private fun pixel(
    frame: FloatArray,
    width: Int,
    x: Int,
    y: Int,
  ): FloatArray = FloatArray(CHANNELS) { frame[(y * width + x) * CHANNELS + it] }

  private fun draw(
    crt: Crt,
    width: Int,
    height: Int,
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

    val program = CrtShaderProgram(crt)
    program.configure(width, height)
    program.drawFrame(input, 0L)

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

  // A ramp whose step a half float holds exactly at any of these widths, red and blue running opposite ways. Under 1.0
  // a half float has a step of 1/2048 or finer, so every code here is one it stores as written.
  private fun ramp(column: Int): FloatArray {
    val code = RAMP_START + column / RAMP_STEPS
    return floatArrayOf(code, 0.5f, 1f - code, 1f)
  }

  private fun rampAt(
    u: Double,
    width: Int,
  ): DoubleArray {
    // Bilinear over a straight ramp, flat past the centres of the two outside texels.
    val column = (u * width - 0.5).coerceIn(0.0, width - 1.0)
    val code = RAMP_START + column / RAMP_STEPS
    return doubleArrayOf(code, 0.5, 1.0 - code)
  }

  private companion object {
    const val MID = 0.5f
    const val WIDTH = 640
    const val HEIGHT = 360
    const val LARGE_WIDTH = 1280
    const val LARGE_HEIGHT = 720
    const val FULL_HD_WIDTH = 1920
    const val FULL_HD_HEIGHT = 1080
    const val UNEVEN_WIDTH = 1366
    const val UNEVEN_HEIGHT = 768
    const val CHANNELS = 4
    const val RGB = 3
    const val SPREAD = 9
    const val RAMP_START = 0.03125f
    const val RAMP_STEPS = 2048f
    const val GREY = 0.5f
    const val BRIGHT = 0.75f
    const val HALF = 0.5f
    const val CREST_READS = 8
    const val CREST_MARGIN = 0.02
    const val CANVAS_LEFT = 160
    const val CANVAS_RIGHT = 480
    const val TOLERANCE = 1e-4

    // A box and a small frame differ by the footprint's stretch, and by a pattern weighed unevenly where the edge
    // crosses.
    const val PARITY = 0.012

    // A source position in a 32 bit float is good to about 1e-7, which over a pixel's reach moves the coverage by a few
    // 1e-4. That is the GPU's float, and only a pixel the edge crosses reads it.
    const val EDGE_TOLERANCE = 5e-4
    val EDGE_RANGE = 1e-6..(1.0 - 1e-6)
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

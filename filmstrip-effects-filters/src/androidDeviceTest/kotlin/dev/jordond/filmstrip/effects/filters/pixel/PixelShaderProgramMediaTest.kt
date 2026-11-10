package dev.jordond.filmstrip.effects.filters.pixel

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.opengl.GLES30
import androidx.media3.common.util.GlUtil
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
 * Reads the shader's own output, with no encoder in the way, against [PixelSignal.pixelAt].
 *
 * The input is a pair of ramps whose codes half floats hold exactly, drawn into a 32 bit float target. A texture here
 * runs up the picture the way media3's do, so its first row is the picture's bottom row. A device with no float color
 * target skips.
 */
class PixelShaderProgramMediaTest {
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
  fun levelsAndDitherLandOnTheSignal() = assertLandsOnTheSignal(LEVELS, WIDTH, HEIGHT)

  @Test
  fun aPaletteLandsOnTheSignal() = assertLandsOnTheSignal(PALETTE, WIDTH, HEIGHT)

  @Test
  fun aSixteenColourPaletteLandsOnTheSignal() = assertLandsOnTheSignal(SIXTEEN, WIDTH, HEIGHT)

  @Test
  fun aMosaicWithNoLevelsLandsOnTheSignal() = assertLandsOnTheSignal(MOSAIC, WIDTH, HEIGHT)

  @Test
  fun levelsWithNoDitherLandOnTheSignal() = assertLandsOnTheSignal(UNDITHERED, WIDTH, HEIGHT)

  @Test
  fun aTwoColourPaletteLandsOnTheSignal() = assertLandsOnTheSignal(TWO, WIDTH, HEIGHT)

  @Test
  fun aSmallerFrameLandsOnTheSameCells() {
    PixelSignal.columns(WIDTH / 2, HEIGHT / 2, LEVELS.cells) shouldBe PixelSignal.columns(WIDTH, HEIGHT, LEVELS.cells)
    assertLandsOnTheSignal(LEVELS, WIDTH / 2, HEIGHT / 2)
  }

  // A clear bar, then straight colour at half alpha up to the middle, then the opaque picture.
  @Test
  fun aClearBarStaysClearAndAHalfAlphaLandsOnTheSignalInStraightColour() {
    val source = { column: Int, row: Int ->
      when {
        column < BAR -> FloatArray(CHANNELS)
        column < WIDTH / 2 -> ramps(column, row).also { it[ALPHA] = HALF }
        else -> ramps(column, row)
      }
    }
    val frame = draw(LEVELS, WIDTH, HEIGHT, source)
    val columns = PixelSignal.columns(WIDTH, HEIGHT, LEVELS.cells)

    reads(HEIGHT).forEach { y ->
      reads(WIDTH).forEach { x ->
        val centreX = PixelSignal.centreOf(PixelSignal.cellOf(x, columns, WIDTH), columns, WIDTH)
        val centreY = PixelSignal.centreOf(PixelSignal.cellOf(y, LEVELS.cells, HEIGHT), LEVELS.cells, HEIGHT)
        val alpha = source(centreX, HEIGHT - 1 - centreY)[ALPHA].toDouble()
        val wanted =
          PixelSignal.pixelAt(x, y, WIDTH, HEIGHT, LEVELS) { px, py ->
            source(px, HEIGHT - 1 - py).take(RGB).map(Float::toDouble).toDoubleArray()
          }
        val read = pixel(frame, WIDTH, x, HEIGHT - 1 - y)
        withClue("the alpha at column $x row $y") { abs(read[ALPHA] - alpha) shouldBeLessThan TOLERANCE }
        if (alpha > 0.0) {
          for (channel in 0 until RGB) {
            withClue("column $x row $y channel $channel") {
              abs(read[channel] - wanted[channel]) shouldBeLessThan TOLERANCE
            }
          }
        }
      }
    }
  }

  private fun assertLandsOnTheSignal(
    look: Pixel,
    width: Int,
    height: Int,
  ) {
    val frame = draw(look, width, height) { column, row -> ramps(column, row) }
    // The picture's rows from the top, as the signal counts them.
    val picture = { x: Int, y: Int -> ramps(x, height - 1 - y).take(RGB).map(Float::toDouble).toDoubleArray() }

    reads(height).forEach { y ->
      reads(width).forEach { x ->
        val wanted = PixelSignal.pixelAt(x, y, width, height, look, picture)
        val read = pixel(frame, width, x, height - 1 - y)
        for (channel in 0 until RGB) {
          withClue("column $x row $y channel $channel of $width by $height") {
            abs(read[channel] - wanted[channel]) shouldBeLessThan TOLERANCE
          }
        }
      }
    }
  }

  // Every pixel through the middle, so each cell edge there is crossed, plus a spread out to both sides.
  private fun reads(size: Int): List<Int> =
    ((size / 2 - MIDDLE_READS until size / 2 + MIDDLE_READS) + (0 until size step SPREAD_STRIDE) + (size - 1))
      .distinct()

  private fun pixel(
    frame: FloatArray,
    width: Int,
    x: Int,
    y: Int,
  ): FloatArray = FloatArray(CHANNELS) { frame[(y * width + x) * CHANNELS + it] }

  private fun draw(
    look: Pixel,
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

    val program = PixelShaderProgram(look)
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

  // Red runs across and green runs down the texture, each on a step a half float holds exactly.
  private fun ramps(
    column: Int,
    row: Int,
  ): FloatArray = floatArrayOf(RAMP_START + column / RAMP_STEPS, RAMP_START + row / RAMP_STEPS, BLUE, 1f)

  private companion object {
    const val CELLS = 90
    val LEVELS = Pixel(cells = CELLS, levels = 6, dither = 0.5f)
    val PALETTE = Pixel(cells = CELLS, dither = 0.5f, palette = PixelPalette.GameBoy)
    val MOSAIC = Pixel(cells = CELLS)
    val UNDITHERED = Pixel(cells = CELLS, levels = 6)

    // A dark and a light entry either side of the ramps, so both get picked.
    val TWO = Pixel(cells = CELLS, dither = 0.5f, palette = PixelPalette(listOf(0x202020, 0x806060)))

    // A full palette on a diagonal, so the ramps cross many of its entries.
    val SIXTEEN =
      Pixel(
        cells = CELLS,
        dither = 0.5f,
        palette = PixelPalette(List(PixelPalette.MAX_COLORS) { (it * 0x0F0B07 + 0x081018) and 0xFFFFFF }),
      )
    const val WIDTH = 1280
    const val HEIGHT = 720
    const val CHANNELS = 4
    const val RGB = 3
    const val MIDDLE_READS = 24
    const val SPREAD_STRIDE = 37
    const val BAR = 160
    const val RAMP_START = 0.03125f
    const val RAMP_STEPS = 2048f
    const val BLUE = 0.375f
    const val ALPHA = 3
    const val HALF = 0.5f

    // A step or a palette entry is the same code on the GPU, so only the target's own float is left.
    const val TOLERANCE = 1e-5
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

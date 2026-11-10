package dev.jordond.filmstrip.effects.filters.camcorder

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.opengl.GLES30
import androidx.media3.common.util.GlUtil
import androidx.media3.effect.BaseGlShaderProgram
import dev.jordond.filmstrip.effects.filters.vhs.TapeSpeed
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.effects.filters.vhs.VhsParams
import dev.jordond.filmstrip.effects.filters.vhs.VhsShaderProgram
import dev.jordond.filmstrip.effects.filters.vhs.VhsSignal
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Assume.assumeTrue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Reads the media3 pass a [Camcorder] draws, with no encoder in the way, against [CamcorderOsd.rasterAt].
 *
 * Frames are drawn into a 32 bit float target over a ramp, so a pixel the display leaves clear reads the ramp and a
 * lit or edge pixel reads the raster's code exactly.
 */
class CamcorderEffectMediaTest {
  private var display = EGL14.EGL_NO_DISPLAY
  private var context = EGL14.EGL_NO_CONTEXT
  private var surface = EGL14.EGL_NO_SURFACE

  @BeforeTest
  fun makeContext() {
    display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    val version = IntArray(2)
    EGL14.eglInitialize(display, version, 0, version, 1)
    val configs = arrayOfNulls<EGLConfig>(1)
    EGL14.eglChooseConfig(display, CONFIG, 0, configs, 0, 1, IntArray(1), 0)
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
  fun everyGlyphAndEdgePixelLandsOnTheRaster() {
    val frame = drawn(listOf(display()), WIDTH, HEIGHT, listOf(BLINK_ON)).single()

    assertOnRaster(frame, OSD.rasterAt(BLINK_ON, WIDTH, HEIGHT))
  }

  @Test
  fun aFrameEachSideOfASecondShowsItsOwnDisplay() {
    val before = OSD.rasterAt(BEFORE_SECOND, WIDTH, HEIGHT)
    val after = OSD.rasterAt(AFTER_SECOND, WIDTH, HEIGHT)
    val frames = drawn(listOf(display()), WIDTH, HEIGHT, listOf(BEFORE_SECOND, AFTER_SECOND))

    withClue("the counter has to change across the second for this to tell") {
      before.pixels.contentEquals(after.pixels) shouldBe false
    }
    assertOnRaster(frames[0], before)
    assertOnRaster(frames[1], after)
  }

  @Test
  fun aSmallerFrameIsTheRasterAtItsOwnSize() {
    val width = WIDTH / 2
    val height = HEIGHT / 2
    val frame = drawn(listOf(display()), width, height, listOf(BLINK_ON)).single()

    assertOnRaster(frame, OSD.rasterAt(BLINK_ON, width, height))
  }

  @Test
  fun aDisplayAheadOfVhsIsRecordedOnTheTape() {
    val vhs = Vhs(intensity = 0.5f)
    val recorded = drawn(listOf(display()), WIDTH, HEIGHT, listOf(BLINK_ON)).single()
    val burned = drawn(listOf(display(), VhsShaderProgram(vhs)), WIDTH, HEIGHT, listOf(BLINK_ON)).single()
    val params = VhsParams.of(vhs)
    val tick = VhsSignal.tickAt(BLINK_ON.inWholeMicroseconds / 1e6)
    val raster = OSD.rasterAt(BLINK_ON, WIDTH, HEIGHT)
    var moved = 0.0

    textRows(raster).forEach { y ->
      val row = VhsSignal.virtualRow(y, HEIGHT)
      for (column in 0 until WIDTH) {
        val x = (column + 0.5) / WIDTH
        val expected = VhsSignal.pixelAt(x, row, tick, vhs.seed, WIDTH, params) { at -> recorded.rowAt(at, y) }
        val read = burned.rgbAt(column, y)
        for (channel in 0 until RGB) {
          withClue("row $y column $column channel $channel") {
            read[channel] shouldBe (expected[channel] plusOrMinus TOLERANCE)
          }
        }
        if (raster.argbAt(column, y) != HIDDEN) moved = max(moved, read.gapTo(raster.argbAt(column, y)))
      }
    }
    withClue("the tape moved the display off its own codes") { moved shouldBeGreaterThan 0.1 }
  }

  @Test
  fun aDisplayAfterVhsStaysClean() {
    val vhs = Vhs(intensity = 0.5f)
    val frame = drawn(listOf(VhsShaderProgram(vhs), display()), WIDTH, HEIGHT, listOf(BLINK_ON)).single()
    val raster = OSD.rasterAt(BLINK_ON, WIDTH, HEIGHT)

    var lit = 0
    for (y in 0 until HEIGHT) {
      for (x in 0 until WIDTH) {
        val argb = raster.argbAt(x, y)
        if (argb == HIDDEN) continue
        lit++
        withClue("($x, $y)") { frame.rgbAt(x, y).gapTo(argb) shouldBe (0.0 plusOrMinus TOLERANCE) }
      }
    }
    lit shouldNotBe 0
  }

  private fun display(): BaseGlShaderProgram = CamcorderShaderProgram(OSD)

  private fun assertOnRaster(
    frame: Frame,
    raster: OsdRaster,
  ) {
    var lit = 0
    for (y in 0 until frame.height) {
      for (x in 0 until frame.width) {
        val argb = raster.argbAt(x, y)
        val read = frame.rgbAt(x, y)
        if (argb == HIDDEN) {
          val ramp = ramp(x, frame.width)
          for (channel in 0 until RGB) {
            withClue("clear ($x, $y)") { read[channel] shouldBe (ramp[channel].toDouble() plusOrMinus TOLERANCE) }
          }
        } else {
          lit++
          withClue("lit ($x, $y)") { read.gapTo(argb) shouldBe (0.0 plusOrMinus TOLERANCE) }
        }
      }
    }
    lit shouldNotBe 0
  }

  // Draws [programs] in order over a ramp once for each of [times], with the same programs throughout.
  private fun drawn(
    programs: List<BaseGlShaderProgram>,
    width: Int,
    height: Int,
    times: List<Duration>,
  ): List<Frame> {
    programs.forEach { it.configure(width, height) }
    val input = rampTexture(width, height)
    val targets = List(programs.size) { floatTarget(width, height) }
    val frames =
      times.map { time ->
        var read = input
        programs.forEachIndexed { index, program ->
          GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, targets[index].second)
          GLES20.glViewport(0, 0, width, height)
          program.drawFrame(read, time.inWholeMicroseconds)
          read = targets[index].first
        }
        readBack(width, height)
      }
    programs.forEach { it.release() }
    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    targets.forEach { (texture, framebuffer) ->
      GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
      GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
    }
    GLES20.glDeleteTextures(1, intArrayOf(input), 0)
    return frames
  }

  private fun floatTarget(
    width: Int,
    height: Int,
  ): Pair<Int, Int> {
    val texture = IntArray(1)
    val framebuffer = IntArray(1)
    GLES20.glGenTextures(1, texture, 0)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
    GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, GLES30.GL_RGBA32F, width, height)
    linear()
    GLES20.glGenFramebuffers(1, framebuffer, 0)
    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer[0])
    GLES20.glFramebufferTexture2D(
      GLES20.GL_FRAMEBUFFER,
      GLES20.GL_COLOR_ATTACHMENT0,
      GLES20.GL_TEXTURE_2D,
      texture[0],
      0,
    )
    return texture[0] to framebuffer[0]
  }

  private fun readBack(
    width: Int,
    height: Int,
  ): Frame {
    val bytes = ByteBuffer.allocateDirect(width * height * CHANNELS * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
    GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_FLOAT, bytes)
    GlUtil.checkGlError()
    val read = FloatArray(width * height * CHANNELS)
    bytes.asFloatBuffer().get(read)
    return Frame(width, height, read)
  }

  private fun rampTexture(
    width: Int,
    height: Int,
  ): Int {
    val texels = ByteBuffer.allocateDirect(width * height * CHANNELS * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
    repeat(height) {
      for (x in 0 until width) {
        ramp(x, width).forEach(texels::putFloat)
        texels.putFloat(1f)
      }
    }
    texels.rewind()
    val texture = IntArray(1)
    GLES20.glGenTextures(1, texture, 0)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture[0])
    linear()
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

  private fun linear() {
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
  }

  // A ramp whose every step a half float holds exactly, red and blue running opposite ways.
  private fun ramp(
    column: Int,
    width: Int,
  ): FloatArray {
    val code = 0.25f + (column * RAMP_SPAN / width).toInt() / RAMP_STEPS
    return floatArrayOf(code, 0.5f, 1f - code)
  }

  // The rows through the middle of the first line of each corner's text.
  private fun textRows(raster: OsdRaster): List<Int> =
    (0 until raster.height).filter { y -> (0 until raster.width).any { raster.argbAt(it, y) != HIDDEN } }.let {
      listOf(it.first() + 2, it[it.size / 2], it.last() - 2)
    }

  // A frame read back from GL, its rows bottom up.
  private class Frame(
    val width: Int,
    val height: Int,
    val texels: FloatArray,
  ) {
    fun rgbAt(
      x: Int,
      y: Int,
    ): DoubleArray {
      val at = ((height - 1 - y) * width + x) * CHANNELS
      return DoubleArray(RGB) { texels[at + it].toDouble() }
    }

    // Bilinear over the row the way the sampler reads it, flat past the centres of the two outside texels.
    fun rowAt(
      x: Double,
      y: Int,
    ): DoubleArray {
      val across = (x * width - 0.5).coerceIn(0.0, width - 1.0)
      val left = across.toInt()
      val right = (left + 1).coerceAtMost(width - 1)
      val blend = across - left
      val a = rgbAt(left, y)
      val b = rgbAt(right, y)
      return DoubleArray(RGB) { a[it] + (b[it] - a[it]) * blend }
    }
  }

  private fun DoubleArray.gapTo(argb: Int): Double =
    maxOf(
      abs(this[0] - (argb shr 16 and 0xFF) / FULL),
      abs(this[1] - (argb shr 8 and 0xFF) / FULL),
      abs(this[2] - (argb and 0xFF) / FULL),
    )

  private companion object {
    val OSD =
      CamcorderOsd(
        Camcorder(
          listOf(
            OsdElement.Rec(),
            OsdElement.Counter(withFrames = true),
            OsdElement.Tape(TapeSpeed.SP),
            OsdElement.Clock(),
          ),
        ),
        30f,
      )
    val BLINK_ON = 250.milliseconds
    val BEFORE_SECOND = 1_900.milliseconds
    val AFTER_SECOND = 2_100.milliseconds
    const val WIDTH = 640
    const val HEIGHT = 360
    const val CHANNELS = 4
    const val RGB = 3
    const val FULL = 255.0
    const val HIDDEN = 0
    const val RAMP_SPAN = 512
    const val RAMP_STEPS = 1024f
    const val TOLERANCE = 1e-4

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

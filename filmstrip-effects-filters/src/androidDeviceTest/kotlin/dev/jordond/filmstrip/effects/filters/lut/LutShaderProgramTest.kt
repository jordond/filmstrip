package dev.jordond.filmstrip.effects.filters.lut

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlUtil
import androidx.test.platform.app.InstrumentationRegistry
import dev.jordond.filmstrip.InternalFilmstripApi
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.Assume.assumeTrue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Reads the shader's own output, with no encoder in the way, against [CubeLut.at].
 *
 * The input is a ramp whose codes half floats hold exactly, drawn into a 32 bit float target, so a read resolves the
 * table to well under one 8 bit code. A device with no float color target skips, since 8 bits cannot read 1e-4.
 */
@OptIn(InternalFilmstripApi::class)
class LutShaderProgramTest {
  private var display = EGL14.EGL_NO_DISPLAY
  private var context = EGL14.EGL_NO_CONTEXT
  private var surface = EGL14.EGL_NO_SURFACE

  private val table =
    CubeLut.parse(
      buildString {
        append("LUT_3D_SIZE 5\n")
        for (blue in 0..4) {
          for (green in 0..4) {
            for (red in 0..4) {
              val r = red / 4.0
              val g = green / 4.0
              val b = blue / 4.0
              append(r * r * 0.7 + b * 0.2).append(' ')
              append(g * (1.0 - r * 0.4) + b * b * 0.1).append(' ')
              append((1.0 - b) * 0.5 + g * r * 0.4).append('\n')
            }
          }
        }
      },
    )

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
    Log.i("LutShaderProgramTest", "context is OpenGL ES ${GlUtil.getContextMajorVersion()}")
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
  fun everyPixelReadsLikeTheTable() {
    val frame = draw(Lut(table), WIDTH, HEIGHT, ::opaqueRamp)

    worstGap(frame, WIDTH, HEIGHT, table, ::opaqueRamp) shouldBeLessThan TOLERANCE
  }

  @Test
  fun aPreviewHeightReadsLikeTheTable() {
    val frame = draw(Lut(table), WIDTH / 3, HEIGHT / 3, ::opaqueRamp)

    worstGap(frame, WIDTH / 3, HEIGHT / 3, table, ::opaqueRamp) shouldBeLessThan TOLERANCE
  }

  @Test
  fun intensityReadsLikeTheBlendedTable() {
    val frame = draw(Lut(table, 0.5f), WIDTH, HEIGHT, ::opaqueRamp)

    worstGap(frame, WIDTH, HEIGHT, table.blended(0.5f), ::opaqueRamp) shouldBeLessThan TOLERANCE
    withClue("the full table has to read apart from the blend for this to tell") {
      worstGap(frame, WIDTH, HEIGHT, table, ::opaqueRamp) shouldBeGreaterThan TOLERANCE * 10
    }
  }

  @Test
  fun aTableFinerThanEightBitsReadsToSixteen() {
    val fine =
      CubeLut.parse(
        buildString {
          append("LUT_3D_SIZE 2\n")
          repeat(8) { append("0.50123 0.25037 0.75003\n") }
        },
      )
    val frame = draw(Lut(fine), WIDTH, HEIGHT, ::opaqueRamp)

    worstGap(frame, WIDTH, HEIGHT, fine, ::opaqueRamp) shouldBeLessThan TOLERANCE
  }

  @Test
  fun aPartAlphaPixelReadsItsStraightColourAndAClearBarStaysClear() {
    val source = { column: Int, _: Int ->
      if (column < WIDTH / 2) {
        FloatArray(CHANNELS)
      } else {
        floatArrayOf(HALF_ALPHA * GREY, HALF_ALPHA * GREY, HALF_ALPHA * GREY, HALF_ALPHA)
      }
    }
    val frame = draw(Lut(table), WIDTH, HEIGHT, source)

    val bar = (0 until CHANNELS).map { frame[(HEIGHT / 2 * WIDTH + WIDTH / 4) * CHANNELS + it] }
    bar shouldBe listOf(0f, 0f, 0f, 0f)
    val want = table.at(GREY, GREY, GREY)
    val at = (HEIGHT / 2 * WIDTH + WIDTH * 3 / 4) * CHANNELS
    for (channel in 0 until RGB) {
      abs(frame[at + channel] - want[channel] * HALF_ALPHA).toDouble() shouldBeLessThan TOLERANCE
    }
    abs(frame[at + RGB] - HALF_ALPHA).toDouble() shouldBeLessThan TOLERANCE
  }

  @Test
  fun aTablePastTheContextsLimitIsRefusedByName() {
    val atlasWidth = lutAtlasWidth(table.size)

    requireLutFits(table.size, atlasWidth)
    val refused = assertFailsWith<VideoFrameProcessingException> { requireLutFits(table.size, atlasWidth - 1) }

    refused.message.orEmpty() shouldContain "MAX_TEXTURE_SIZE is ${atlasWidth - 1}"
  }

  @Test
  fun aFrameProcessorInHdrIsRefusedByName() {
    val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    val refused =
      assertFailsWith<VideoFrameProcessingException> { LutEffect(Lut(table)).toGlShaderProgram(targetContext, true) }

    refused.message.orEmpty() shouldContain "Lut requires SDR"
  }

  private fun worstGap(
    frame: FloatArray,
    width: Int,
    height: Int,
    want: CubeLut,
    source: (Int, Int) -> FloatArray,
  ): Double {
    var worst = 0.0
    for (row in 0 until height) {
      for (column in 0 until width) {
        val colour = source(column, row)
        val read = want.at(colour[0], colour[1], colour[2])
        for (channel in 0 until RGB) {
          worst = max(worst, abs(frame[(row * width + column) * CHANNELS + channel] - read[channel]).toDouble())
        }
      }
    }
    return worst
  }

  // Runs red and blue opposite ways and green down, at steps a half float holds exactly.
  private fun opaqueRamp(
    column: Int,
    row: Int,
  ): FloatArray {
    val across = 0.125f + column / RAMP_STEPS
    return floatArrayOf(across, 0.25f + row / RAMP_STEPS, 1f - across, 1f)
  }

  private fun draw(
    lut: Lut,
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

    val program = LutShaderProgram(lut)
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
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
    GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
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
    const val WIDTH = 640
    const val HEIGHT = 360
    const val CHANNELS = 4
    const val RGB = 3
    const val RAMP_STEPS = 1024f
    const val GREY = 0.4375f
    const val HALF_ALPHA = 0.5f
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

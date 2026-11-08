package dev.jordond.filmstrip.media3.internal

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * One full-frame draw of a fragment shader over an input texture, read back off a real GL context.
 *
 * On a device because the draw needs EGL, which the host stubs do not answer.
 *
 * Camel case test names, because dex below version 040, which needs API 30, rejects a space in a method name.
 */
class FullFramePassMediaTest {
  private lateinit var display: EGLDisplay
  private lateinit var context: EGLContext

  @BeforeTest
  fun focus() {
    display = GlUtil.getDefaultEglDisplay()
    context = GlUtil.createEglContext(display)
    GlUtil.createFocusedPlaceholderEglSurface(context, display)
  }

  @AfterTest
  fun release() {
    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
    GlUtil.destroyEglContext(display, context)
  }

  @Test
  fun theOutputIsTheInputsOwnSize() {
    val pass = FullFramePass(useHdr = false, fragmentShader = SCALING_SHADER)
    try {
      pass.configure(WIDTH, HEIGHT) shouldBe Size(WIDTH, HEIGHT)
      pass.configure(17, 9) shouldBe Size(17, 9)
    } finally {
      pass.release()
    }
  }

  // Left and right halves differ, so a draw that mirrored the frame or sampled one texel everywhere reads wrong.
  @Test
  fun everyTexelIsDrawnWhereItSitsWithTheUniformsSetOnBuild() {
    val pass = FullFramePass(useHdr = false, fragmentShader = SCALING_SHADER) { setFloatUniform("uScale", HALF) }
    try {
      val (left, right) = draw(pass)

      assertColor(intArrayOf(LEFT_RED / 2, LEFT_GREEN / 2, LEFT_BLUE / 2), left, "left")
      assertColor(intArrayOf(RIGHT_RED / 2, RIGHT_GREEN / 2, RIGHT_BLUE / 2), right, "right")
    } finally {
      pass.release()
    }
  }

  @Test
  fun aShaderThatDoesNotCompileFailsAsAFrameProcessingError() {
    assertFailsWith<VideoFrameProcessingException> {
      FullFramePass(useHdr = false, fragmentShader = "#version 100\nvoid main() { gl_FragColor = nothing; }")
    }
  }

  // The left and right pixels in the middle row of the input drawn through pass, as red, green and blue codes.
  private fun draw(pass: FullFramePass): Pair<IntArray, IntArray> {
    val input =
      Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).apply {
        for (x in 0 until WIDTH) {
          val color =
            if (x < WIDTH / 2) {
              Color.rgb(LEFT_RED, LEFT_GREEN, LEFT_BLUE)
            } else {
              Color.rgb(RIGHT_RED, RIGHT_GREEN, RIGHT_BLUE)
            }
          for (y in 0 until HEIGHT) setPixel(x, y, color)
        }
      }
    val inputTexture = GlUtil.createTexture(input)
    val outputTexture = GlUtil.createTexture(WIDTH, HEIGHT, false)
    val framebuffer = GlUtil.createFboForTexture(outputTexture)
    try {
      GlUtil.focusFramebufferUsingCurrentContext(framebuffer, WIDTH, HEIGHT)
      pass.drawFrame(inputTexture, 0)
      return pixel(WIDTH / 4, HEIGHT / 2) to pixel(WIDTH * 3 / 4, HEIGHT / 2)
    } finally {
      GlUtil.deleteFbo(framebuffer)
      GlUtil.deleteTexture(outputTexture)
      GlUtil.deleteTexture(inputTexture)
      input.recycle()
    }
  }

  private fun pixel(
    x: Int,
    y: Int,
  ): IntArray {
    val buffer = ByteBuffer.allocateDirect(4)
    GLES20.glReadPixels(x, y, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
    GlUtil.checkGlError()
    return IntArray(3) { buffer.get(it).toInt() and 0xFF }
  }

  private fun assertColor(
    expected: IntArray,
    actual: IntArray,
    where: String,
  ) {
    withClue("$where read ${actual.toList()} where ${expected.toList()} was drawn") {
      expected.indices.all { abs(expected[it] - actual[it]) <= TOLERANCE } shouldBe true
    }
  }

  private companion object {
    const val WIDTH = 64
    const val HEIGHT = 32
    const val HALF = 0.5f
    const val LEFT_RED = 200
    const val LEFT_GREEN = 120
    const val LEFT_BLUE = 40
    const val RIGHT_RED = 30
    const val RIGHT_GREEN = 180
    const val RIGHT_BLUE = 220

    // One code for the rounding of a halved 8-bit sample, and one for the driver's.
    const val TOLERANCE = 2

    val SCALING_SHADER =
      """
      #version 100
      precision mediump float;
      uniform sampler2D uTexSampler;
      uniform float uScale;
      varying vec2 vTexSamplingCoord;
      void main() {
        vec4 color = texture2D(uTexSampler, vTexSamplingCoord);
        gl_FragColor = vec4(color.rgb * uScale, 1.0);
      }
      """.trimIndent()
  }
}

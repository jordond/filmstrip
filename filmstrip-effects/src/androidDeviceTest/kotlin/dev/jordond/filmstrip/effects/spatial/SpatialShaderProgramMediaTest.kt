package dev.jordond.filmstrip.effects.spatial

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.opengl.GLES30
import androidx.media3.common.util.GlUtil
import dev.jordond.filmstrip.color.BlurPlan
import dev.jordond.filmstrip.color.blurPlane
import dev.jordond.filmstrip.color.sdrSignalFromHlgScene
import dev.jordond.filmstrip.color.vignetteGain
import dev.jordond.filmstrip.effects.StepShaderProgram
import dev.jordond.filmstrip.effects.color.HdrColorMatrixUniforms
import dev.jordond.filmstrip.effects.media3SignalUniforms
import dev.jordond.filmstrip.media.HdrTransfer
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import org.junit.Assume.assumeTrue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Reads a blur's and a vignette's own output, with no encoder in the way, against the shared function each lowers.
 *
 * The input is drawn from half float codes into a 32 bit float target, so a read resolves the steps to well under one
 * 8 bit code. A device with no float color target skips, since 8 bits cannot read that finely.
 */
class SpatialShaderProgramMediaTest {
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
  fun aBlurAtExportSizeMatchesTheSharedPlan() {
    assertBlurMatches(WIDTH, HEIGHT)
  }

  @Test
  fun aBlurAtPreviewSizeMatchesTheSharedPlan() {
    assertBlurMatches(WIDTH / 2, HEIGHT / 2)
  }

  @Test
  fun aBlurOnAWidthThePlanDoesNotDivideMatchesTheSharedPlan() {
    val plan = BlurPlan.of(BLUR_SIGMA.toDouble(), UNEVEN_WIDTH)
    withClue("the plan boxes by ${plan.factor}, which divides $UNEVEN_WIDTH") {
      (UNEVEN_WIDTH % plan.factor == 0) shouldBe false
    }
    assertBlurMatches(UNEVEN_WIDTH, HEIGHT)
  }

  @Test
  fun aBlurAcrossAHorizontalEdgeMatchesTheVerticalPass() {
    assertBlurMatches(WIDTH, HEIGHT) { _, y -> if (y < HEIGHT / 2) LIGHT else DARK }
  }

  @Test
  fun aBlurOnAKeptHlgGradeRunsOnTheSdrSignal() {
    val domain = HdrTransfer.Hlg.media3SignalUniforms
    val scene = { x: Int, _: Int -> if (x < WIDTH / 2) HLG_LIGHT_SCENE else HLG_SCENE.toDouble() }
    val frame = draw(SpatialGlEffect(GaussianBlur(BLUR_SIGMA), HdrTransfer.Hlg), domain, WIDTH, HEIGHT, red = scene)
    val signal = DoubleArray(WIDTH * HEIGHT) { sdrSignalFromHlgScene(scene(it % WIDTH, it / WIDTH)) }
    val expected = blurPlane(signal, DoubleArray(signal.size) { 1.0 }, WIDTH, HEIGHT, BLUR_SIGMA.toDouble())

    var worst = 0.0
    for (x in 0 until WIDTH) {
      val read = sdrSignalFromHlgScene(frame.red(x, HEIGHT / 2, WIDTH, HEIGHT))
      worst = maxOf(worst, abs(read - expected[HEIGHT / 2 * WIDTH + x]))
    }
    val bound = if (halfFloatTargetRoundsDown()) FLOORED_HLG_BLUR_TOLERANCE else BLUR_TOLERANCE
    withClue("the kept grade's blur is $worst off the shared plan on the sdr signal at its worst column") {
      worst shouldBeLessThanOrEqualTo bound
    }
  }

  @Test
  fun aBlurOnSceneLightWouldReadOutsideEitherBound() {
    val signal = { scene: Double -> sdrSignalFromHlgScene(scene) }
    val onSceneLight = signal((HLG_LIGHT_SCENE + HLG_SCENE) / 2)
    val onTheSignal = (signal(HLG_LIGHT_SCENE) + signal(HLG_SCENE.toDouble())) / 2

    abs(onSceneLight - onTheSignal) shouldBeGreaterThan FLOORED_HLG_BLUR_TOLERANCE * 2
  }

  // Draws a value three quarters of the way up a half float step. A GPU that rounds to nearest reads the step above.
  private fun halfFloatTargetRoundsDown(): Boolean {
    val target = IntArray(1)
    val framebuffer = IntArray(1)
    GLES20.glGenTextures(1, target, 0)
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, target[0])
    GLES30.glTexStorage2D(GLES20.GL_TEXTURE_2D, 1, GLES30.GL_RGBA16F, 1, 1)
    GLES20.glGenFramebuffers(1, framebuffer, 0)
    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer[0])
    GLES20.glFramebufferTexture2D(
      GLES20.GL_FRAMEBUFFER,
      GLES20.GL_COLOR_ATTACHMENT0,
      GLES20.GL_TEXTURE_2D,
      target[0],
      0,
    )
    GLES20.glViewport(0, 0, 1, 1)
    val program = GLES20.glCreateProgram()
    val sources = listOf(GLES20.GL_VERTEX_SHADER to CONSTANT_VERTEX, GLES20.GL_FRAGMENT_SHADER to CONSTANT_FRAGMENT)
    val shaders =
      sources.map { (type, glsl) ->
        GLES20.glCreateShader(type).also {
          GLES20.glShaderSource(it, glsl)
          GLES20.glCompileShader(it)
          GLES20.glAttachShader(program, it)
        }
      }
    GLES20.glLinkProgram(program)
    GLES20.glUseProgram(program)
    val value = (1.0 + HALF_FLOAT_STEP_ABOVE_ONE * 3 / 4).toFloat()
    GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uValue"), value)
    GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)

    val bytes = ByteBuffer.allocateDirect(CHANNELS * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
    GLES20.glReadPixels(0, 0, 1, 1, GLES20.GL_RGBA, GLES20.GL_FLOAT, bytes)
    GlUtil.checkGlError()
    shaders.forEach(GLES20::glDeleteShader)
    GLES20.glDeleteProgram(program)
    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    GLES20.glDeleteFramebuffers(1, framebuffer, 0)
    GLES20.glDeleteTextures(1, target, 0)
    return bytes.asFloatBuffer().get(0) < 1.0 + HALF_FLOAT_STEP_ABOVE_ONE / 2
  }

  // A composition's presentation letterboxes a contained picture, and media3 clears the bars to no alpha.
  @Test
  fun aBlurBesideLetterboxBarsKeepsThePictureToItsEdgeAndTheBarsEmpty() {
    assertContained { _, y -> y in BAR_SIDE until HEIGHT - BAR_SIDE }
  }

  // A clip's contain crop pillarboxes its picture the same way, before the clip's own effects run.
  @Test
  fun aBlurBesidePillarboxBarsKeepsThePictureToItsEdgeAndTheBarsEmpty() {
    assertContained { x, _ -> x in BAR_SIDE until WIDTH - BAR_SIDE }
  }

  @Test
  fun aVignetteFollowsTheSharedGainAlongItsRamp() {
    assertVignetteMatches(WIDTH, HEIGHT)
  }

  @Test
  fun aVignetteAtPreviewHeightFollowsTheSharedGainAlongItsRamp() {
    assertVignetteMatches(WIDTH / 2, HEIGHT / 2)
  }

  @Test
  fun aKeptHlgGradeTakesTheGainOnItsSignal() {
    val vignette = Vignette()
    val domain = HdrTransfer.Hlg.media3SignalUniforms
    val frame = draw(SpatialGlEffect(vignette, HdrTransfer.Hlg), domain, WIDTH, HEIGHT) { _, _ -> HLG_SCENE.toDouble() }
    val signal = sdrSignalFromHlgScene(HLG_SCENE).toDouble()

    for ((u, v) in VIGNETTE_POINTS) {
      val x = (u * WIDTH).toInt()
      val y = (v * HEIGHT).toInt()
      val gain = gainAt(vignette, x, y, WIDTH, HEIGHT)
      val read = sdrSignalFromHlgScene(frame.red(x, y, WIDTH, HEIGHT).toFloat()).toDouble()
      withClue("at $x, $y the grade's signal read $read, and the shared gain $gain puts it at ${signal * gain}") {
        abs(read - signal * gain) shouldBeLessThanOrEqualTo TOLERANCE
      }
    }
  }

  private fun assertBlurMatches(
    width: Int,
    height: Int,
    red: (Int, Int) -> Double = { x, _ -> if (x < width / 2) LIGHT else DARK },
  ) {
    val frame = draw(SpatialGlEffect(GaussianBlur(BLUR_SIGMA), null), null, width, height, red = red)
    assertBlurred(frame, width, height, red) { _, _ -> true }
  }

  // Every pixel inside the picture sits within the half float rounding of the shared blur, with the pixels outside it
  // counting for nothing, and the reference moves the frame far enough that a sharp read would fail.
  private fun assertBlurred(
    frame: FloatArray,
    width: Int,
    height: Int,
    red: (Int, Int) -> Double,
    inside: (Int, Int) -> Boolean,
  ) {
    val plane = DoubleArray(width * height) { red(it % width, it / width) }
    val alpha = DoubleArray(width * height) { if (inside(it % width, it / width)) 1.0 else 0.0 }
    val expected = blurPlane(plane, alpha, width, height, BLUR_SIGMA.toDouble())

    var worst = 0.0
    var spread = 0.0
    for (index in expected.indices) {
      if (alpha[index] == 0.0) continue
      worst = maxOf(worst, abs(frame.red(index % width, index / width, width, height) - expected[index]))
      spread = maxOf(spread, abs(expected[index] - plane[index]))
    }
    withClue("the reference blur moves the frame by $spread, so the read is not of a sharp frame") {
      spread shouldBeGreaterThan SHARP_SPREAD
    }
    withClue("the drawn blur is $worst off the shared plan's at its worst pixel") {
      worst shouldBeLessThanOrEqualTo BLUR_TOLERANCE
    }
  }

  // The picture keeps the alpha it came with and blurs only by itself, and a bar keeps no alpha at all, so the fill
  // painted under it later shows exactly its own color.
  private fun assertContained(inside: (Int, Int) -> Boolean) {
    val red = { x: Int, y: Int ->
      if (!inside(x, y)) {
        0.0
      } else if (x < WIDTH / 2) {
        LIGHT
      } else {
        DARK
      }
    }
    val frame =
      draw(
        SpatialGlEffect(GaussianBlur(BLUR_SIGMA), null),
        null,
        WIDTH,
        HEIGHT,
        alpha = { x, y -> if (inside(x, y)) 1.0 else 0.0 },
        red = red,
      )
    assertBlurred(frame, WIDTH, HEIGHT, red, inside)

    for (y in 0 until HEIGHT) {
      for (x in 0 until WIDTH) {
        withClue("alpha at $x, $y") { frame.alpha(x, y, WIDTH, HEIGHT) shouldBe if (inside(x, y)) 1.0 else 0.0 }
      }
    }
  }

  private fun assertVignetteMatches(
    width: Int,
    height: Int,
  ) {
    val vignette = Vignette()
    val frame = draw(SpatialGlEffect(vignette, null), null, width, height) { _, _ -> GREY }

    for ((u, v) in VIGNETTE_POINTS) {
      val x = (u * width).toInt()
      val y = (v * height).toInt()
      val gain = gainAt(vignette, x, y, width, height)
      val read = frame.red(x, y, width, height)
      withClue("at $x, $y the vignette read $read, and the shared gain $gain puts it at ${GREY * gain}") {
        abs(read - GREY * gain) shouldBeLessThanOrEqualTo TOLERANCE
      }
    }
  }

  private fun gainAt(
    vignette: Vignette,
    x: Int,
    y: Int,
    width: Int,
    height: Int,
  ): Double =
    vignetteGain(
      (x + 0.5) / width,
      (y + 0.5) / height,
      width.toDouble() / height,
      vignette.strength.toDouble(),
      vignette.radius.toDouble(),
      vignette.softness.toDouble(),
    )

  // Rows run from the top of the picture, and a GL texture and its read back both start at the bottom.
  private fun FloatArray.red(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
  ): Double = this[((height - 1 - y) * width + x) * CHANNELS].toDouble()

  private fun FloatArray.alpha(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
  ): Double = this[((height - 1 - y) * width + x) * CHANNELS + CHANNELS - 1].toDouble()

  private fun draw(
    effect: SpatialGlEffect,
    domain: HdrColorMatrixUniforms?,
    width: Int,
    height: Int,
    alpha: (Int, Int) -> Double = { _, _ -> 1.0 },
    red: (Int, Int) -> Double,
  ): FloatArray {
    val input = sourceTexture(width, height, red, alpha)
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

    val program = StepShaderProgram(effect.stepsIn(domain), GLES30.GL_RGBA16F, domain != null)
    program.configure(width, height)
    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer[0])
    program.drawFrame(input, 0L)
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

  private fun sourceTexture(
    width: Int,
    height: Int,
    red: (Int, Int) -> Double,
    alpha: (Int, Int) -> Double,
  ): Int {
    val texels = ByteBuffer.allocateDirect(width * height * CHANNELS * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
    for (row in height - 1 downTo 0) {
      for (x in 0 until width) {
        val value = red(x, row).toFloat()
        floatArrayOf(value, value, value, alpha(x, row).toFloat()).forEach(texels::putFloat)
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

  private companion object {
    // At a sigma of 0.05 the plan boxes this down by 3 and the half size by 2, and both land on whole pixels.
    const val WIDTH = 384
    const val HEIGHT = 216

    // At a sigma of 0.05 the plan boxes this down by 3, which leaves part of a box over.
    const val UNEVEN_WIDTH = 394
    const val BLUR_SIGMA = 0.05f

    // How far a bar reaches in from each side, wide enough that the blur's reach across the edge stays in it.
    const val BAR_SIDE = 40
    const val HLG_LIGHT_SCENE = 0.6
    const val CHANNELS = 4
    const val LIGHT = 0.875
    const val DARK = 0.25
    const val GREY = 0.75
    const val HLG_SCENE = 0.25f

    // Half float holds the input to 2^-11 and each target between the steps rounds once more.
    const val TOLERANCE = 1e-3

    // The blurred steps sit in half float targets, which round each of the three at 2^-11 below one.
    const val BLUR_TOLERANCE = 2e-3

    // A kept HLG grade's signal runs past one, where half float steps by 2^-10.
    const val HALF_FLOAT_STEP_ABOVE_ONE = 1.0 / 1024

    // A GPU's own limit: one that rounds a half float target down, as an Adreno 830 does, can drop a whole step at each
    // of the three targets where one rounding to nearest drops half. Measured 2.74e-3 at the worst column on a Galaxy
    // Z Fold7.
    const val FLOORED_HLG_BLUR_TOLERANCE = 3 * HALF_FLOAT_STEP_ABOVE_ONE

    const val CONSTANT_VERTEX = """#version 300 es
      void main() {
        vec2 corner = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
        gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
      }
    """
    const val CONSTANT_FRAGMENT = """#version 300 es
      precision highp float;
      uniform float uValue;
      out vec4 color;
      void main() {
        color = vec4(uValue);
      }
    """

    // A blur of a 0.625 edge at a sigma of 0.05 of the width moves the columns beside it by far more than this.
    const val SHARP_SPREAD = 0.1

    // The centre, a point part way down the ramp, one near the radius on the diagonal and one near a corner, as
    // fractions of the frame.
    val VIGNETTE_POINTS = listOf(0.5 to 0.5, 0.15 to 0.5, 0.2 to 0.2, 0.03 to 0.05)

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

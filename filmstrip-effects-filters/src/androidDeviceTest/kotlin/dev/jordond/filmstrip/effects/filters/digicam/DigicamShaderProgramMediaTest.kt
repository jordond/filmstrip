package dev.jordond.filmstrip.effects.filters.digicam

import android.opengl.GLES30
import androidx.media3.common.VideoFrameProcessingException
import androidx.test.platform.app.InstrumentationRegistry
import dev.jordond.filmstrip.effects.filters.film.CHANNELS
import dev.jordond.filmstrip.effects.filters.film.FilmGlContext
import dev.jordond.filmstrip.effects.filters.film.drawSteps
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Reads the digicam chain's own output, with no encoder in the way, against [DigicamSignal.pixelAt].
 *
 * The input is a warm ramp from dark at the top to bright at the bottom beside a flat grey, in codes half floats hold
 * exactly, drawn into a 32 bit float target. The ramp runs through every luma, clips near its bottom, and meets the
 * grey darker at one end and brighter at the other. A device with no float color target skips, since 8 bits cannot
 * read the signal this closely.
 */
class DigicamShaderProgramMediaTest {
  private val gl = FilmGlContext()

  @BeforeTest
  fun makeContext() = gl.open()

  @AfterTest
  fun dropContext() = gl.close()

  @Test
  fun theDefaultsLandOnTheSignal() {
    assertChainMatches(Digicam(), WIDTH, HEIGHT)
  }

  @Test
  fun eachControlPartWayLandsOnTheSignal() {
    assertChainMatches(Digicam(sharpness = 0.25f, noise = 0.75f, clip = 0.4f, seed = 77), WIDTH, HEIGHT)
  }

  @Test
  fun ccdLandsOnTheSignal() {
    assertChainMatches(Digicam.Ccd, WIDTH, HEIGHT)
  }

  @Test
  fun aSmallerFrameDrawsTheSameLookAtItsOwnSize() {
    assertChainMatches(Digicam(), WIDTH / 2, HEIGHT / 2)
  }

  @Test
  fun aLargeFrameDrawsCellsSeveralPixelsWideAndAHaloThatReaches() {
    withClue("a cell has to span more than a pixel for this to tell") {
      DigicamSignal.cellPx(LARGE_HEIGHT) shouldBeGreaterThan 1
    }
    withClue("the blur has to reach the next pixel for this to tell") {
      DigicamSignal.blurPlan(LARGE_WIDTH).weights[1] shouldBeGreaterThan REACHING_WEIGHT
    }
    assertChainMatches(Digicam(), LARGE_WIDTH, LARGE_HEIGHT, step = LARGE_READ_STEP)
  }

  @Test
  fun theHaloOvershootsBothSidesOfAnEdge() {
    val digicam = Digicam(sharpness = 0.5f, noise = 0f, clip = 0f)
    val frame = draw(digicam, LARGE_WIDTH, LARGE_HEIGHT)

    val edge = LARGE_WIDTH / 2
    val top = LARGE_HEIGHT / 8
    val bottom = LARGE_HEIGHT * 7 / 8
    val red = { x: Int, y: Int -> frame[((LARGE_HEIGHT - 1 - y) * LARGE_WIDTH + x) * CHANNELS].toDouble() }
    val under = { x: Int, y: Int -> codesAt(x, y, LARGE_WIDTH, LARGE_HEIGHT)[0] }
    withClue("the ramp is darker than the grey near the top, so its last column dips and the grey lifts") {
      (under(edge - 1, top) - red(edge - 1, top)) shouldBeGreaterThan HALO
      (red(edge, top) - under(edge, top)) shouldBeGreaterThan HALO
    }
    withClue("the ramp is brighter than the grey near the bottom, so its last column lifts and the grey dips") {
      (red(edge - 1, bottom) - under(edge - 1, bottom)) shouldBeGreaterThan HALO
      (under(edge, bottom) - red(edge, bottom)) shouldBeGreaterThan HALO
    }
  }

  @Test
  fun aBrightRowClipsAtTheMiddleOfTheRange() {
    val digicam = Digicam(sharpness = 0f, noise = 0f, clip = 0.5f)
    val params = DigicamParams.of(digicam)
    val x = WIDTH / 4
    withClue("the bottom of the ramp has to clip and its middle must not for this to tell") {
      (codesAt(x, HEIGHT - 2, WIDTH, HEIGHT)[0] * params.clipGain) shouldBeGreaterThan 1.0
      (codesAt(x, HEIGHT / 2, WIDTH, HEIGHT)[0] * params.clipGain) shouldBeLessThan 0.9
    }

    val frame = draw(digicam, WIDTH, HEIGHT)

    frame[(WIDTH + x) * CHANNELS].toDouble() shouldBe 1.0
    assertChainMatches(digicam, WIDTH, HEIGHT)
  }

  @Test
  fun eightBitTargetsLandWithinTheirRounding() {
    val digicam = Digicam()
    val params = DigicamParams.of(digicam)
    val rounding = EIGHT_BIT_STEP / 2 * BLUR_STEPS * params.haloGain * params.clipGain

    assertChainMatches(digicam, WIDTH, HEIGHT, GLES30.GL_RGBA8, tolerance(digicam) + rounding)
  }

  @Test
  fun theSameTimeDrawsTheSamePictureAndAnotherTickDrawsOtherNoise() {
    val first = draw(Digicam(), WIDTH, HEIGHT)
    val again = draw(Digicam(), WIDTH, HEIGHT)
    val later = draw(Digicam(), WIDTH, HEIGHT, seconds = SECONDS + 1.0)

    again.contentEquals(first) shouldBe true
    later.contentEquals(first) shouldBe false
  }

  @Test
  fun aBarKeepsItsAlphaAndGivesThePictureNoHalo() {
    val digicam = Digicam(sharpness = 1f, noise = 0f, clip = 0f)
    val dark = floatArrayOf(0f, 0f, 0f, 0f)
    val bright = floatArrayOf(BAR_BRIGHT, BAR_BRIGHT, BAR_BRIGHT, 0f)
    val overDark = draw(digicam, WIDTH, HEIGHT, source = canvas(dark))
    val overBright = draw(digicam, WIDTH, HEIGHT, source = canvas(bright))

    val y = HEIGHT / 2
    val alpha = { column: Int -> overDark[(y * WIDTH + column) * CHANNELS + 3].toDouble() }
    withClue("a bar stays clear") { alpha(CANVAS_LEFT / 2) shouldBe (0.0 plusOrMinus tolerance(digicam)) }
    withClue("the picture stays opaque") { alpha(WIDTH / 2) shouldBe (1.0 plusOrMinus tolerance(digicam)) }
    val inPicture = (y * WIDTH + CANVAS_LEFT) * CHANNELS
    withClue("what a clear bar holds never reaches the picture") {
      overBright[inPicture].toDouble() shouldBe (overDark[inPicture].toDouble() plusOrMinus tolerance(digicam))
    }
  }

  @Test
  fun aSheerPatchIsSharpenedAgainstItsColourTimesItsAlpha() {
    val digicam = Digicam(sharpness = 1f, noise = 0.5f, clip = 0.5f)
    val source = { x: Int, y: Int ->
      val codes = codesAt(x, y, WIDTH, HEIGHT)
      val alpha = if (x >= WIDTH / 2 && y < HEIGHT / 2) SHEER else 1.0
      floatArrayOf(codes[0].toFloat(), codes[1].toFloat(), codes[2].toFloat(), alpha.toFloat())
    }

    val frame = draw(digicam, WIDTH, HEIGHT, source = source)

    worstGap(frame, digicam, WIDTH, HEIGHT, source = source) shouldBeLessThan tolerance(digicam)
  }

  @Test
  fun anHdrFrameProcessorIsRefusedByName() {
    val app = InstrumentationRegistry.getInstrumentation().targetContext
    val refused =
      assertFailsWith<VideoFrameProcessingException> { DigicamEffect(Digicam()).toGlShaderProgram(app, true) }

    refused.message.orEmpty() shouldContain "Digicam requires SDR"
  }

  @Test
  fun anSdrFrameProcessorOnGles3BuildsThePass() {
    val app = InstrumentationRegistry.getInstrumentation().targetContext

    DigicamEffect(Digicam()).toGlShaderProgram(app, false).release()
  }

  @Test
  fun twoEffectsOfOneDigicamCompareEqual() {
    (DigicamEffect(Digicam(0.25f, 0.75f)) == DigicamEffect(Digicam(0.25f, 0.75f))) shouldBe true
    (DigicamEffect(Digicam(0.25f, 0.75f)) == DigicamEffect(Digicam(0.25f, 0.5f))) shouldBe false
    DigicamEffect(Digicam(0.25f, 0.75f)).hashCode() shouldBe DigicamEffect(Digicam(0.25f, 0.75f)).hashCode()
  }

  private fun assertChainMatches(
    digicam: Digicam,
    width: Int,
    height: Int,
    targetFormat: Int = GLES30.GL_RGBA16F,
    tolerance: Double = tolerance(digicam),
    step: Int = 1,
  ) {
    val frame = draw(digicam, width, height, targetFormat = targetFormat)

    withClue("a ${width}x$height frame of $digicam") {
      worstGap(frame, digicam, width, height, step) shouldBeLessThan tolerance
    }
  }

  // The largest gap between the frame and the signal over every pixel a step apart, alpha included.
  private fun worstGap(
    frame: FloatArray,
    digicam: Digicam,
    width: Int,
    height: Int,
    step: Int = 1,
    source: (Int, Int) -> FloatArray = { x, y -> opaque(x, y, width, height) },
  ): Double {
    val params = DigicamParams.of(digicam)
    val tick = DigicamSignal.tickAt(SECONDS)
    val blur =
      DigicamSignal.blur(width, height) { x, y ->
        val read = source(x, y)
        DoubleArray(RGB) { read[it].toDouble() * read[3] }
      }
    var worst = 0.0
    for (y in 0 until height step step) {
      for (x in 0 until width step step) {
        val read = source(x, y)
        val rgb = DoubleArray(RGB) { read[it].toDouble() }
        val wanted = DigicamSignal.pixelAt(x, y, height, digicam.seed, tick, params, rgb, blur.at(x, y))
        // The frame reads bottom up, the way GL hands it back.
        val at = ((height - 1 - y) * width + x) * CHANNELS
        for (channel in 0 until RGB) worst = max(worst, abs(frame[at + channel] - wanted[channel]))
        worst = max(worst, abs(frame[at + RGB] - read[RGB]).toDouble())
      }
    }
    return worst
  }

  // Each blur step rounds to half a half float step, and the halo and the clip gain scale that up.
  private fun tolerance(digicam: Digicam): Double {
    val params = DigicamParams.of(digicam)
    return HALF_FLOAT_STEP / 2 * BLUR_STEPS * params.haloGain * params.clipGain + FLOAT_SLACK
  }

  // A warm ramp down the left half, which a frame of any height runs through in the same steps, against flat grey.
  private fun codesAt(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
  ): DoubleArray {
    if (x >= width / 2) return GREY.copyOf()
    val lift = (y * RAMP_STEPS / height) / RAMP_DIVISOR
    return DoubleArray(RGB) { RAMP_TOP[it] + lift }
  }

  private fun opaque(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
  ): FloatArray {
    val codes = codesAt(x, y, width, height)
    return floatArrayOf(codes[0].toFloat(), codes[1].toFloat(), codes[2].toFloat(), 1f)
  }

  private fun canvas(bar: FloatArray): (Int, Int) -> FloatArray =
    { x, _ ->
      if (x in CANVAS_LEFT until CANVAS_RIGHT) {
        floatArrayOf(GREY[0].toFloat(), GREY[1].toFloat(), GREY[2].toFloat(), 1f)
      } else {
        bar
      }
    }

  private fun draw(
    digicam: Digicam,
    width: Int,
    height: Int,
    seconds: Double = SECONDS,
    targetFormat: Int = GLES30.GL_RGBA16F,
    source: (Int, Int) -> FloatArray = { x, y -> opaque(x, y, width, height) },
  ): FloatArray = drawSteps(digicamShaderProgram(digicam, targetFormat), width, height, seconds, source)

  private companion object {
    const val WIDTH = 512
    const val HEIGHT = 480

    // Large enough for a cell several pixels wide and a halo a pixel across, and small enough for the signal's blur
    // of the whole frame to fit a test's heap.
    const val LARGE_WIDTH = 1280
    const val LARGE_HEIGHT = 720
    const val LARGE_READ_STEP = 7
    const val CANVAS_LEFT = 128
    const val CANVAS_RIGHT = 384
    const val RGB = 3
    const val BAR_BRIGHT = 0.875f
    const val SHEER = 0.5
    const val SECONDS = 25.0

    // A half float's step from a half to one, an eight bit target's step, and the steps a code is rounded on before
    // the last one reads it.
    const val HALF_FLOAT_STEP = 1.0 / 2048.0
    const val EIGHT_BIT_STEP = 1.0 / 255.0
    const val BLUR_STEPS = 3

    // What 32 bit arithmetic leaves between the shader and the signal's doubles.
    const val FLOAT_SLACK = 1e-4

    // A halo that stands clear of rounding beside an edge, and a blur weight that moves a code.
    const val HALO = 0.01
    const val REACHING_WEIGHT = 0.1

    // The ramp climbs in 480 steps of 1/512 from its top colour and ends just under white.
    const val RAMP_STEPS = 480
    const val RAMP_DIVISOR = 512.0
    val RAMP_TOP = doubleArrayOf(0.0625, 0.046875, 0.03125)
    val GREY = doubleArrayOf(0.46875, 0.46875, 0.46875)
  }
}

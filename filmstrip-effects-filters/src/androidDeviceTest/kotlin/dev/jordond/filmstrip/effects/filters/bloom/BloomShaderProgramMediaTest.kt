package dev.jordond.filmstrip.effects.filters.bloom

import android.opengl.GLES30
import androidx.media3.common.VideoFrameProcessingException
import androidx.test.platform.app.InstrumentationRegistry
import dev.jordond.filmstrip.effects.filters.film.CHANNELS
import dev.jordond.filmstrip.effects.filters.film.FilmGlContext
import dev.jordond.filmstrip.effects.filters.film.drawSteps
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Reads the bloom chain's own output, with no encoder in the way, against [BloomSignal].
 *
 * The input is a bright patch over a midtone in codes half floats hold exactly, drawn into a 32 bit float target. The
 * patch's luma sits part way up the default knee, and the midtone's part way up [Bloom.Dreamy]'s, so each response is
 * read off its ramp. A device with no float color target skips, since 8 bits cannot read the signal this closely.
 */
class BloomShaderProgramMediaTest {
  private val gl = FilmGlContext()

  @BeforeTest
  fun makeContext() = gl.open()

  @AfterTest
  fun dropContext() = gl.close()

  @Test
  fun theDefaultsLandOnTheSignal() {
    assertChainMatches(Bloom(), WIDTH, HEIGHT)
  }

  @Test
  fun eachControlPartWayLandsOnTheSignal() {
    assertChainMatches(Bloom(intensity = 0.25f, threshold = 0.5f, spread = 0.75f), WIDTH, HEIGHT)
  }

  @Test
  fun dreamyLandsOnTheSignal() {
    assertChainMatches(Bloom.Dreamy, WIDTH, HEIGHT)
  }

  @Test
  fun aTightBloomAtFullIntensityLandsOnTheSignal() {
    assertChainMatches(Bloom(intensity = 1f, threshold = 0.6f, spread = 0f), WIDTH, HEIGHT)
  }

  @Test
  fun aSmallerFrameDrawsTheSameLookAtItsOwnSize() {
    assertChainMatches(Bloom(), WIDTH / 2, HEIGHT / 2)
  }

  @Test
  fun aLargeFrameBlursAtThePlansSmallerSize() {
    withClue("the plan has to scale past two for this to tell") {
      BloomSignal.blurPlan(Bloom().spread.toDouble(), FULL_HD_WIDTH).factor shouldBeGreaterThan 2
    }
    assertChainMatches(Bloom(), FULL_HD_WIDTH, FULL_HD_HEIGHT)
  }

  @Test
  fun aFrameItsFactorDoesNotDivideBlursAtThePlansSize() {
    withClue("the factor has to leave a remainder for this to tell") {
      (UNEVEN_WIDTH % BloomSignal.blurPlan(Bloom().spread.toDouble(), UNEVEN_WIDTH).factor != 0) shouldBe true
    }
    assertChainMatches(Bloom(), UNEVEN_WIDTH, UNEVEN_HEIGHT)
  }

  @Test
  fun eightBitTargetsLandWithinTheirRounding() {
    assertChainMatches(Bloom(), WIDTH, HEIGHT, GLES30.GL_RGBA8, EIGHT_BIT_TOLERANCE)
  }

  @Test
  fun anotherTimeDrawsTheSamePicture() {
    val first = draw(Bloom(), WIDTH, HEIGHT)
    val later = draw(Bloom(), WIDTH, HEIGHT, seconds = SECONDS + 1.0)

    later.contentEquals(first) shouldBe true
  }

  @Test
  fun aBarKeepsItsAlphaAndGivesTheGlowNoLight() {
    val bloom = Bloom(intensity = 1f, threshold = 0.5f, spread = 1f)
    val dark = floatArrayOf(0f, 0f, 0f, 0f)
    val bright = floatArrayOf(BAR_BRIGHT, BAR_BRIGHT, BAR_BRIGHT, 0f)
    val overDark = draw(bloom, WIDTH, HEIGHT, source = canvas(dark))
    val overBright = draw(bloom, WIDTH, HEIGHT, source = canvas(bright))

    val y = HEIGHT / 2
    val alpha = { column: Int -> overDark[(y * WIDTH + column) * CHANNELS + 3].toDouble() }
    withClue("a bar stays clear") { alpha(CANVAS_LEFT / 2) shouldBe (0.0 plusOrMinus TOLERANCE) }
    withClue("the picture stays opaque") { alpha(WIDTH / 2) shouldBe (1.0 plusOrMinus TOLERANCE) }
    val inPicture = (y * WIDTH + CANVAS_LEFT + 2) * CHANNELS
    withClue("what a clear bar holds never reaches the picture") {
      overBright[inPicture].toDouble() shouldBe (overDark[inPicture].toDouble() plusOrMinus TOLERANCE)
    }
  }

  @Test
  fun aSheerPatchGivesLightByItsAlpha() {
    val bloom = Bloom(intensity = 1f, threshold = 0.3f, spread = 0.5f)
    val params = BloomParams.of(bloom)
    val source = { x: Int, y: Int ->
      val codes = codesAt(x, y, WIDTH, HEIGHT)
      val alpha = if (inPatch(x, y, WIDTH, HEIGHT)) SHEER else 1.0
      floatArrayOf(codes[0].toFloat(), codes[1].toFloat(), codes[2].toFloat(), alpha.toFloat())
    }
    val glow =
      BloomSignal.glow(WIDTH, HEIGHT, params) { x, y ->
        val read = source(x, y)
        DoubleArray(RGB) { read[it].toDouble() * read[3] }
      }
    val opaque = BloomSignal.glow(WIDTH, HEIGHT, params) { x, y -> codesAt(x, y, WIDTH, HEIGHT) }
    val inside = WIDTH / 8 to HEIGHT / 8
    withClue("the alpha has to move the light for this to tell") {
      (opaque.at(inside.first, inside.second)[0] - glow.at(inside.first, inside.second)[0]) shouldBeGreaterThan 0.05
    }

    val frame = draw(bloom, WIDTH, HEIGHT, source = source)

    assertFrameMatches(frame, WIDTH, HEIGHT, TOLERANCE) { x, y, codes ->
      BloomSignal.bloomed(codes[0], codes[1], codes[2], glow.at(x, y), params) + source(x, y)[3].toDouble()
    }
  }

  @Test
  fun anHdrFrameProcessorIsRefusedByName() {
    val app = InstrumentationRegistry.getInstrumentation().targetContext
    val refused = assertFailsWith<VideoFrameProcessingException> { BloomEffect(Bloom()).toGlShaderProgram(app, true) }

    refused.message.orEmpty() shouldContain "Bloom requires SDR"
  }

  @Test
  fun anSdrFrameProcessorOnGles3BuildsThePass() {
    val app = InstrumentationRegistry.getInstrumentation().targetContext

    BloomEffect(Bloom()).toGlShaderProgram(app, false).release()
  }

  @Test
  fun twoEffectsOfOneBloomCompareEqual() {
    (BloomEffect(Bloom(0.25f, 0.75f)) == BloomEffect(Bloom(0.25f, 0.75f))) shouldBe true
    (BloomEffect(Bloom(0.25f, 0.75f)) == BloomEffect(Bloom(0.25f, 0.5f))) shouldBe false
    BloomEffect(Bloom(0.25f, 0.75f)).hashCode() shouldBe BloomEffect(Bloom(0.25f, 0.75f)).hashCode()
  }

  private fun assertChainMatches(
    bloom: Bloom,
    width: Int,
    height: Int,
    targetFormat: Int = GLES30.GL_RGBA16F,
    tolerance: Double = TOLERANCE,
  ) {
    val params = BloomParams.of(bloom)
    val glow = BloomSignal.glow(width, height, params) { x, y -> codesAt(x, y, width, height) }
    val frame = draw(bloom, width, height, targetFormat = targetFormat)

    withClue("the glow has to reach past the patch for this to tell") {
      glow.at(width / 2 + 2, height / 3).max() shouldBeGreaterThan 0.01
    }
    assertFrameMatches(frame, width, height, tolerance) { x, y, codes ->
      BloomSignal.bloomed(codes[0], codes[1], codes[2], glow.at(x, y), params) + 1.0
    }
  }

  private fun assertFrameMatches(
    frame: FloatArray,
    width: Int,
    height: Int,
    tolerance: Double,
    expected: (x: Int, y: Int, codes: DoubleArray) -> DoubleArray,
  ) {
    val columns = listOf(0, width / 8, width / 2 - 1, width / 2 + 2, width / 2 + width / 16, width - 2)
    listOf(0, height / 8, height / 3, height / 2 + height / 16, height - 2).forEach { y ->
      columns.forEach { x ->
        val wanted = expected(x, y, codesAt(x, y, width, height))
        // The frame reads bottom up, the way GL hands it back.
        val at = ((height - 1 - y) * width + x) * CHANNELS
        wanted.forEachIndexed { channel, value ->
          withClue("row $y column $x channel $channel of a ${width}x$height frame") {
            frame[at + channel].toDouble() shouldBe (value plusOrMinus tolerance)
          }
        }
      }
    }
  }

  private fun inPatch(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
  ) = x < width / 2 && y < height / 2

  // A bright patch in the upper left over a midtone.
  private fun codesAt(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
  ): DoubleArray = if (inPatch(x, y, width, height)) PATCH.copyOf() else GROUND.copyOf()

  private fun canvas(bar: FloatArray): (Int, Int) -> FloatArray =
    { x, _ ->
      if (x in CANVAS_LEFT until CANVAS_RIGHT) {
        floatArrayOf(PATCH[0].toFloat(), PATCH[1].toFloat(), PATCH[2].toFloat(), 1f)
      } else {
        bar
      }
    }

  private fun draw(
    bloom: Bloom,
    width: Int,
    height: Int,
    seconds: Double = SECONDS,
    targetFormat: Int = GLES30.GL_RGBA16F,
    source: (Int, Int) -> FloatArray = { x, y ->
      val codes = codesAt(x, y, width, height)
      floatArrayOf(codes[0].toFloat(), codes[1].toFloat(), codes[2].toFloat(), 1f)
    },
  ): FloatArray = drawSteps(bloomShaderProgram(bloom, targetFormat), width, height, seconds, source)

  private companion object {
    const val WIDTH = 512
    const val HEIGHT = 480
    const val FULL_HD_WIDTH = 1920
    const val FULL_HD_HEIGHT = 1080
    const val UNEVEN_WIDTH = 1366
    const val UNEVEN_HEIGHT = 768
    const val CANVAS_LEFT = 128
    const val CANVAS_RIGHT = 384
    const val RGB = 3
    const val BAR_BRIGHT = 0.875f
    const val SHEER = 0.5
    const val SECONDS = 25.0

    // The three glow steps round to half float, 2^-11 from a half to one, under a gain of up to BLOOM_GAIN.
    const val TOLERANCE = 5e-4

    // Half a code on each of the three glow steps under the default gain.
    const val EIGHT_BIT_TOLERANCE = 1.5 / 255.0
    val PATCH = doubleArrayOf(0.875, 0.8125, 0.75)
    val GROUND = doubleArrayOf(0.375, 0.5, 0.25)
  }
}

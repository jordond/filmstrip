package dev.jordond.filmstrip.effects.filters.bloom

import dev.jordond.filmstrip.color.BlurPlan
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.FrameInfo
import dev.jordond.filmstrip.effects.filters.film.FILM_CHANNELS
import dev.jordond.filmstrip.effects.filters.film.filmAttributes
import dev.jordond.filmstrip.effects.filters.film.filmImageOf
import dev.jordond.filmstrip.effects.filters.film.filmRendered
import dev.jordond.filmstrip.effects.spatial.blurredWithin
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Reads the kernels' own output, with no encoder in the way, against [BloomSignal].
 *
 * A frame goes in and comes out in the codes of the BT.709 buffer, the way the chain hands it over and takes it back.
 * Rows of a render come back from the top. The picture is a bright patch half way up the default knee over a midtone.
 */
@OptIn(ExperimentalForeignApi::class)
class BloomCoreImageHardwareTest {
  @Test
  fun `core image's blur lands within two codes of the plan's kernel at every spread`() {
    listOf(0.0, 0.5, 1.0).forEach { spread ->
      val plan = BloomSignal.blurPlan(spread, BLUR_FRAME_WIDTH)
      val width = plan.width
      val height = plan.heightOf(BLUR_FRAME_HEIGHT)
      val left = width / 2 - PATCH_HALF
      val top = height / 2 - PATCH_HALF
      val inPatch = { x: Int, y: Int -> x in left until left + 2 * PATCH_HALF && y in top until top + 2 * PATCH_HALF }
      val patch = filmImageOf(width, height) { x, y -> if (inPatch(x, y)) WHITE else CLEAR_BLACK }
      val small = CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble())
      val frame = filmRendered(patch.blurredWithin(plan.sigmaPx, small), width, height)

      var worst = 0.0
      for (y in 0 until height step READ_STEP) {
        for (x in 0 until width step READ_STEP) {
          val expected = planBlur(plan, x, y, left, top)
          worst = maxOf(worst, abs(frame[(y * width + x) * FILM_CHANNELS].toDouble() - expected))
        }
      }
      withClue("a spread of $spread at a sigma of ${plan.sigmaPx} landed ${worst * FULL} codes off") {
        worst * FULL shouldBeLessThan BLUR_CODES
      }
    }
  }

  @Test
  fun `a bloom at a mid intensity lands on the signal`() {
    assertBloomMatches(Bloom(), WIDTH, HEIGHT)
  }

  @Test
  fun `a low threshold takes the ground into the glow and a high one takes less of the patch`() {
    assertBloomMatches(Bloom(threshold = 0.35f), WIDTH, HEIGHT)
    assertBloomMatches(Bloom(threshold = 0.75f), WIDTH, HEIGHT)
  }

  @Test
  fun `a tight spread and a wide one land on the signal`() {
    assertBloomMatches(Bloom(spread = 0.25f), WIDTH, HEIGHT)
    assertBloomMatches(Bloom(intensity = 0.75f, spread = 1f), WIDTH, HEIGHT)
  }

  @Test
  fun `a read tells another intensity threshold or spread from the bloom that was drawn`() {
    val frame = drawn(Bloom(), WIDTH, HEIGHT)

    listOf(Bloom(intensity = 0.3f), Bloom(threshold = 0.78f), Bloom(spread = 0.1f)).forEach { wrong ->
      withClue("$wrong read against a default bloom") {
        (worstMiss(frame, wrong, WIDTH, HEIGHT) > toleranceOf(wrong)) shouldBe true
      }
    }
  }

  @Test
  fun `a smaller frame draws the same look at its own size`() {
    assertBloomMatches(Bloom(), WIDTH / 2, HEIGHT / 2)
  }

  @Test
  fun `a 1080p frame blurs at the plan's smaller size`() {
    (BloomSignal.blurPlan(0.5, FULL_HD_WIDTH).factor > 2) shouldBe true
    assertBloomMatches(Bloom(), FULL_HD_WIDTH, FULL_HD_HEIGHT)
  }

  @Test
  fun `a frame its factor does not divide blurs at the plan's size`() {
    (UNEVEN_WIDTH % BloomSignal.blurPlan(0.5, UNEVEN_WIDTH).factor != 0) shouldBe true
    assertBloomMatches(Bloom(), UNEVEN_WIDTH, UNEVEN_HEIGHT)
  }

  @Test
  fun `a bar keeps its alpha and stays clear beside a picture that glows`() {
    val bloom = Bloom(intensity = 1f, threshold = 0.35f, spread = 1f)
    val params = BloomParams.of(bloom)
    val picture = floatArrayOf(PATCH[0].toFloat(), PATCH[1].toFloat(), PATCH[2].toFloat(), 1f)
    val inPicture = { x: Int -> x in CANVAS_LEFT until CANVAS_RIGHT }
    val canvas = filmImageOf(WIDTH, HEIGHT) { x, _ -> if (inPicture(x)) picture else CLEAR_BLACK }
    val frame =
      filmRendered(
        bloomStep(bloom).apply(canvas, FrameInfo(filmAttributes(WIDTH, HEIGHT), SECONDS.seconds)),
        WIDTH,
        HEIGHT,
      )
    val glow =
      BloomSignal.glow(WIDTH, HEIGHT, params) { x, _ -> if (inPicture(x)) PATCH.copyOf() else DoubleArray(3) }

    val y = HEIGHT / 2
    val at = { x: Int, channel: Int -> frame[(y * WIDTH + x) * FILM_CHANNELS + channel].toDouble() }
    withClue("a bar stays clear") {
      at(CANVAS_LEFT / 2, ALPHA) shouldBe (0.0 plusOrMinus TOLERANCE)
      at(CANVAS_LEFT / 2, 0) shouldBe (0.0 plusOrMinus TOLERANCE)
    }
    val inside = CANVAS_LEFT + INSIDE
    val wanted = BloomSignal.bloomed(PATCH[0], PATCH[1], PATCH[2], glow.at(inside, y), params)
    withClue("the picture stays opaque and glows on the signal") {
      at(inside, ALPHA) shouldBe (1.0 plusOrMinus TOLERANCE)
      at(inside, 0) shouldBe (wanted[0] plusOrMinus toleranceOf(bloom))
    }
  }

  @Test
  fun `no kernel refuses a bloom by name`() {
    bloomKernelLimit().shouldBeNull()
    bloomKernelLimit(kernelsExist = false) shouldBe BLOOM_KERNEL_LIMIT
    bloomKernelLimit(kernelsExist = true, compiles = { false }) shouldBe BLOOM_KERNEL_LIMIT
    bloomResolution(Bloom(), BLOOM_KERNEL_LIMIT).shouldBeInstanceOf<EffectResolution.Unsupported>()
  }

  private fun assertBloomMatches(
    bloom: Bloom,
    width: Int,
    height: Int,
  ) {
    val worst = worstMiss(drawn(bloom, width, height), bloom, width, height)
    withClue("$bloom on a ${width}x$height frame landed ${worst * FULL} codes off") {
      worst shouldBeLessThan toleranceOf(bloom)
    }
    withClue("the glow has to lift the ground for this to tell") {
      val params = BloomParams.of(bloom)
      val glow = BloomSignal.glow(width, height, params) { x, y -> codesAt(x, y, width, height) }
      val x = width / 2 + 2
      val y = height / 3
      BloomSignal.bloomed(GROUND[0], GROUND[1], GROUND[2], glow.at(x, y), params)[1] - GROUND[1] shouldBeGreaterThan
        LIFT
    }
  }

  private fun worstMiss(
    frame: FloatArray,
    bloom: Bloom,
    width: Int,
    height: Int,
  ): Double {
    val params = BloomParams.of(bloom)
    val glow = BloomSignal.glow(width, height, params) { x, y -> codesAt(x, y, width, height) }
    val columns = listOf(width / 8, width / 2 - 1, width / 2 + 2, width / 2 + width / 16, width - 2)
    var worst = 0.0
    listOf(height / 8, height / 3, height / 2 + height / 16, height - 2).forEach { y ->
      columns.forEach { x ->
        val codes = codesAt(x, y, width, height)
        val wanted = BloomSignal.bloomed(codes[0], codes[1], codes[2], glow.at(x, y), params)
        val at = (y * width + x) * FILM_CHANNELS
        wanted.forEachIndexed { channel, value -> worst = maxOf(worst, abs(frame[at + channel] - value)) }
      }
    }
    return worst
  }

  // The blur's limit reaches the frame through the gain, on top of the float reads' own rounding.
  private fun toleranceOf(bloom: Bloom): Double = BLUR_CODES * BloomParams.of(bloom).gain / FULL + TOLERANCE

  private fun planBlur(
    plan: BlurPlan,
    x: Int,
    y: Int,
    left: Int,
    top: Int,
  ): Double {
    val weight = { d: Int -> if (abs(d) > plan.taps) 0.0 else plan.weights[abs(d)] }
    var sum = 0.0
    for (py in top until top + 2 * PATCH_HALF) {
      for (px in left until left + 2 * PATCH_HALF) sum += weight(x - px) * weight(y - py)
    }
    return sum
  }

  // A bright patch in the upper left over a midtone.
  private fun codesAt(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
  ): DoubleArray = if (x < width / 2 && y < height / 2) PATCH.copyOf() else GROUND.copyOf()

  private fun drawn(
    bloom: Bloom,
    width: Int,
    height: Int,
  ): FloatArray {
    val image =
      filmImageOf(width, height) { x, y ->
        val codes = codesAt(x, y, width, height)
        floatArrayOf(codes[0].toFloat(), codes[1].toFloat(), codes[2].toFloat(), 1f)
      }
    return filmRendered(
      bloomStep(bloom).apply(image, FrameInfo(filmAttributes(width, height), SECONDS.seconds)),
      width,
      height,
    )
  }

  private companion object {
    const val WIDTH = 512
    const val HEIGHT = 480
    const val FULL_HD_WIDTH = 1920
    const val FULL_HD_HEIGHT = 1080
    const val UNEVEN_WIDTH = 1366
    const val UNEVEN_HEIGHT = 768
    const val BLUR_FRAME_WIDTH = 1280
    const val BLUR_FRAME_HEIGHT = 720
    const val PATCH_HALF = 16
    const val READ_STEP = 3
    const val ALPHA = 3
    const val FULL = 255.0
    const val SECONDS = 25.0
    const val TOLERANCE = 1e-4
    const val LIFT = 0.01
    const val CANVAS_LEFT = 128
    const val CANVAS_RIGHT = 384
    const val INSIDE = 4

    // Core Image's Gaussian is Core Image's own limit, held here at the sigmas a bloom runs it at. On a patch it lands
    // 0.91 codes off the plan's kernel at a sigma of 6.4, 0.92 at 8 and 1.49 at 7.28, and a frame of the edge picture
    // at most 0.12.
    const val BLUR_CODES = 2.0
    val WHITE = floatArrayOf(1f, 1f, 1f, 1f)
    val CLEAR_BLACK = floatArrayOf(0f, 0f, 0f, 0f)
    val PATCH = doubleArrayOf(0.875, 0.8125, 0.75)
    val GROUND = doubleArrayOf(0.375, 0.5, 0.25)
  }
}

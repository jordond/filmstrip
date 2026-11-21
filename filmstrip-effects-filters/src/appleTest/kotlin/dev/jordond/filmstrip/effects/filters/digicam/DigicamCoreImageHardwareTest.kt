package dev.jordond.filmstrip.effects.filters.digicam

import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.FrameInfo
import dev.jordond.filmstrip.effects.filters.film.FILM_CHANNELS
import dev.jordond.filmstrip.effects.filters.film.filmAttributes
import dev.jordond.filmstrip.effects.filters.film.filmImageOf
import dev.jordond.filmstrip.effects.filters.film.filmRendered
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Reads the kernel's own output, with no encoder in the way, against [DigicamSignal].
 *
 * A frame goes in and comes out in the codes of the BT.709 buffer, the way the chain hands it over and takes it back.
 * Rows of a render come back from the top. The picture is a dark side and a light side that meet at an edge which
 * steps sideways every few rows, so a row reads differently from the one above it.
 */
@OptIn(ExperimentalForeignApi::class)
class DigicamCoreImageHardwareTest {
  @Test
  fun `a halo and a clip at the middle of their ranges land on the signal`() {
    assertDigicamMatches(QUIET, HD_WIDTH, HEIGHT)
  }

  @Test
  fun `the noise alone lands on the signal`() {
    assertDigicamMatches(Digicam(sharpness = 0f, noise = 0.5f, clip = 0f), WIDTH, HEIGHT)
  }

  @Test
  fun `the default look and a look at the top of every range land on the signal`() {
    assertDigicamMatches(Digicam(), HD_WIDTH, HEIGHT)
    assertDigicamMatches(Digicam(sharpness = 1f, noise = 1f, clip = 1f), HD_WIDTH, HEIGHT)
  }

  @Test
  fun `a read tells another sharpness clip noise or seed from the look that was drawn`() {
    val drawn = Digicam()
    val frame = drawn(drawn, HD_WIDTH, HEIGHT)

    listOf(
      Digicam(sharpness = drawn.sharpness + 0.25f),
      Digicam(clip = drawn.clip + 0.25f),
      Digicam(noise = drawn.noise + 0.25f),
      Digicam(seed = drawn.seed + 1),
    ).forEach { wrong ->
      withClue("$wrong read against a default digicam") {
        worstMiss(frame, wrong, HD_WIDTH, HEIGHT) shouldBeGreaterThan toleranceOf(wrong)
      }
    }
  }

  @Test
  fun `a frame at another tick draws other noise`() {
    val digicam = Digicam(sharpness = 0f, noise = 1f, clip = 0f)
    val frame = drawn(digicam, WIDTH, HEIGHT, LATER_SECONDS.seconds)

    DigicamSignal.tickAt(LATER_SECONDS) shouldBeGreaterThan DigicamSignal.tickAt(SECONDS)
    worstMiss(frame, digicam, WIDTH, HEIGHT, DigicamSignal.tickAt(LATER_SECONDS)) shouldBeLessThan toleranceOf(digicam)
    worstMiss(frame, digicam, WIDTH, HEIGHT) shouldBeGreaterThan toleranceOf(digicam)
  }

  @Test
  fun `a smaller frame draws the same look at its own size`() {
    DigicamSignal.cellPx(HEIGHT / 2) shouldBe 1
    assertDigicamMatches(Digicam(), HD_WIDTH / 2, HEIGHT / 2)
  }

  @Test
  fun `a 1080p frame draws cells three rows tall`() {
    DigicamSignal.cellPx(FULL_HD_HEIGHT) shouldBe NOISE_ROWS
    assertDigicamMatches(Digicam(), FULL_HD_WIDTH, FULL_HD_HEIGHT)
  }

  @Test
  fun `a frame wider than the blur's ceiling blurs at the plan's smaller size`() {
    DigicamSignal.blurPlan(WIDE_WIDTH).factor shouldBe 2
    assertDigicamMatches(QUIET, WIDE_WIDTH, WIDE_HEIGHT)
  }

  @Test
  fun `a tile of the frame draws the pixels the whole frame does`() {
    val digicam = Digicam()
    val image = digicamStep(digicam).apply(imageOf(HD_WIDTH, HEIGHT), frameAt(HD_WIDTH, HEIGHT, SECONDS.seconds))
    val whole = filmRendered(image, HD_WIDTH, HEIGHT)
    val tileX = HD_WIDTH / 2 - TILE / 2
    val tile =
      filmRendered(image, HD_WIDTH, HEIGHT, originX = tileX, originY = TILE_Y, boundsWidth = TILE, boundsHeight = TILE)

    val top = HEIGHT - TILE_Y - TILE
    var worst = 0.0
    for (y in 0 until TILE) {
      for (x in 0 until TILE) {
        repeat(FILM_CHANNELS) { channel ->
          val wanted = whole[((top + y) * HD_WIDTH + tileX + x) * FILM_CHANNELS + channel]
          worst = maxOf(worst, abs(tile[(y * TILE + x) * FILM_CHANNELS + channel] - wanted).toDouble())
        }
      }
    }
    withClue("the tile landed ${worst * FULL} codes off the whole frame") { worst shouldBeLessThan TOLERANCE }
  }

  @Test
  fun `a bar keeps its alpha and stays clear beside a picture that is sharpened`() {
    val digicam = Digicam(sharpness = 1f, noise = 1f, clip = 1f)
    val params = DigicamParams.of(digicam)
    val inPicture = { x: Int -> x in CANVAS_LEFT until CANVAS_RIGHT }
    val picture = floatArrayOf(LIGHT[0].toFloat(), LIGHT[1].toFloat(), LIGHT[2].toFloat(), 1f)
    val canvas = filmImageOf(WIDTH, HEIGHT) { x, _ -> if (inPicture(x)) picture else CLEAR_BLACK }
    val frame =
      filmRendered(digicamStep(digicam).apply(canvas, frameAt(WIDTH, HEIGHT, SECONDS.seconds)), WIDTH, HEIGHT)
    val blur = DigicamSignal.blur(WIDTH, HEIGHT) { x, _ -> if (inPicture(x)) LIGHT.copyOf() else DoubleArray(3) }

    val y = HEIGHT / 2
    val at = { x: Int, channel: Int -> frame[(y * WIDTH + x) * FILM_CHANNELS + channel].toDouble() }
    withClue("a bar stays clear") {
      repeat(FILM_CHANNELS) { channel -> at(CANVAS_LEFT - 1, channel) shouldBe (0.0 plusOrMinus TOLERANCE) }
    }
    val wanted =
      DigicamSignal.pixelAt(CANVAS_LEFT, y, HEIGHT, digicam.seed, TICK, params, LIGHT, blur.at(CANVAS_LEFT, y))
    withClue("the picture's first column stays opaque and is sharpened against the bar") {
      at(CANVAS_LEFT, ALPHA) shouldBe (1.0 plusOrMinus TOLERANCE)
      wanted[1] shouldBeGreaterThan DigicamSignal.clipped(LIGHT[1], params) + LIFT
      at(CANVAS_LEFT, 1) shouldBe (wanted[1] plusOrMinus toleranceOf(digicam))
    }
  }

  @Test
  fun `no kernel refuses a digicam by name`() {
    digicamKernelLimit().shouldBeNull()
    digicamKernelLimit(kernelsExist = false) shouldBe DIGICAM_KERNEL_LIMIT
    digicamKernelLimit(kernelsExist = true, compiles = { false }) shouldBe DIGICAM_KERNEL_LIMIT
    digicamResolution(Digicam(), DIGICAM_KERNEL_LIMIT).shouldBeInstanceOf<EffectResolution.Unsupported>()
  }

  private fun assertDigicamMatches(
    digicam: Digicam,
    width: Int,
    height: Int,
  ) {
    val worst = worstMiss(drawn(digicam, width, height), digicam, width, height)
    withClue("$digicam on a ${width}x$height frame landed ${worst * FULL} codes off") {
      worst shouldBeLessThan toleranceOf(digicam)
    }
  }

  private fun worstMiss(
    frame: FloatArray,
    digicam: Digicam,
    width: Int,
    height: Int,
    tick: Int = TICK,
  ): Double {
    val params = DigicamParams.of(digicam)
    val blur = DigicamSignal.blur(width, height) { x, y -> codesAt(x, y, width) }
    val columns = (-EDGE_REACH..EDGE_REACH + EDGE_STEP).map { width / 2 + it } + listOf(1, width / 5, width - 2)
    var worst = 0.0
    (1 until height - 1 step ROW_STEP).forEach { y ->
      columns.forEach { x ->
        val wanted =
          DigicamSignal.pixelAt(x, y, height, digicam.seed, tick, params, codesAt(x, y, width), blur.at(x, y))
        val at = (y * width + x) * FILM_CHANNELS
        wanted.forEachIndexed { channel, value -> worst = maxOf(worst, abs(frame[at + channel] - value)) }
      }
    }
    return worst
  }

  // A float read's rounding reaches the frame once through the picture and once more through the blur, each scaled
  // by the halo and the clip gain.
  private fun toleranceOf(digicam: Digicam): Double {
    val params = DigicamParams.of(digicam)
    return TOLERANCE * (1.0 + 2.0 * params.haloGain) * params.clipGain
  }

  private fun codesAt(
    x: Int,
    y: Int,
    width: Int,
  ): DoubleArray = if (x < width / 2 + (if (y / EDGE_ROWS % 2 == 0) 0 else EDGE_STEP)) DARK.copyOf() else LIGHT.copyOf()

  private fun imageOf(
    width: Int,
    height: Int,
  ) = filmImageOf(width, height) { x, y ->
    val codes = codesAt(x, y, width)
    floatArrayOf(codes[0].toFloat(), codes[1].toFloat(), codes[2].toFloat(), 1f)
  }

  private fun frameAt(
    width: Int,
    height: Int,
    time: Duration,
  ) = FrameInfo(filmAttributes(width, height), time)

  private fun drawn(
    digicam: Digicam,
    width: Int,
    height: Int,
    time: Duration = SECONDS.seconds,
  ): FloatArray =
    filmRendered(digicamStep(digicam).apply(imageOf(width, height), frameAt(width, height, time)), width, height)

  private companion object {
    const val WIDTH = 512
    const val HD_WIDTH = 1280
    const val HEIGHT = 720
    const val FULL_HD_WIDTH = 1920
    const val FULL_HD_HEIGHT = 1080
    const val WIDE_WIDTH = 10560
    const val WIDE_HEIGHT = 32
    const val EDGE_REACH = 6
    const val EDGE_STEP = 3
    const val EDGE_ROWS = 5
    const val ROW_STEP = 7
    const val TILE = 64
    const val TILE_Y = 100
    const val ALPHA = 3
    const val FULL = 255.0
    const val SECONDS = 25.0
    const val LATER_SECONDS = 25.5
    const val TOLERANCE = 1e-4
    const val LIFT = 0.01
    const val CANVAS_LEFT = 128
    const val CANVAS_RIGHT = 384
    val TICK = DigicamSignal.tickAt(SECONDS)
    val CLEAR_BLACK = floatArrayOf(0f, 0f, 0f, 0f)
    val DARK = doubleArrayOf(0.25, 0.3, 0.2)
    val LIGHT = doubleArrayOf(0.6, 0.55, 0.7)
    val QUIET = Digicam(sharpness = 0.5f, noise = 0f, clip = 0.5f)
  }
}

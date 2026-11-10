package dev.jordond.filmstrip.effects.filters.film

import android.opengl.GLES30
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Reads the chain with the weave, flicker, dust, scratches and gate on against [FilmSignal.pixelAt].
 *
 * The input ramps across and down under a bright patch, in codes half floats hold exactly, so a weave moves every read
 * and the halation has an edge to glow off. Each test runs at a tick where a speck of dust and a scratch both land in
 * the frame, and reads them along with a grid that takes in the corners the gate closes.
 */
class FilmGateShaderProgramMediaTest {
  private val gl = FilmGlContext()

  @BeforeTest
  fun makeContext() = gl.open()

  @AfterTest
  fun dropContext() = gl.close()

  @Test
  fun everyOptionAtAMidStrengthLandsOnTheSignal() {
    assertChainMatches(MID, WIDTH, HEIGHT)
  }

  @Test
  fun super8LandsOnTheSignal() {
    assertChainMatches(Film.Super8, WIDTH, HEIGHT)
  }

  @Test
  fun aSeedOfItsOwnDrawsItsOwnLook() {
    val seeded = Film(0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, seed = OTHER_SEED)
    val tick = markedTick(FilmParams.of(seeded), WIDTH, HEIGHT).tick

    assertChainMatches(seeded, WIDTH, HEIGHT)
    withClue("a seed of its own draws a different frame") {
      draw(seeded, WIDTH, HEIGHT, secondsOf(tick)).contentEquals(draw(MID, WIDTH, HEIGHT, secondsOf(tick))) shouldBe
        false
    }
  }

  @Test
  fun aSmallerPreviewDrawsTheSameLookAtItsOwnSize() {
    assertChainMatches(MID, WIDTH * 3 / 4, HEIGHT * 3 / 4)
  }

  @Test
  fun theSameTimeDrawsTheSamePictureAfterASeek() {
    val seconds = secondsOf(markedTick(FilmParams.of(MID), WIDTH, HEIGHT).tick)
    val first = draw(MID, WIDTH, HEIGHT, seconds)
    val elsewhere = draw(MID, WIDTH, HEIGHT, seconds + 1.0)
    val again = draw(MID, WIDTH, HEIGHT, seconds)

    withClue("the look moves between the two times") { elsewhere.contentEquals(first) shouldBe false }
    withClue("the same time draws the same picture") { again.contentEquals(first) shouldBe true }
    assertChainMatches(MID, WIDTH, HEIGHT)
  }

  @Test
  fun theWeaveMixesColorPremultipliedWhereAlphaChanges() {
    val film = Film(grain = 0f, halation = 0f, weave = 1f)
    val tick = MID_TICK
    val offset = FilmSignal.weaveAt(film.seed, tick, 1.0)
    withClue("the weave has to reach past a tenth of a pixel for this to tell") {
      (abs(offset[0] * WIDTH) + abs(offset[1] * HEIGHT) > 0.1) shouldBe true
    }
    val source = { x: Int, y: Int -> sheerAt(x, y) }
    val frame = draw(film, WIDTH, HEIGHT, secondsOf(tick), source)

    for (y in listOf(0, HEIGHT / 3, HEIGHT - 1)) {
      for (x in listOf(0, SHEER_EDGE - 1, SHEER_EDGE, WIDTH / 2, WIDTH - 1)) {
        val wanted = weaved(x, y, WIDTH, HEIGHT, offset, source)
        assertPixel(frame, x, y, WIDTH, HEIGHT, wanted, TOLERANCE)
      }
    }
  }

  private fun assertChainMatches(
    film: Film,
    width: Int,
    height: Int,
  ) {
    val params = FilmParams.of(film)
    val marked = markedTick(params, width, height)
    val offset = FilmSignal.weaveAt(params.seed, marked.tick, params.weave)
    val source = { x: Int, y: Int -> codesAt(x, y, width, height) }
    val moved = Array(height) { y -> Array(width) { x -> weaved(x, y, width, height, offset, source) } }
    val glow = HalationGlow.of(width, height) { x, y -> moved[y][x].copyOf(3) }
    val frame = draw(film, width, height, secondsOf(marked.tick))

    val columns = listOf(0, width / 8, width / 2 - 1, width / 2 + 1, width * 3 / 4, width - 1)
    val rows = listOf(0, height / 4, height / 2 - 1, height / 2 + 1, height - 1)
    val points = rows.flatMap { y -> columns.map { x -> x to y } } + marked.dust + marked.scratch
    points.forEach { (x, y) ->
      val texel = moved[y][x]
      val wanted =
        FilmSignal.pixelAt(
          texel[0],
          texel[1],
          texel[2],
          glow.at(x, y),
          FilmSignal.grainCell(x, y, width, height),
          marked.tick,
          params,
          (x + 0.5) / width,
          (y + 0.5) / height,
          width.toDouble() / height,
        ) + texel[3]
      assertPixel(frame, x, y, width, height, wanted, CHAIN_TOLERANCE)
    }
  }

  private fun assertPixel(
    frame: FloatArray,
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    wanted: DoubleArray,
    tolerance: Double,
  ) {
    // The frame reads bottom up, the way GL hands it back.
    val at = ((height - 1 - y) * width + x) * CHANNELS
    wanted.forEachIndexed { channel, value ->
      withClue("row $y column $x channel $channel of a ${width}x$height frame") {
        frame[at + channel].toDouble() shouldBe (value plusOrMinus tolerance)
      }
    }
  }

  // The first tick from MID_TICK on where a speck of dust and a scratch both land in the frame, with a pixel of each.
  private fun markedTick(
    params: FilmParams,
    width: Int,
    height: Int,
  ): Marked {
    for (tick in MID_TICK until MID_TICK + SEARCH_TICKS step WEAVE_KNOT_TICKS) {
      val scratchX =
        (0 until width).firstOrNull { FilmSignal.scratchAt(params.seed, tick, (it + 0.5) / width, params.dust) != 0.0 }
          ?: continue
      for (y in 0 until height) {
        for (x in 0 until width) {
          val cell = FilmSignal.grainCell(x, y, width, height)
          if (FilmSignal.dustAt(params.seed, tick, cell, params.dust) != 0.0) {
            return Marked(tick, x to y, scratchX to height / 3)
          }
        }
      }
    }
    error("no tick in $SEARCH_TICKS from $MID_TICK holds both dust and a scratch")
  }

  private class Marked(
    val tick: Int,
    val dust: Pair<Int, Int>,
    val scratch: Pair<Int, Int>,
  )

  // The straight RGBA the weave reads at pixel x, y counted from the top, filtered between the four nearest texels
  // premultiplied with each held at the frame's edge.
  private fun weaved(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    offset: DoubleArray,
    source: (Int, Int) -> FloatArray,
  ): DoubleArray {
    val across = x + offset[0] * width
    val down = y + offset[1] * height
    val left = floor(across).toInt()
    val top = floor(down).toInt()
    val fx = across - left
    val fy = down - top
    val tap = { tx: Int, ty: Int ->
      val read = source(tx.coerceIn(0, width - 1), ty.coerceIn(0, height - 1))
      val alpha = read[3].toDouble()
      doubleArrayOf(read[0] * alpha, read[1] * alpha, read[2] * alpha, alpha)
    }
    val mixed =
      DoubleArray(CHANNELS) { channel ->
        val near = tap(left, top)[channel] * (1 - fx) + tap(left + 1, top)[channel] * fx
        val far = tap(left, top + 1)[channel] * (1 - fx) + tap(left + 1, top + 1)[channel] * fx
        near * (1 - fy) + far * fy
      }
    val alpha = mixed[3]
    return DoubleArray(CHANNELS) { channel ->
      when {
        channel == 3 -> alpha
        alpha > 0.0 -> mixed[channel] / alpha
        else -> 0.0
      }
    }
  }

  // A bright patch in the upper left over a ramp across and down, every code a multiple of 1/1024 from a quarter up so
  // half floats hold it exactly.
  private fun codesAt(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
  ): FloatArray {
    if (x < width / 2 && y < height / 2) return floatArrayOf(PATCH[0], PATCH[1], PATCH[2], 1f)
    val red = (QUARTER + floor(x * RAMP / width)) / STEPS
    val green = (QUARTER + floor(y * RAMP / height)) / STEPS
    return floatArrayOf(red.toFloat(), green.toFloat(), BLUE, 1f)
  }

  // Alpha that steps down across the frame to clear at SHEER_EDGE, where the color turns bright, so a straight mix
  // would carry the clear texels' color into the picture.
  private fun sheerAt(
    x: Int,
    y: Int,
  ): FloatArray {
    if (x >= SHEER_EDGE) return floatArrayOf(PATCH[0], PATCH[1], PATCH[2], 0f)
    val alpha = ((QUARTER + floor(x * RAMP * 2 / SHEER_EDGE)) / STEPS).coerceAtMost(1.0)
    val green = (QUARTER + floor(y * RAMP / HEIGHT)) / STEPS
    return floatArrayOf(BLUE, green.toFloat(), BLUE, alpha.toFloat())
  }

  private fun draw(
    film: Film,
    width: Int,
    height: Int,
    seconds: Double,
    source: (Int, Int) -> FloatArray = { x, y -> codesAt(x, y, width, height) },
  ): FloatArray = drawFilm(film, width, height, seconds, GLES30.GL_RGBA16F, source)

  // A time inside the tick, clear of its opening.
  private fun secondsOf(tick: Int) = tick / GRAIN_TICK_RATE + 0.01

  private companion object {
    const val WIDTH = 512
    const val HEIGHT = 480
    const val OTHER_SEED = 7

    // Halfway between two of the weave's knots, so its ease is mid way too.
    const val MID_TICK = 603
    const val SEARCH_TICKS = 2_400
    const val QUARTER = 256.0
    const val RAMP = 400.0
    const val STEPS = 1024.0
    const val BLUE = 0.25f
    const val SHEER_EDGE = 300
    const val TOLERANCE = 1e-4

    // The weave, the halation and the last step each round to half float, 2^-11 from a half to one, and the flicker
    // carries the first two into the last.
    const val CHAIN_TOLERANCE = 1e-3
    val PATCH = floatArrayOf(0.875f, 0.8125f, 0.75f)
    val MID = Film(0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f)
  }
}

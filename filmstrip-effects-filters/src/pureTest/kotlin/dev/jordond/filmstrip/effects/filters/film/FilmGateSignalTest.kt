package dev.jordond.filmstrip.effects.filters.film

import dev.jordond.filmstrip.effects.filters.internal.FilterNoise
import dev.jordond.filmstrip.effects.filters.internal.glslFloat
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.math.abs
import kotlin.test.Test

/**
 * The weave, flicker, dust, scratches and gate a [Film] draws, and the controls that set them.
 */
class FilmGateSignalTest {
  @Test
  fun `today's film reads the same with every new control at zero`() {
    val params = FilmParams.at(0.5, 0.5)
    val blurred = doubleArrayOf(0.4, 0.1, 0.03)

    for (tick in listOf(0, 7, 31, 250)) {
      for (step in 0..10) {
        val code = step / 10.0
        val x = 0.05 + step * 0.09
        val cell = FilmSignal.grainCell(step * 97, step * 53, 1920, 1080)
        val glow = FilmSignal.halated(code, code * 0.8, code * 0.6, blurred, params)
        val today = FilmSignal.grained(glow[0], glow[1], glow[2], cell, tick, params)

        val pixel =
          FilmSignal.pixelAt(code, code * 0.8, code * 0.6, blurred, cell, tick, params, x, 1.0 - x, ASPECT)

        pixel.toList() shouldBe today.toList()
      }
    }
  }

  @Test
  fun `weaveAt stays within its reach and moves a little each tick`() {
    val weave = 0.5
    var moved = 0.0
    var last = FilmSignal.weaveAt(Film.DEFAULT_SEED, 0, weave)

    for (tick in 1..2_000) {
      val offset = FilmSignal.weaveAt(Film.DEFAULT_SEED, tick, weave)
      withClue("tick $tick") {
        abs(offset[0]) shouldBeLessThanOrEqual weave * WEAVE_REACH_X
        abs(offset[1]) shouldBeLessThanOrEqual weave * WEAVE_REACH_Y
        // An eased step between two knots moves at most 1.5 of their gap over a knot, a quarter of the reach a tick.
        abs(offset[0] - last[0]) shouldBeLessThanOrEqual weave * WEAVE_REACH_X * 0.5
        abs(offset[1] - last[1]) shouldBeLessThanOrEqual weave * WEAVE_REACH_Y * 0.5
      }
      moved = maxOf(moved, abs(offset[0] - last[0]))
      last = offset
    }
    moved shouldBeGreaterThan 0.0
    WEAVE_REACH_Y shouldBe (2.0 * WEAVE_REACH_X plusOrMinus 1e-12)
  }

  @Test
  fun `weaveAt lands on each knot and is still at zero`() {
    val atKnot = FilmSignal.weaveAt(Film.DEFAULT_SEED, 2 * WEAVE_KNOT_TICKS, 1.0)
    val knot = FilterNoise.snorm(FilterNoise.hash24(Film.DEFAULT_SEED, PASS_WEAVE_Y, 2, 0))

    atKnot[1] shouldBe (WEAVE_REACH_Y * knot plusOrMinus 1e-15)
    FilmSignal.weaveAt(Film.DEFAULT_SEED, 17, 0.0).toList() shouldBe listOf(0.0, 0.0)
  }

  @Test
  fun `flickerAt averages to one and stays within its depth`() {
    val flicker = 0.5
    val gains = (0 until 20_000).map { FilmSignal.flickerAt(Film.DEFAULT_SEED, it, flicker) }

    gains.average() shouldBe (1.0 plusOrMinus 0.002)
    gains.forEach { abs(it - 1.0) shouldBeLessThanOrEqual FLICKER_DEPTH * flicker }
    FilmSignal.flickerAt(Film.DEFAULT_SEED, 9, 0.0) shouldBe 1.0
  }

  @Test
  fun `dustAt lands about six specks a frame at half dust`() {
    val ticks = 200
    var specks = 0
    for (tick in 0 until ticks) {
      for (block in 0 until DUST_CELLS) {
        val row = block / DUST_CELLS_ACROSS * DUST_CELL + DUST_CELL / 2
        val column = block % DUST_CELLS_ACROSS * DUST_CELL + DUST_CELL / 2
        if (FilmSignal.dustAt(Film.DEFAULT_SEED, tick, row * GRAIN_CELLS_ACROSS + column, 0.5) != 0.0) specks++
      }
    }

    (specks.toDouble() / ticks) shouldBe (DUST_PER_FRAME * 0.5 plusOrMinus 0.6)
  }

  @Test
  fun `a speck is a disc held for its tick alone and dark or light`() {
    val (tick, block) = firstSpeck()
    val row = block / DUST_CELLS_ACROSS * DUST_CELL
    val column = block % DUST_CELLS_ACROSS * DUST_CELL
    val centre = (row + DUST_CELL / 2) * GRAIN_CELLS_ACROSS + column + DUST_CELL / 2

    abs(FilmSignal.dustAt(Film.DEFAULT_SEED, tick, centre, 1.0)) shouldBe DUST_LEVEL
    FilmSignal.dustAt(Film.DEFAULT_SEED, tick, row * GRAIN_CELLS_ACROSS + column, 1.0) shouldBe 0.0
    FilmSignal.dustAt(Film.DEFAULT_SEED, tick, centre, 0.0) shouldBe 0.0
  }

  @Test
  fun `scratchAt lives inside its run and then goes`() {
    val run = 3
    val alive =
      (0 until SCRATCH_RUN_TICKS).filter { offset ->
        val tick = run * SCRATCH_RUN_TICKS + offset
        (0..SCRATCH_STEPS).any { scratchedAt(tick, it) }
      }

    alive.size shouldBeInRange SCRATCH_LIFE_MIN..SCRATCH_LIFE_MAX
    (alive.last() - alive.first() + 1) shouldBe alive.size
    val first = placeOf(run * SCRATCH_RUN_TICKS + alive.first())
    val last = placeOf(run * SCRATCH_RUN_TICKS + alive.last())
    abs(last - first) shouldBeLessThanOrEqual SCRATCH_DRIFT * alive.size + 2.0 / SCRATCH_STEPS
  }

  @Test
  fun `scratchAt draws nothing at zero dust`() {
    for (tick in 0 until 4 * SCRATCH_RUN_TICKS) {
      FilmSignal.scratchAt(Film.DEFAULT_SEED, tick, 0.5, 0.0) shouldBe 0.0
    }
  }

  @Test
  fun `gateMask is open at the centre and shut at a corner and mid edge`() {
    val aspect = ASPECT

    FilmSignal.gateMask(0.5, 0.5, aspect, 1.0) shouldBe 1.0
    FilmSignal.gateMask(0.0, 0.0, aspect, 1.0) shouldBe 0.0
    FilmSignal.gateMask(0.5, 0.0, aspect, 1.0) shouldBe 0.0
    FilmSignal.gateMask(0.0, 0.5, aspect, 1.0) shouldBe 0.0
    FilmSignal.gateMask(0.0, 0.0, aspect, 0.0) shouldBe 1.0
  }

  @Test
  fun `gateMask is half way on its edge at a mid gate on either side`() {
    val aspect = ASPECT
    val gate = 0.5
    val inset = GATE_INSET * gate

    FilmSignal.gateMask(0.5, inset, aspect, gate) shouldBe (0.5 plusOrMinus 1e-9)
    FilmSignal.gateMask(inset / aspect, 0.5, aspect, gate) shouldBe (0.5 plusOrMinus 1e-9)
    FilmSignal.gateMask(0.5, inset + GATE_SOFTNESS * gate, aspect, gate) shouldBe (1.0 plusOrMinus 1e-9)
  }

  @Test
  fun `the gate darkens the pixel last and the flicker scales it before the grain`() {
    val params = FilmParams.at(0.0, 0.0, flicker = 0.5, gate = 0.5)
    val tick = 11
    val gain = FilmSignal.flickerAt(Film.DEFAULT_SEED, tick, 0.5)
    val mask = FilmSignal.gateMask(0.5, GATE_INSET * 0.5, 2.0, 0.5)

    val dark = doubleArrayOf(0.0, 0.0, 0.0)
    val pixel = FilmSignal.pixelAt(0.5, 0.4, 0.3, dark, 0, tick, params, 0.5, GATE_INSET * 0.5, 2.0)

    pixel[0] shouldBe (0.5 * gain * mask plusOrMinus 1e-12)
    pixel[2] shouldBe (0.3 * gain * mask plusOrMinus 1e-12)
  }

  @Test
  fun `the new controls hold their range and set inertness`() {
    shouldThrow<IllegalArgumentException> { Film(weave = 1.5f) }
    shouldThrow<IllegalArgumentException> { Film(flicker = -0.1f) }
    shouldThrow<IllegalArgumentException> { Film(dust = 2f) }
    shouldThrow<IllegalArgumentException> { Film(gate = -1f) }
    Film(0f, 0f, seed = 7).isInert shouldBe true
    Film(0f, 0f, weave = 0.5f).isInert shouldBe false
    Film(0f, 0f, flicker = 0.5f).isInert shouldBe false
    Film(0f, 0f, dust = 0.5f).isInert shouldBe false
    Film(0f, 0f, gate = 0.5f).isInert shouldBe false
  }

  @Test
  fun `Super8 sets every control and its params carry them`() {
    val film = Film.Super8
    val params = FilmParams.of(film)

    val controls = listOf(film.grain, film.halation, film.weave, film.flicker, film.dust, film.gate)
    controls.forEach { (it > 0f) shouldBe true }
    params.weave shouldBe film.weave.toDouble()
    params.flicker shouldBe film.flicker.toDouble()
    params.dust shouldBe film.dust.toDouble()
    params.gate shouldBe film.gate.toDouble()
    params.seed shouldBe Film.DEFAULT_SEED
  }

  @Test
  fun `the shader sources carry the signal's constants`() {
    FILM_GATE_GLSL shouldContain "${DUST_CELLS_ACROSS}u"
    FILM_GATE_GLSL shouldContain glslFloat(WEAVE_REACH_X)
    FILM_GATE_GLSL shouldContain "draw(${PASS_SCRATCH_DRIFT}u"
    FILM_GATE_MSL shouldContain "filterDraw(seed, ${PASS_FLICKER}u"
    FILM_GATE_MSL shouldContain "${glslFloat(GATE_RADIUS)}f"
  }

  private fun firstSpeck(): Pair<Int, Int> {
    for (tick in 0 until 100) {
      for (block in 0 until DUST_CELLS) {
        val row = block / DUST_CELLS_ACROSS * DUST_CELL + DUST_CELL / 2
        val column = block % DUST_CELLS_ACROSS * DUST_CELL + DUST_CELL / 2
        if (FilmSignal.dustAt(Film.DEFAULT_SEED, tick, row * GRAIN_CELLS_ACROSS + column, 1.0) != 0.0) {
          return tick to block
        }
      }
    }
    error("No speck landed in 100 ticks at full dust.")
  }

  private fun placeOf(tick: Int): Double = (0..SCRATCH_STEPS).first { scratchedAt(tick, it) }.toDouble() / SCRATCH_STEPS

  private fun scratchedAt(
    tick: Int,
    step: Int,
  ): Boolean = FilmSignal.scratchAt(Film.DEFAULT_SEED, tick, step.toDouble() / SCRATCH_STEPS, 1.0) > 0.0

  private companion object {
    const val SCRATCH_STEPS = 10_000
    const val ASPECT = 16.0 / 9
  }
}

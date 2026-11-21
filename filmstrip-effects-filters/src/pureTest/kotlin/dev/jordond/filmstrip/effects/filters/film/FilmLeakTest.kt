package dev.jordond.filmstrip.effects.filters.film

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.math.sqrt
import kotlin.test.Test

/**
 * The light [FilmLeak] draws, and where [FilmSignal.pixelAt] lays it.
 */
class FilmLeakTest {
  @Test
  fun `a run holds 96 ticks`() {
    FilmLeak.leakRunAt(0) shouldBe 0
    FilmLeak.leakRunAt(LEAK_RUN_TICKS - 1) shouldBe 0
    FilmLeak.leakRunAt(LEAK_RUN_TICKS) shouldBe 1
    FilmLeak.leakRunAt(LEAK_RUN_TICKS * 5 + 40) shouldBe 5
  }

  @Test
  fun `the envelope opens from nothing to one at mid run along a squared sine`() {
    FilmLeak.leakEnvelope(0) shouldBe 0.0
    FilmLeak.leakEnvelope(LEAK_RUN_TICKS) shouldBe 0.0
    FilmLeak.leakEnvelope(QUARTER_RUN) shouldBe (0.5 plusOrMinus TOLERANCE)
    FilmLeak.leakEnvelope(MID_RUN) shouldBe (1.0 plusOrMinus TOLERANCE)
    FilmLeak.leakEnvelope(LEAK_RUN_TICKS - QUARTER_RUN) shouldBe (0.5 plusOrMinus TOLERANCE)
  }

  @Test
  fun `the second spot runs half a run out of step with the first`() {
    val opening = FilmLeak.leakSpots(SEED, 0)
    val quarter = FilmLeak.leakSpots(SEED, QUARTER_RUN)

    opening.size shouldBe LEAK_SPOTS
    opening[0].envelope shouldBe 0.0
    opening[1].envelope shouldBe (1.0 plusOrMinus TOLERANCE)
    quarter[0].envelope shouldBe (0.5 plusOrMinus TOLERANCE)
    quarter[1].envelope shouldBe (0.5 plusOrMinus TOLERANCE)
  }

  @Test
  fun `a spot sits on an edge with a radius inside its range and a tint from the table`() {
    for (run in 0 until 40) {
      for (spot in FilmLeak.leakSpots(SEED, run * LEAK_RUN_TICKS + MID_RUN)) {
        val onSide = (spot.x == 0.0 || spot.x == 1.0) && spot.y in 0.0..1.0
        val onTop = spot.y == 0.0 && spot.x in 0.0..1.0
        (onSide || onTop) shouldBe true
        (spot.radius in LEAK_RADIUS_MIN..LEAK_RADIUS_MAX) shouldBe true
        LEAK_TINTS.any { it.contentEquals(spot.tint) } shouldBe true
      }
    }
  }

  @Test
  fun `a spot keeps its draw for a whole run and draws again for the next`() {
    val early = FilmLeak.leakSpots(SEED, 1).first()
    val late = FilmLeak.leakSpots(SEED, LEAK_RUN_TICKS - 1).first()
    val places = (0 until 40).map { FilmLeak.leakSpots(SEED, it * LEAK_RUN_TICKS).first() }.map { it.x to it.y }

    (early.x to early.y) shouldBe (late.x to late.y)
    early.radius shouldBe late.radius
    places.toSet().size shouldBeGreaterThan 30
  }

  @Test
  fun `the falloff is one at the centre a quarter at mid radius and nothing past the radius`() {
    FilmLeak.leakFalloff(0.0, 0.6) shouldBe 1.0
    FilmLeak.leakFalloff(0.3, 0.6) shouldBe (0.25 plusOrMinus TOLERANCE)
    FilmLeak.leakFalloff(0.6, 0.6) shouldBe 0.0
    FilmLeak.leakFalloff(0.9, 0.6) shouldBe 0.0
  }

  @Test
  fun `leakAt adds nothing at a leak of zero`() {
    val spot = FilmLeak.leakSpots(SEED, MID_RUN).first()

    FilmLeak.leakAt(spot.x, spot.y, ASPECT, MID_RUN, SEED, 0.0).toList() shouldBe listOf(0.0, 0.0, 0.0)
  }

  @Test
  fun `leakAt adds nothing on a run's first tick or past a spot's radius`() {
    // At mid run the second spot sits on its own first tick, so only the first one is lit.
    val spot = FilmLeak.leakSpots(SEED, MID_RUN).first()
    val past = inward(spot, spot.radius * 1.01)
    val firstTick = FilmLeak.leakSpots(SEED, 0).first()

    FilmLeak.leakSpots(SEED, MID_RUN)[1].envelope shouldBe (0.0 plusOrMinus TOLERANCE)
    FilmLeak.leakAt(past.first, past.second, ASPECT, MID_RUN, SEED, 1.0).sum() shouldBe (0.0 plusOrMinus TOLERANCE)
    FilmLeak.leakAt(spot.x, spot.y, ASPECT, MID_RUN, SEED, 1.0).sum() shouldBeGreaterThan 0.1
    FilmLeak.leakFalloff(distance(firstTick, firstTick.x, firstTick.y), firstTick.radius) shouldBe 1.0
    firstTick.envelope shouldBe 0.0
  }

  @Test
  fun `leakAt at a mid tick a mid radius and a half leak is the product of its parts`() {
    val spots = FilmLeak.leakSpots(SEED, QUARTER_RUN)
    val at = inward(spots[0], spots[0].radius / 2.0)

    val light = FilmLeak.leakAt(at.first, at.second, ASPECT, QUARTER_RUN, SEED, 0.5)

    val own = LEAK_LEVEL * 0.5 * 0.5 * 0.25
    val other = LEAK_LEVEL * 0.5 * 0.5 * FilmLeak.leakFalloff(distance(spots[1], at.first, at.second), spots[1].radius)
    for (channel in 0 until 3) {
      light[channel] shouldBe ((own * spots[0].tint[channel] + other * spots[1].tint[channel]) plusOrMinus TOLERANCE)
    }
    light[0] shouldBeGreaterThan own * 0.99
  }

  @Test
  fun `leakAt is linear in the leak below its ceiling`() {
    val spot = FilmLeak.leakSpots(SEED, MID_RUN).first()
    val at = inward(spot, spot.radius / 2.0)

    val half = FilmLeak.leakAt(at.first, at.second, ASPECT, MID_RUN, SEED, 0.5)
    val full = FilmLeak.leakAt(at.first, at.second, ASPECT, MID_RUN, SEED, 1.0)

    for (channel in 0 until 3) half[channel] shouldBe (full[channel] / 2.0 plusOrMinus TOLERANCE)
  }

  @Test
  fun `equal seeds draw equal runs and two seeds draw apart`() {
    val draws = { seed: Int -> (0 until 8).flatMap { FilmLeak.leakSpots(seed, it * LEAK_RUN_TICKS + MID_RUN) } }
    val shape = { spots: List<LeakSpot> -> spots.map { listOf(it.x, it.y, it.radius) + it.tint.toList() } }

    shape(draws(SEED)) shouldBe shape(draws(SEED))
    shape(draws(SEED)) shouldNotBe shape(draws(SEED + 1))
  }

  @Test
  fun `leaked screens the light over mid codes`() {
    val leaked = FilmLeak.leaked(0.25, 0.5, 0.75, doubleArrayOf(0.4, 0.2, 0.0))

    leaked[0] shouldBe (0.25 + 0.4 * 0.75 plusOrMinus TOLERANCE)
    leaked[1] shouldBe (0.5 + 0.2 * 0.5 plusOrMinus TOLERANCE)
    leaked[2] shouldBe 0.75
    FilmLeak.leaked(1.0, 1.0, 1.0, doubleArrayOf(1.0, 0.5, 0.0)).toList() shouldBe listOf(1.0, 1.0, 1.0)
  }

  @Test
  fun `pixelAt with only a leak lays leakAt's light over the frame`() {
    val params = FilmParams.at(0.0, 0.0, leak = 0.5, seed = SEED)
    val spot = FilmLeak.leakSpots(SEED, MID_RUN).first()
    val at = inward(spot, spot.radius / 2.0)

    val pixel = FilmSignal.pixelAt(0.3, 0.4, 0.5, NO_GLOW, CELL, MID_RUN, params, at.first, at.second, ASPECT)

    val light = FilmLeak.leakAt(at.first, at.second, ASPECT, MID_RUN, SEED, 0.5)
    pixel.toList() shouldBe FilmLeak.leaked(0.3, 0.4, 0.5, light).toList()
    pixel[0] shouldBeGreaterThan 0.3 + 0.05
  }

  @Test
  fun `pixelAt lays the grain on the leak`() {
    val params = FilmParams.at(1.0, 0.0, leak = 0.5, seed = SEED)
    val spot = FilmLeak.leakSpots(SEED, MID_RUN).first()
    val at = inward(spot, spot.radius / 2.0)

    val pixel = FilmSignal.pixelAt(0.3, 0.4, 0.5, NO_GLOW, CELL, MID_RUN, params, at.first, at.second, ASPECT)

    val lit = FilmLeak.leaked(0.3, 0.4, 0.5, FilmLeak.leakAt(at.first, at.second, ASPECT, MID_RUN, SEED, 0.5))
    val grained = FilmSignal.grained(lit[0], lit[1], lit[2], CELL, MID_RUN, params)
    pixel.toList() shouldBe grained.toList()
    grained.toList() shouldNotBe lit.toList()
    grained.toList() shouldNotBe FilmSignal.grained(0.3, 0.4, 0.5, CELL, MID_RUN, params).toList()
  }

  @Test
  fun `the gate darkens the leak`() {
    val params = FilmParams.at(0.0, 0.0, gate = 1.0, leak = 1.0, seed = SEED)
    val spot = FilmLeak.leakSpots(SEED, MID_RUN).first()

    val pixel = FilmSignal.pixelAt(0.3, 0.4, 0.5, NO_GLOW, CELL, MID_RUN, params, spot.x, spot.y, ASPECT)

    pixel.toList() shouldBe listOf(0.0, 0.0, 0.0)
  }

  // A point a distance from a spot's centre, in frame heights, toward the middle of the frame.
  private fun inward(
    spot: LeakSpot,
    by: Double,
  ): Pair<Double, Double> {
    val across = (0.5 - spot.x) * ASPECT
    val down = 0.5 - spot.y
    val length = sqrt(across * across + down * down)
    return (spot.x + across / length * by / ASPECT) to (spot.y + down / length * by)
  }

  private fun distance(
    spot: LeakSpot,
    x: Double,
    y: Double,
  ): Double {
    val across = (x - spot.x) * ASPECT
    val down = y - spot.y
    return sqrt(across * across + down * down)
  }

  private companion object {
    const val SEED = 77
    const val ASPECT = 16.0 / 9.0
    const val MID_RUN = LEAK_RUN_TICKS / 2
    const val QUARTER_RUN = LEAK_RUN_TICKS / 4
    const val CELL = 4242
    const val TOLERANCE = 1e-12
    val NO_GLOW = doubleArrayOf(0.0, 0.0, 0.0)
  }
}

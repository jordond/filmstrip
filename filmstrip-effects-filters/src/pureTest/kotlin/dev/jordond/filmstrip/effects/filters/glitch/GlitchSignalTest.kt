package dev.jordond.filmstrip.effects.filters.glitch

import dev.jordond.filmstrip.effects.filters.internal.HASH_RANGE
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.math.abs
import kotlin.math.min
import kotlin.test.Test

/**
 * The numbers every glitch lowering reads, at the middle of their ranges.
 */
class GlitchSignalTest {
  private val params = GlitchParams.at(HALF)
  private val full = GlitchParams.at(1.0)

  @Test
  fun `tickAt runs at twelve a second and a frame on an opening reads the tick it opens`() {
    GlitchSignal.tickAt(1.0) shouldBe 12
    GlitchSignal.tickAt(2.5) shouldBe 30
    (0 until 100_000).count { frame -> GlitchSignal.tickAt(frame / 24.0) != frame / 2 } shouldBe 0
    (0 until 100_000).count { frame -> GlitchSignal.tickAt(frame / 30.0) != frame * 2 / 5 } shouldBe 0
  }

  @Test
  fun `a burst fires in about its chance of windows`() {
    val windows = 4000
    val fired =
      (0 until windows).count { window ->
        (0 until BURST_WINDOW_TICKS).any { GlitchSignal.burstAt(SEED, window * BURST_WINDOW_TICKS + it, full) != null }
      }

    (fired.toDouble() / windows) shouldBe (full.burstChance plusOrMinus 0.03)
  }

  @Test
  fun `a burst is one run inside its window and null either side of it`() {
    for (window in 0 until 200) {
      val ticks =
        (0 until BURST_WINDOW_TICKS).map {
          GlitchSignal.burstAt(
            SEED,
            window * BURST_WINDOW_TICKS + it,
            full,
          )
        }
      val on = ticks.indices.filter { ticks[it] != null }
      if (on.isEmpty()) continue
      (on.last() - on.first() + 1) shouldBe on.size
      on.size shouldBeInRange BURST_MIN_TICKS..BURST_MAX_TICKS
    }
  }

  @Test
  fun `a burst rises over its attack and falls over its decay`() {
    val (start, length) = firstBurst(minLength = ATTACK_TICKS + DECAY_TICKS)
    val strengths = (0 until length).map { GlitchSignal.burstAt(SEED, start + it, params).shouldNotBeNull().strength }

    strengths[1] shouldBe (2.0 / ATTACK_TICKS plusOrMinus EPSILON)
    strengths[ATTACK_TICKS] shouldBe (1.0 plusOrMinus EPSILON)
    strengths[length - 2] shouldBe (2.0 / DECAY_TICKS plusOrMinus EPSILON)
    strengths.forEachIndexed { into, strength ->
      val expected = min(1.0, (into + 1.0) / ATTACK_TICKS) * min(1.0, (length - into).toDouble() / DECAY_TICKS)
      strength shouldBe (expected plusOrMinus EPSILON)
    }
  }

  @Test
  fun `slices cover the frame top to bottom with no gap`() {
    val (start, _) = firstBurst()
    for (tick in listOf(start, start + 1, 7, 431)) {
      val slices = GlitchSignal.slices(SEED, tick, GlitchSignal.burstAt(SEED, tick, params), params)

      slices.first().top shouldBe 0
      slices.last().bottom shouldBe GlitchSignal.ROWS
      slices.zipWithNext().forEach { (above, below) -> below.top shouldBe above.bottom }
      slices.dropLast(1).forEach { (it.bottom - it.top) shouldBeInRange SLICE_MIN_ROWS..SLICE_MAX_ROWS }
      slices.size shouldBeInRange 2..GlitchSignal.maxSlices
    }
  }

  @Test
  fun `about half the slices slide in a burst and none outside one`() {
    val (start, _) = firstBurst()
    val burst = GlitchSignal.burstAt(SEED, start + 2, params).shouldNotBeNull()
    val all = (start until start + 40).flatMap { GlitchSignal.slices(SEED, it, burst, params) }
    val sliding = all.count { it.shift != 0.0 }

    (sliding.toDouble() / all.size) shouldBe (0.5 plusOrMinus 0.1)
    all.forEach { abs(it.shift) shouldBeLessThanOrEqual params.bandShift * burst.strength }
    GlitchSignal.slices(SEED, start + 2, null, params).forEach { it.shift shouldBe 0.0 }
  }

  @Test
  fun `rowShift is the row's slice slide plus its tear in a burst`() {
    val (start, _) = firstBurst()
    val tick = start + 2
    val burst = GlitchSignal.burstAt(SEED, tick, params).shouldNotBeNull()
    val slices = GlitchSignal.slices(SEED, tick, burst, params)

    for (row in listOf(0, 1, 359, 540, 541, 777, 1079)) {
      val slice = slices.first { row < it.bottom }
      GlitchSignal.rowShift(SEED, tick, row, params) shouldBe
        slice.shift + GlitchSignal.tearAt(SEED, tick, row, burst, params)
    }
    (0 until GlitchSignal.ROWS).any { GlitchSignal.rowShift(SEED, tick, it, params) != 0.0 } shouldBe true
  }

  @Test
  fun `rowShift is zero outside a burst`() {
    val quiet = (0 until 2000).filter { GlitchSignal.burstAt(SEED, it, params) == null }
    quiet.size shouldNotBe 0
    for (tick in quiet.take(50)) {
      (0 until GlitchSignal.ROWS step 7).forEach { GlitchSignal.rowShift(SEED, tick, it, params) shouldBe 0.0 }
    }
  }

  @Test
  fun `tears are sparse thin and slide further than a slice can`() {
    val (start, _) = firstBurst()
    val burst = GlitchSignal.burstAt(SEED, start + 2, params).shouldNotBeNull()
    val tears = (0 until GlitchSignal.ROWS).map { GlitchSignal.tearAt(SEED, start + 2, it, burst, params) }
    val cells = (0 until 50).flatMap { tick -> (0 until GlitchSignal.ROWS step TEAR_ROWS).map { tick to it } }
    val torn = cells.count { (tick, row) -> GlitchSignal.tearAt(SEED, tick, row, burst, params) != 0.0 }

    (torn.toDouble() / cells.size) shouldBe (TEAR_BELOW / HASH_RANGE plusOrMinus 0.005)
    tears.chunked(TEAR_ROWS).forEach { cell -> cell.distinct().size shouldBe 1 }
    tears.forEach { abs(it) shouldBeLessThanOrEqual params.tearShift * burst.strength }
    GlitchSignal.tearAt(SEED, start + 2, 10, null, params) shouldBe 0.0
  }

  @Test
  fun `the split at half intensity is the steady split plus the burst's share`() {
    val (start, _) = firstBurst()
    val quiet = (0 until 2000).first { GlitchSignal.burstAt(SEED, it, params) == null }
    val strength = GlitchSignal.burstAt(SEED, start + 1, params).shouldNotBeNull().strength

    GlitchSignal.channelSplit(SEED, quiet, params) shouldBe (params.split plusOrMinus EPSILON)
    GlitchSignal.channelSplit(SEED, start + 1, params) shouldBe
      (params.split + params.burstSplit * strength plusOrMinus EPSILON)
  }

  @Test
  fun `pixelAt reads red and blue a split either side of green and holds them at the edges`() {
    val (start, _) = firstBurst()
    val tick = start + 2
    val row = 600
    val shift = GlitchSignal.rowShift(SEED, tick, row, params)
    val split = GlitchSignal.channelSplit(SEED, tick, params)
    // A ramp at each column's centre, so a read between two columns lands on the ramp at the read.
    val texel = { column: Int -> ((column + HALF) / WIDTH).let { doubleArrayOf(it, it, it) } }
    val edge = HALF / WIDTH
    val held = { at: Double -> at.coerceIn(edge, 1.0 - edge) }

    val middle = GlitchSignal.pixelAt(0.5, row, tick, SEED, WIDTH, params, texel)
    middle[0] shouldBe (held(0.5 - shift - split) plusOrMinus EPSILON)
    middle[1] shouldBe (held(0.5 - shift) plusOrMinus EPSILON)
    middle[2] shouldBe (held(0.5 - shift + split) plusOrMinus EPSILON)

    val quiet = (0 until 2000).first { GlitchSignal.burstAt(SEED, it, params) == null }
    GlitchSignal.pixelAt(0.0, row, quiet, SEED, WIDTH, params, texel)[0] shouldBe (edge plusOrMinus EPSILON)
    GlitchSignal.pixelAt(1.0, row, quiet, SEED, WIDTH, params, texel)[2] shouldBe (1.0 - edge plusOrMinus EPSILON)
  }

  @Test
  fun `pixelAt between a picture and a clear column keeps the pictures colour and mixes alpha`() {
    val none = GlitchParams.at(0.0)
    val picture = doubleArrayOf(0.8, 0.4, 0.2, 1.0)
    val texel = { column: Int -> if (column < WIDTH / 2) DoubleArray(4) else picture }
    // A quarter of the way from the last clear column's centre to the first picture column's centre.
    val x = (WIDTH / 2 - HALF + QUARTER) / WIDTH

    val read = GlitchSignal.pixelAt(x, 500, 40, SEED, WIDTH, none, texel)

    for (channel in 0 until 3) read[channel] shouldBe (picture[channel] plusOrMinus EPSILON)
    read[3] shouldBe (QUARTER plusOrMinus EPSILON)
    GlitchSignal.pixelAt(HALF / WIDTH, 500, 40, SEED, WIDTH, none, texel).toList() shouldBe listOf(0.0, 0.0, 0.0, 0.0)
  }

  @Test
  fun `widestReach bounds every read`() {
    var widest = 0.0
    for (tick in 0 until 1200) {
      val split = GlitchSignal.channelSplit(SEED, tick, full)
      for (row in 0 until GlitchSignal.ROWS step 5) {
        widest = maxOf(widest, abs(GlitchSignal.rowShift(SEED, tick, row, full)) + split)
      }
    }

    widest shouldBeLessThanOrEqual GlitchSignal.widestReach(full)
    widest shouldNotBe 0.0
  }

  @Test
  fun `intensity zero draws the source`() {
    val none = GlitchParams.at(0.0)
    val texel = { column: Int -> doubleArrayOf(column / 100.0, column / 200.0, 1.0 - column / 100.0) }

    (0 until 2000).forEach { GlitchSignal.burstAt(SEED, it, none).shouldBeNull() }
    GlitchSignal.widestReach(none) shouldBe 0.0
    val read = GlitchSignal.pixelAt((30 + HALF) / WIDTH, 500, 40, SEED, WIDTH, none, texel)
    read.indices.forEach { read[it] shouldBe (texel(30)[it] plusOrMinus EPSILON) }
  }

  @Test
  fun `virtualRow reads a pixel row at its top`() {
    GlitchSignal.virtualRow(0, 540) shouldBe 0
    GlitchSignal.virtualRow(1, 540) shouldBe 2
    GlitchSignal.virtualRow(719, 720) shouldBe 1078
    GlitchSignal.virtualRow(1079, 1080) shouldBe 1079
  }

  // The first tick and the length of the first burst at half intensity at least [minLength] ticks long.
  private fun firstBurst(minLength: Int = BURST_MIN_TICKS): Pair<Int, Int> {
    var tick = 0
    while (true) {
      if (GlitchSignal.burstAt(SEED, tick, params) != null) {
        var length = 0
        val windowEnd = (tick / BURST_WINDOW_TICKS + 1) * BURST_WINDOW_TICKS
        while (tick + length < windowEnd && GlitchSignal.burstAt(SEED, tick + length, params) != null) length++
        if (length >= minLength) return tick to length
        tick += length
      } else {
        tick++
      }
    }
  }
}

private const val SEED = Glitch.DEFAULT_SEED
private const val HALF = 0.5
private const val EPSILON = 1e-12
private const val QUARTER = 0.25
private const val WIDTH = 640

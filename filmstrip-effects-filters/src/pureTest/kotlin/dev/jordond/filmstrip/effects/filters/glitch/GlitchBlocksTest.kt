package dev.jordond.filmstrip.effects.filters.glitch

import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test

/**
 * The broken blocks every glitch lowering draws, read in the middle of their ranges.
 */
class GlitchBlocksTest {
  private val params = GlitchParams.at(1.0, HALF)

  @Test
  fun `a block is forty rows of 1080 at any height`() {
    GlitchBlocks.sidePx(1080) shouldBe 40
    GlitchBlocks.sidePx(2160) shouldBe 80
    GlitchBlocks.sidePx(720) shouldBe 27
    GlitchBlocks.sidePx(540) shouldBe 20
    GlitchBlocks.sidePx(360) shouldBe 13
    GlitchBlocks.sidePx(20) shouldBe 1
    GlitchBlocks.reachPx(1080) shouldBe (BLOCK_REACH + 1) * 40
    GlitchBlocks.columnsOf(1920, 1080) shouldBe 48
    GlitchBlocks.columnsOf(1279, 720) shouldBe 48
  }

  @Test
  fun `steps spread a hash over the whole count`() {
    GlitchBlocks.steps(0, 50_000) shouldBe 0
    GlitchBlocks.steps(0xFFFFFF, 50_000) shouldBe 49_999
    GlitchBlocks.steps(0x800000, 7) shouldBe 3
  }

  @Test
  fun `a frame with no blocks or outside a burst reads every pixel as itself`() {
    val plain = GlitchParams.at(1.0)
    val calm = (0 until 2000).first { GlitchSignal.burstAt(SEED, it, params) == null }
    for (y in 0 until HEIGHT step 7) {
      for (x in 0 until WIDTH step 13) {
        GlitchBlocks.sourceOf(x, y, WIDTH, HEIGHT, SEED, burstTick(), plain).toList() shouldBe listOf(x, y)
        GlitchBlocks.sourceOf(x, y, WIDTH, HEIGHT, SEED, calm, params).toList() shouldBe listOf(x, y)
      }
    }
  }

  @Test
  fun `a row breaks in about its chance times the burst's strength`() {
    val rows = 20_000
    val broken = (0 until rows).count { GlitchBlocks.runAt(SEED, TICK, it, COLUMNS, HALF, params) != null }

    (broken.toDouble() / rows) shouldBe (params.blockChance * HALF plusOrMinus 0.01)
    GlitchBlocks.runAt(SEED, TICK, 0, COLUMNS, 0.0, params).shouldBeNull()
  }

  @Test
  fun `runs start across the frame and take every length`() {
    val runs = (0 until 20_000).mapNotNull { GlitchBlocks.runAt(SEED, TICK, it, COLUMNS, 1.0, params) }

    runs.forEach { run ->
      run.start shouldBeInRange 0..<COLUMNS
      run.length shouldBeInRange 1..RUN_MAX_BLOCKS
    }
    runs.map { it.length }.toSet() shouldBe (1..RUN_MAX_BLOCKS).toSet()
    runs.count { it.start in COLUMNS / 4 until 3 * COLUMNS / 4 } shouldBeGreaterThan runs.size / 3
  }

  @Test
  fun `about half the blocks smear and a displaced block never stands still`() {
    val damages = (0 until 20_000).map { GlitchBlocks.damageAt(SEED, TICK, it / RUN_MAX_BLOCKS, it % RUN_MAX_BLOCKS) }
    val moves = damages.filterIsInstance<BlockDamage.Displace>()

    (damages.count { it == BlockDamage.Smear }.toDouble() / damages.size) shouldBe (HALF plusOrMinus 0.02)
    moves.forEach { move ->
      move.dx shouldBeInRange -BLOCK_REACH..BLOCK_REACH
      move.dy shouldBeInRange -BLOCK_REACH..BLOCK_REACH
      (move.dx to move.dy) shouldNotBe (0 to 0)
    }
    moves.map { it.dx to it.dy }.toSet().size shouldBe BLOCK_MOVES
    moves.map { it.dx }.toSet() shouldContainAll listOf(-2, -1, 1, 2)
  }

  @Test
  fun `a smeared block reads the row above it and a displaced one its move in whole blocks`() {
    val tick = burstTick()
    val strength = GlitchSignal.burstAt(SEED, tick, params).shouldNotBeNull().strength
    val side = GlitchBlocks.sidePx(HEIGHT)
    var smears = 0
    var displaced = 0
    for (blockRow in 1 until HEIGHT / side) {
      val run = GlitchBlocks.runAt(SEED, tick, blockRow, COLUMNS, strength, params) ?: continue
      for (index in 0 until run.length) {
        val column = run.start + index
        if (column >= COLUMNS) break
        // The middle of the block, not its corner.
        val x = column * side + side / 2
        val y = blockRow * side + side / 2
        val read = GlitchBlocks.sourceOf(x, y, WIDTH, HEIGHT, SEED, tick, params).toList()
        when (val damage = GlitchBlocks.damageAt(SEED, tick, blockRow, index)) {
          BlockDamage.Smear -> {
            smears++
            read shouldBe listOf(x, blockRow * side - 1)
          }
          is BlockDamage.Displace -> {
            displaced++
            read shouldBe
              listOf(
                (x + damage.dx * side).coerceIn(0, WIDTH - 1),
                (y + damage.dy * side).coerceIn(0, HEIGHT - 1),
              )
          }
        }
      }
    }
    smears shouldBeGreaterThan 0
    displaced shouldBeGreaterThan 0
  }

  @Test
  fun `a smear on the top row of blocks reads row zero`() {
    val (seed, tick, run) =
      (0 until 50_000)
        .asSequence()
        .mapNotNull { seed ->
          val tick = seed % BURST_WINDOW_TICKS
          val strength = GlitchSignal.burstAt(seed, tick, params)?.strength ?: return@mapNotNull null
          val run = GlitchBlocks.runAt(seed, tick, 0, COLUMNS, strength, params) ?: return@mapNotNull null
          Triple(seed, tick, run).takeIf { GlitchBlocks.damageAt(seed, tick, 0, 0) == BlockDamage.Smear }
        }.first()
    val x = run.start * GlitchBlocks.sidePx(HEIGHT) + 1

    GlitchBlocks.sourceOf(x, 2, WIDTH, HEIGHT, seed, tick, params).toList() shouldBe listOf(x, 0)
  }

  @Test
  fun `more blocks break more rows`() {
    val tick = burstTick()
    val count = { blocks: Double ->
      val p = GlitchParams.at(1.0, blocks)
      (0 until 10_000).count { GlitchBlocks.runAt(SEED, tick, it, COLUMNS, 1.0, p) != null }
    }

    count(0.0) shouldBe 0
    count(QUARTER) shouldBeGreaterThan 0
    count(HALF) shouldBeGreaterThan count(QUARTER)
    count(1.0) shouldBeGreaterThan count(HALF)
  }

  private fun burstTick(): Int =
    (0 until 2000).first { (GlitchSignal.burstAt(SEED, it, params)?.strength ?: 0.0) > HALF }
}

private const val SEED = Glitch.DEFAULT_SEED
private const val TICK = 41
private const val HALF = 0.5
private const val QUARTER = 0.25
private const val WIDTH = 640
private const val HEIGHT = 360
private val COLUMNS = GlitchBlocks.columnsOf(WIDTH, HEIGHT)

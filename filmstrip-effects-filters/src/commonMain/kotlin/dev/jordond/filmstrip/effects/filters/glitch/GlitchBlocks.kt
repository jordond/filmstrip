package dev.jordond.filmstrip.effects.filters.glitch

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effects.filters.internal.FilterNoise.hash24
import dev.jordond.filmstrip.effects.filters.internal.HASH_RANGE
import kotlin.math.ceil
import kotlin.math.max

// One random stream per thing that gets drawn, clear of the other glitch streams.
internal const val PASS_BLOCK_ROW = 40
internal const val PASS_BLOCK_RUN = 41
internal const val PASS_BLOCK_KIND = 42
internal const val PASS_BLOCK_MOVE = 43

// A block's side on the virtual raster, and how a row of blocks breaks.
internal const val BLOCK_ROWS = 40
internal const val RUN_MAX_BLOCKS = 12
internal const val BLOCK_REACH = 3
internal const val SMEAR_BELOW = 1 shl 23

// The moves a displaced block can take, every offset within the reach but standing still.
internal const val BLOCK_SPAN = 2 * BLOCK_REACH + 1
internal const val BLOCK_MOVES = BLOCK_SPAN * BLOCK_SPAN - 1

// A draw of n whole steps keeps the hash's top 16 bits, so its product with n stays inside 32 bits for any n under
// 65536, in a shader's uint as much as here.
internal const val STEP_DROP_BITS = 8
internal const val STEP_KEEP_BITS = 16

/**
 * The broken blocks a [Glitch] with [Glitch.blocks] draws, computed once here and read by all four lowerings.
 *
 * The frame is cut into square blocks of [sidePx] from its top left. During a burst a row of blocks breaks with
 * [GlitchParams.blockChance] times the burst's strength, along a run of up to [RUN_MAX_BLOCKS] blocks. Each block of
 * the run is either displaced, showing the block a few blocks away, or smeared, showing the pixel row just above it
 * down its whole height. [sourceOf] is the pixel a lowering reads in place of each pixel, so the damage is a remap of
 * whole pixels, drawn before [GlitchSignal.pixelAt] slides and splits the frame.
 */
@InternalFilmstripApi
public object GlitchBlocks {
  /**
   * The side of a block in pixels on a frame [height] pixels tall, [BLOCK_ROWS] of the virtual raster rounded to the
   * nearest pixel in whole numbers.
   */
  public fun sidePx(height: Int): Int = max(1, (height * 2 * BLOCK_ROWS + GLITCH_ROWS) / (2 * GLITCH_ROWS))

  /**
   * The farthest a read moves from its own pixel on a frame [height] pixels tall, either way.
   */
  public fun reachPx(height: Int): Int = (BLOCK_REACH + 1) * sidePx(height)

  /**
   * The run of broken blocks on [blockRow] at [tick], or null when the row is whole.
   *
   * @param columns How many blocks span the frame.
   * @param strength The burst's strength at [tick], 0 outside a burst.
   */
  public fun runAt(
    seed: Int,
    tick: Int,
    blockRow: Int,
    columns: Int,
    strength: Double,
    params: GlitchParams,
  ): BlockRun? {
    if (hash24(seed, PASS_BLOCK_ROW, tick, blockRow) >= breakBelow(strength, params)) return null
    val start = steps(hash24(seed, PASS_BLOCK_RUN, tick, 2 * blockRow), columns)
    val length = 1 + steps(hash24(seed, PASS_BLOCK_RUN, tick, 2 * blockRow + 1), RUN_MAX_BLOCKS)
    return BlockRun(start, length)
  }

  /**
   * The hash a row of blocks breaks under at a burst's [strength], so a shader compares whole numbers and lands on the
   * same rows. 0 outside a burst.
   */
  public fun breakBelow(
    strength: Double,
    params: GlitchParams,
  ): Int = if (strength <= 0.0) 0 else ceil(params.blockChance * strength * HASH_RANGE).toInt()

  /**
   * How the block [index] blocks into the run on [blockRow] breaks at [tick].
   */
  public fun damageAt(
    seed: Int,
    tick: Int,
    blockRow: Int,
    index: Int,
  ): BlockDamage {
    val cell = blockRow * RUN_MAX_BLOCKS + index
    if (hash24(seed, PASS_BLOCK_KIND, tick, cell) < SMEAR_BELOW) return BlockDamage.Smear
    val draw = steps(hash24(seed, PASS_BLOCK_MOVE, tick, cell), BLOCK_MOVES)
    val move = if (draw >= BLOCK_MOVES / 2) draw + 1 else draw
    return BlockDamage.Displace(move % BLOCK_SPAN - BLOCK_REACH, move / BLOCK_SPAN - BLOCK_REACH)
  }

  /**
   * The pixel a lowering reads for pixel [x], [y] of a frame [width] by [height] at [tick], held inside the frame.
   *
   * It is the pixel itself outside a burst and outside a run. A displaced block reads its move in whole blocks away,
   * and a smeared block reads its own column on the row above the block.
   *
   * @return the column, then the row.
   */
  public fun sourceOf(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    seed: Int,
    tick: Int,
    params: GlitchParams,
  ): IntArray {
    val own = intArrayOf(x, y)
    if (params.blockChance <= 0.0) return own
    val strength = GlitchSignal.burstAt(seed, tick, params)?.strength ?: return own
    val side = sidePx(height)
    val blockRow = y / side
    val blockColumn = x / side
    val run = runAt(seed, tick, blockRow, columnsOf(width, height), strength, params) ?: return own
    val index = blockColumn - run.start
    if (index < 0 || index >= run.length) return own
    return when (val damage = damageAt(seed, tick, blockRow, index)) {
      BlockDamage.Smear -> {
        intArrayOf(x, max(0, blockRow * side - 1))
      }
      is BlockDamage.Displace -> {
        intArrayOf(
          (x + damage.dx * side).coerceIn(0, width - 1),
          (y + damage.dy * side).coerceIn(0, height - 1),
        )
      }
    }
  }

  /**
   * How many blocks span a frame [width] by [height], the last one cut off at its right edge.
   */
  public fun columnsOf(
    width: Int,
    height: Int,
  ): Int {
    val side = sidePx(height)
    return (width + side - 1) / side
  }

  /**
   * A hash as one of [count] whole steps from 0, for any [count] under 65536.
   */
  internal fun steps(
    hash: Int,
    count: Int,
  ): Int = ((hash ushr STEP_DROP_BITS) * count) ushr STEP_KEEP_BITS
}

/**
 * A run of broken blocks along one row of blocks.
 *
 * @property start The first broken block, counted from the left.
 * @property length How many blocks break, which can run past the right edge.
 */
@InternalFilmstripApi
public class BlockRun(
  public val start: Int,
  public val length: Int,
)

/**
 * How one broken block draws.
 */
@InternalFilmstripApi
public sealed interface BlockDamage {
  /**
   * The block shows the pixel row just above itself down its whole height.
   */
  public data object Smear : BlockDamage

  /**
   * The block shows the block [dx] blocks right and [dy] blocks down from it, never itself.
   *
   * @property dx Blocks to the right, negative to the left.
   * @property dy Blocks down, negative up.
   */
  public data class Displace(
    public val dx: Int,
    public val dy: Int,
  ) : BlockDamage
}

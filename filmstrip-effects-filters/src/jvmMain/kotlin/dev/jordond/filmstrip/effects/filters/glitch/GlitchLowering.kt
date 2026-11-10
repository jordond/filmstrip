package dev.jordond.filmstrip.effects.filters.glitch

import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effects.RgbRoundTrip
import dev.jordond.filmstrip.effects.filters.internal.HASH_RANGE
import dev.jordond.filmstrip.effects.filters.internal.HashExpression

/**
 * The filter chain that draws a [Glitch] on ffmpeg, every figure read from [GlitchSignal].
 *
 * Red, green and blue each read from their own place, so the chain runs in `gbrap` with the matrix named both ways.
 * One `geq` writes every plane, working out its row's shift on the row's first pixel and reading the plane at that
 * shift and the plane's split between the two columns either side, held at the edge of the frame. The planes are
 * straight, so each read weighs its two columns by their alpha, and a clear column lends its neighbour no colour. Alpha
 * reads where green does. Going through `gbrap` and back rounds twice, and 4:2:0 cannot hold the split's colour fringe
 * narrower than its chroma samples. Both are ffmpeg's own limits, and a test pins how close they land.
 */
internal fun glitchFragment(glitch: Glitch): FilterFragment =
  FilterFragment(
    chain =
      RgbRoundTrip.opening() + RgbRoundTrip.toRgb() +
        GlitchExpressions(glitch.seed, GlitchParams.of(glitch)).tear() +
        RgbRoundTrip.toCodes(),
    readsCompositionClock = true,
  )

/**
 * Writes [GlitchSignal]'s functions as ffmpeg expressions.
 *
 * An expression's ten registers last across its pixels, so the row's shift is worked out on the row's first pixel.
 * Registers 0 and 1 hold a hash being computed, 2 the tick, 3 the virtual row, 4 the burst's strength and 9 the row's
 * shift as a fraction. 5, 6, 7 and 8 are scratch. Once the row is worked out, 7 holds the whole columns a read steps
 * from its own and 9 the share of the next column past them, 0 and 1 hold a read's two alpha weights and 8 their sum.
 */
internal class GlitchExpressions(
  seed: Int,
  private val params: GlitchParams,
) {
  private val hasher = HashExpression(seed)

  /**
   * The `geq` that slides every row and splits red and blue either side of green.
   */
  fun tear(): FilterNode =
    FilterNode(
      "geq",
      "r" to plane("r", "+"),
      "g" to plane("g", null),
      "b" to plane("b", "-"),
      "a" to plane(ALPHA_PLANE, null),
      "interpolation" to "nearest",
    )

  /**
   * [GlitchSignal.rowShift] of the tick in register 2 and the row in register 3, with the burst's strength in
   * register 4, as the value of the expression.
   */
  fun rowShift(): String {
    val rows = { index: String ->
      "($SLICE_MIN_ROWS+floor((${hash(PASS_SLICE_HEIGHT, index)})*$SLICE_SPREAD/$HASH_RANGE))"
    }
    val walk =
      listOf(
        "st(7,0)",
        "st(8,0)",
        "st(6,${rows("ld(8)")})",
        "while(gte(ld(3),ld(7)+ld(6)),st(7,ld(7)+ld(6));st(8,ld(8)+1);st(6,${rows("ld(8)")}))",
      )
    val slice =
      "if(lt(${hash(PASS_SLICE_ROLL, "ld(8)")},$SLICE_SHIFT_BELOW)," +
        "st(9,${params.bandShift}*ld(4)*${signed(hash(PASS_SLICE_SHIFT, "ld(8)"))}),0)"
    val tear =
      "if(lt(${hash(PASS_TEAR_ROLL, "ld(6)")},$TEAR_BELOW)," +
        "st(9,ld(9)+${params.tearShift}*ld(4)*${signed(hash(PASS_TEAR_SHIFT, "ld(6)"))}),0)"
    val shifted = (walk + slice + "st(6,floor(ld(3)/$TEAR_ROWS))" + tear).joinToString(";")
    return "st(9,0);if(gt(ld(4),0),$shifted,0);ld(9)"
  }

  /**
   * [GlitchSignal.channelSplit] with the burst's strength in register 4, as the value of the expression.
   */
  fun split(): String = "(${params.split}+${params.burstSplit}*ld(4))"

  /**
   * [GlitchSignal.burstAt]'s strength at the tick in register 2, 0 outside a burst, as the value of the expression.
   */
  fun strength(): String {
    val window = "floor(ld(2)/$BURST_WINDOW_TICKS)"
    val roll = { cell: Int -> "(${hasher.hash(PASS_BURST, window, "$cell")})" }
    val length = "st(5,$BURST_MIN_TICKS+floor((${roll(BURST_CELL_LENGTH)})*$BURST_LENGTHS/$HASH_RANGE))"
    val into =
      "st(6,ld(2)-$window*$BURST_WINDOW_TICKS-" +
        "floor((${roll(BURST_CELL_START)})*($BURST_WINDOW_TICKS-ld(5)+1)/$HASH_RANGE))"
    val shape =
      "if(gte(ld(6),0)*lt(ld(6),ld(5)),min(1,(ld(6)+1)/$ATTACK_TICKS)*min(1,(ld(5)-ld(6))/$DECAY_TICKS),0)"
    return "if(lt((${roll(BURST_CELL_FIRES)})/$HASH_RANGE,${params.burstChance}),$length;$into;$shape,0)"
  }

  // One plane read at the row's shift, and the split away from it the way [sign] points for red and blue. A read mixes
  // the two whole columns either side of it, each held inside the frame, weighted by their alpha and divided by the
  // mixed alpha, which is the signal's premultiplied read on straight planes. Alpha mixes plain, and a read with no
  // alpha under it is black. Green and alpha on a row with no shift read their own pixel.
  private fun plane(
    read: String,
    sign: String?,
  ): String {
    val apart = sign?.let { "$it${split()}" } ?: ""
    val row =
      listOf(
        "st(2,floor(T*$GLITCH_TICK_RATE+$GLITCH_TICK_MARGIN))",
        "st(3,floor(Y*$GLITCH_ROWS/H))",
        "st(4,${strength()})",
        "st(9,${rowShift()})",
        "st(5,-(ld(9)$apart)*W)",
        "st(7,floor(ld(5)))",
        "st(9,ld(5)-ld(7))",
      ).joinToString(";")
    val left = "clip(ld(6),0,W-1)"
    val right = "clip(ld(6)+1,0,W-1)"
    val weights = "st(6,X+ld(7));st(0,(1-ld(9))*alpha($left,Y));st(1,ld(9)*alpha($right,Y));st(8,ld(0)+ld(1))"
    val value =
      if (read == ALPHA_PLANE) {
        "0.5+ld(8)"
      } else {
        "if(gt(ld(8),0),0.5+(ld(0)*$read($left,Y)+ld(1)*$read($right,Y))/ld(8),0)"
      }
    // A row with no shift reads its own pixel, the mix with all of its weight there.
    val still =
      when {
        sign != null -> null
        read == ALPHA_PLANE -> "0.5+alpha(X,Y)"
        else -> "if(gt(alpha(X,Y),0),0.5+$read(X,Y),0)"
      }
    val mixed = "$weights;$value"
    return "if(X,0,$row);" + (still?.let { "if(eq(ld(7),0)*eq(ld(9),0),$it,$mixed)" } ?: mixed)
  }

  private fun hash(
    pass: Int,
    cell: String,
  ): String = "(${hasher.hash(pass, "ld(2)", cell)})"

  private fun signed(hashExpression: String) = "(($hashExpression)/$HASH_RANGE*2-1)"
}

private const val ALPHA_PLANE = "alpha"

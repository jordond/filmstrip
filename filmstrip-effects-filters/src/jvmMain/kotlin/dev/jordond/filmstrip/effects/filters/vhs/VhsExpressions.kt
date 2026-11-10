package dev.jordond.filmstrip.effects.filters.vhs

import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effects.filters.internal.FilterNoise
import dev.jordond.filmstrip.effects.filters.internal.HASH_RANGE
import dev.jordond.filmstrip.effects.filters.internal.HashExpression
import kotlin.math.max

/**
 * Writes [VhsSignal]'s functions as ffmpeg expressions.
 *
 * An expression's ten registers last across its pixels, so a value that holds along a row is worked out on the row's
 * first pixel and a streak's two hashes on the first pixel of each cell. Registers 0 and 1 hold a hash being computed,
 * and most expressions keep the tick in 2 and the row in 3. The rest belong to whichever expression is running.
 */
internal class VhsExpressions(
  private val seed: Int,
  private val params: VhsParams,
) {
  /**
   * The `geq` that shifts every row and carries chroma with it, black past either edge of the picture.
   *
   * It draws a tape holding a picture [width] luma pixels wide with a [margin] of luma pixels either side, so a
   * position is counted from where the picture starts.
   *
   * Alpha moves with the row, so picture displaced into a canvas's clear bar lands over the fill and the part of a bar
   * displaced into the picture lets the fill through. It holds at the picture's own edge past either side, and the
   * margin's own alpha is never read. A frame with no alpha plane ignores it.
   */
  fun displace(
    width: Int,
    margin: Int,
  ): FilterNode {
    val alpha = "clip(X-ld(8)*$width,$margin,${margin + width - 1})"
    return FilterNode(
      "geq",
      "lum" to shifted("lum", "0", LUMA_BLACK, width, margin),
      "cb" to shifted("cb", "${params.chromaShift}", CHROMA_ZERO, width, margin),
      "cr" to shifted("cr", "${params.chromaShift}", CHROMA_ZERO, width, margin),
      "a" to "${onEachRow(prelude(), "st(8,${rowShift()})")};0.5+alpha($alpha,Y)",
    )
  }

  /**
   * The `geq` that draws the added noise as an offset from mid scale in each plane, widened to fill the map so a code
   * of it is a fraction of a plane's code. [grade] takes the widening back off.
   */
  fun grainMap(): FilterNode =
    FilterNode(
      "geq",
      "lum" to lumaGrainPlane(),
      "cb" to chromaGrainPlane(PASS_CB_NOISE),
      "cr" to chromaGrainPlane(PASS_CR_NOISE),
    )

  /**
   * The `geq` that draws a dropout in luma and what is left of chroma in the chroma planes, full scale at 1.
   */
  fun dropMap(): FilterNode {
    val chromaRow = onEachRow(prelude(), "st(4,${dropEvent()})", "st(7,${band()})")
    val gain = "(1-$TRACK_CHROMA_LOSS*ld(7))*(1-${params.trackingSnow}*ld(7))*(1-(${dropout()}))"
    return FilterNode(
      "geq",
      "lum" to "${onEachRow(prelude(), "st(4,${dropEvent()})")};0.5+$FULL_SCALE*(${dropout()})",
      "cb" to "$chromaRow;0.5+$FULL_SCALE*$gain",
      "cr" to "$chromaRow;0.5+$FULL_SCALE*$gain",
    )
  }

  /**
   * The `geq` that draws how far each luma row mixes toward the snow, full scale at 1, with the chroma planes at mid
   * scale.
   */
  fun snowMask(): FilterNode =
    FilterNode(
      "geq",
      "lum" to "${onEachRow(prelude(), "st(5,${band()})")};0.5+$FULL_SCALE*(${params.trackingSnow}*ld(5))",
      "cb" to "$MID_SCALE",
      "cr" to "$MID_SCALE",
    )

  /**
   * The `geq` that draws the snow in luma as the lifted code it lands on times its weight, 0 on a row the band misses,
   * with the chroma planes at mid scale. Register 4 holds the row's streak phase, 5 the snow's weight there, 6 the
   * hash's state, 7 and 8 the ends of the streak's cell, and 9 the streak, since `clip` reads its value twice.
   */
  fun snowMap(): FilterNode {
    val cell = Cell(4, 7, 8, state = 6)
    val row =
      onEachRow(
        prelude(),
        "st(5,${params.trackingSnow}*(${band()}))",
        "st(4,${streakPhase(PASS_SNOW)})",
        stateOf(PASS_SNOW, cell),
      )
    val value = "st(9,${streakAt(PASS_SNOW, SNOW_CELL, cell)});clip(0.5+${SNOW_CONTRAST * 0.5}*ld(9),0,1)"
    val snow = "(${params.lift}+($value)*${1.0 - params.lift})"
    return FilterNode(
      "geq",
      "lum" to "$row;if(gt(ld(5),0),0.5+ld(5)*($LUMA_BLACK+$LUMA_CODES*$snow),0)",
      "cb" to "$MID_SCALE",
      "cr" to "$MID_SCALE",
    )
  }

  /**
   * The `lut2` that takes the snow's weight off luma, so [addSnow] can add the weighted snow back.
   *
   * @param stopsWithFrame Whether the frame ending ends this `lut2`, for a map that runs on past it.
   */
  fun takeSnowWeight(stopsWithFrame: Boolean = false): FilterNode = lut2(stopsWithFrame, "0.5+x*(1-y/$FULL_SCALE)", "x")

  /**
   * The `lut2` that adds the weighted snow onto luma.
   *
   * @param stopsWithFrame Whether the frame ending ends this `lut2`, for a map that runs on past it.
   */
  fun addSnow(stopsWithFrame: Boolean = false): FilterNode = lut2(stopsWithFrame, "min(x+y,$FULL_SCALE)", "x")

  /**
   * The `lut2` that adds the noise and lifts and desaturates the frame, rounding the sum once, so noise under half a
   * code still moves the codes it tips over. Its table is worked out once a graph, so a pixel costs one read.
   *
   * @param stopsWithFrame Whether the frame ending ends this `lut2`, for a map that runs on past it.
   */
  fun grade(stopsWithFrame: Boolean = false): FilterNode {
    val lift = params.lift
    val chroma = "0.5+$CHROMA_ZERO+(x-$CHROMA_ZERO+(y-$MID_SCALE)/$chromaGrainScale)*(1-${params.desaturation})"
    return lut2(
      stopsWithFrame,
      "max(0,0.5+$LUMA_BLACK+$LUMA_CODES*$lift+(x+(y-$MID_SCALE)/$lumaGrainScale-$LUMA_BLACK)*(1-$lift))",
      chroma,
    )
  }

  /**
   * The `lut2` that lands a dropout on a graded frame. Lifting is the same screen a dropout is, so it does not matter
   * which of them runs first, and a pixel no dropout covers passes through with no second rounding.
   *
   * @param stopsWithFrame Whether the frame ending ends this `lut2`, for a map that runs on past it.
   */
  fun landDropout(stopsWithFrame: Boolean = false): FilterNode {
    val chroma = "0.5+$CHROMA_ZERO+(x-$CHROMA_ZERO)*y/$FULL_SCALE"
    return lut2(stopsWithFrame, "0.5+x+($LUMA_WHITE-x)*y/$FULL_SCALE", chroma)
  }

  private fun lut2(
    stopsWithFrame: Boolean,
    luma: String,
    chroma: String,
  ): FilterNode {
    val planes = listOf("c0" to luma, "c1" to chroma, "c2" to chroma)
    return FilterNode("lut2", *(if (stopsWithFrame) listOf("shortest" to "1") + planes else planes).toTypedArray())
  }

  /**
   * [FilterNoise.hash24] of [pass], the tick in [tick] and the cell in [cell], leaving it in register 0.
   */
  fun hash(
    pass: Int,
    tick: String,
    cell: String,
  ): String = hasher.hash(pass, tick, cell)

  private val hasher = HashExpression(seed)

  // Map codes to a plane's code, so the most noise a plane takes reaches GRAIN_REACH either side of mid scale.
  val lumaGrainScale = grainScale(LUMA_CODES * (params.lumaNoise + params.trackingNoise))
  val chromaGrainScale = grainScale(CHROMA_CODES * params.chromaNoise)

  private fun grainScale(mostCodes: Double) = GRAIN_REACH / max(mostCodes, MIN_GRAIN_CODES)

  private fun unit(hashExpression: String) = "(($hashExpression)/$HASH_RANGE)"

  private fun signed(hashExpression: String) = "(${unit(hashExpression)}*2-1)"

  // VhsSignal.tickAt of the frame's time.
  private val clockTick =
    "floor((floor(T*$TICK_CLOCK_BASE+$TICK_CLOCK_ROUNDING)+$TICK_CLOCK_ROUNDING)/$TICK_CLOCK_BASE*$TICK_RATE+" +
      "$TICK_ROUNDING+$TICK_MARGIN)"

  // The tick and the row of this pixel, from the sample's own position in its plane.
  private fun prelude() = "st(2,$clockTick);st(3,floor(Y*$VIRTUAL_ROWS/H))"

  // A chroma streak row covers two virtual rows.
  private fun chromaPrelude() = "st(2,$clockTick);st(3,floor(floor(Y*$VIRTUAL_ROWS/H)/$CHROMA_NOISE_ROWS))"

  // Runs [steps] on the first sample of each row, which a slice always draws ahead of the rest of the row.
  private fun onEachRow(vararg steps: String): String = "if(X,0,${steps.joinToString(";")})"

  // W and H in a geq are the size of the plane being drawn, so a chroma sample takes its own plane's fractions.
  private val x = "((X+0.5)/W)"

  private fun chromaCell() = STREAK * CHROMA_COARSENESS

  // Register 7 holds the row's shift in this plane's samples. Registers 5 and 6 hold the columns of the row half a luma
  // pixel or more inside either edge of the picture, where the fade is one.
  private fun shifted(
    plane: String,
    chromaShift: String,
    black: Int,
    width: Int,
    margin: Int,
  ): String {
    val at = "(((X+0.5)/SW-$margin)/$width-ld(8)-$chromaShift)"
    val inside = "(clip($at*$width+0.5,0,1)*clip((1-$at)*$width+0.5,0,1))"
    val read = "$plane(X-ld(7),Y)"
    val row =
      onEachRow(
        prelude(),
        "st(8,${rowShift()})",
        "st(7,(ld(8)+$chromaShift)*$width*SW)",
        "st(5,$margin*SW+ld(7)+0.5*SW-0.5)",
        "st(6,($margin+$width)*SW+ld(7)-0.5*SW-0.5)",
      )
    return "$row;if(gte(X,ld(5))*lte(X,ld(6)),0.5+$read,0.5+$black+($read-$black)*$inside)"
  }

  private fun rowShift(): String {
    val phase = FilterNoise.unorm(FilterNoise.hash24(seed, PASS_WAVE, 0, 0))
    val seconds = "(ld(2)/$TICK_RATE)"
    val y = "((ld(3)+0.5)/$VIRTUAL_ROWS)"
    val wave =
      "${params.wave}*($WAVE_FIRST*sin($TAU*($WAVE_CYCLES*$y+$WAVE_HZ*$seconds+$phase))" +
        "+${1.0 - WAVE_FIRST}*sin($TAU*(${WAVE_SECOND_CYCLES * WAVE_CYCLES}*$y+${WAVE_SECOND_HZ * WAVE_HZ}*$seconds" +
        "+2*$phase)))"
    val into = "(ld(3)-${VIRTUAL_ROWS - VhsSignal.headRows})"
    val head =
      "if(gte($into,0),${params.headShift}*(1+$HEAD_WOBBLE*${signed(hash(PASS_HEAD, "ld(2)", "0"))})" +
        "*pow(($into+$HEAD_OFFSET)/${VhsSignal.headRows},$HEAD_POWER),0)"
    val jitter = "${params.jitter}*${signed(hash(PASS_JITTER, "ld(2)", "ld(3)"))}"
    return listOf(
      "st(4,$jitter)",
      "st(4,ld(4)+$wave)",
      "st(4,ld(4)+$head)",
      "st(5,${band()})",
      "if(gt(ld(5),0),st(4,ld(4)+${params.trackingShift}*ld(5)*${signed(hash(PASS_TRACK_SHIFT, "ld(2)", "ld(3)"))}),0)",
      "ld(4)",
    ).joinToString(";")
  }

  // VhsSignal.trackingWeight of the tick in [tick] and the row in [row], as the value of the expression.
  private fun band(
    tick: String = "ld(2)",
    row: String = "ld(3)",
  ): String {
    val distance = "st(1,abs(($row+0.5)/$VIRTUAL_ROWS-ld(1)));st(1,min(ld(1),1-ld(1)))"
    val edge = "clip(ld(1)/${params.trackingHalfHeight},0,1)"
    val fade = "(1-$edge*$edge*(3-2*$edge))"
    if (params.trackingRolls) {
      return "st(1,$tick/${params.trackingPeriodTicks});st(1,ld(1)-floor(ld(1)));$distance;$fade"
    }
    val windowTicks = VhsSignal.trackWindowTicks
    val window = "floor($tick/$windowTicks)"
    val phase = "(mod($tick,$windowTicks)/$windowTicks)"
    val roll = unit(hash(PASS_TRACK_EVENT, window, "0"))
    val start = unit(hash(PASS_TRACK_EVENT, window, "1"))
    val centre = "st(1,$start);st(1,ld(1)+${TRACK_DRIFT * TRACK_WINDOW_SECONDS}*$phase);st(1,ld(1)-floor(ld(1)))"
    return "if(gte($roll,${params.trackingChance}),0,$centre;$distance;sin(PI*$phase)*$fade)"
  }

  // A plane of luma noise, with the tracking band's own noise over it on the rows the band reaches. The tick and the
  // row are read where they are needed, which leaves registers for both streaks. Register 4 holds the noise's phase and
  // 7 and 8 its cell's ends, 5 the band's weight, 2 the band noise's phase and 3 and 9 its cell's ends, and 6 the
  // noise while the band's is added.
  private fun lumaGrainPlane(): String {
    val tick = clockTick
    val row = "floor(Y*$VIRTUAL_ROWS/H)"
    val start =
      onEachRow(
        "st(4,${streakPhase(PASS_LUMA_NOISE, tick, row)})",
        "st(5,${band(tick, row)})",
        "if(gt(ld(5),0),st(2,${streakPhase(PASS_TRACK_NOISE, tick, row)}),0)",
      )
    val noise = "${params.lumaNoise}*(${streakAt(PASS_LUMA_NOISE, STREAK, Cell(4, 7, 8), tick, row)})"
    val tracking =
      "${params.trackingNoise}*ld(5)*(${streakAt(PASS_TRACK_NOISE, TRACK_NOISE_CELL, Cell(2, 3, 9), tick, row)})"
    val grain = "if(gt(ld(5),0),st(6,$noise);ld(6)+$tracking,$noise)"
    return "$start;$MID_SCALE+0.5+$lumaGrainScale*$LUMA_CODES*($grain)"
  }

  private fun chromaGrainPlane(pass: Int): String {
    val cell = Cell(4, 7, 8, state = 5)
    val row = onEachRow(chromaPrelude(), "st(4,${streakPhase(pass)})", stateOf(pass, cell))
    val streak = streakAt(pass, chromaCell(), cell)
    return "$row;$MID_SCALE+0.5+$chromaGrainScale*$CHROMA_CODES*${params.chromaNoise}*($streak)"
  }

  // VhsSignal.streak's phase on [row] at [tick].
  private fun streakPhase(
    pass: Int,
    tick: String = "ld(2)",
    row: String = "ld(3)",
  ) = unit(hash(pass, tick, row))

  // Keeps the hash's state after [pass] and the tick in register 2 where [cell] has room for it.
  private fun stateOf(
    pass: Int,
    cell: Cell,
  ) = "st(${cell.state},${hasher.state(pass, "ld(2)")})"

  // VhsSignal.streak on [row] at [tick] at the raster sample's x, through the registers [cell] names. A cell's two ends
  // are read on its first sample, the row's first or one whose left neighbour sits in another cell. A cell straight
  // after its neighbour's starts where that one ends, so only its far end is hashed. Each read moves the cell on, so a
  // sample reads it once.
  private fun streakAt(
    pass: Int,
    cellLength: Double,
    cell: Cell,
    tick: String = "ld(2)",
    row: String = "ld(3)",
  ): String {
    val at = "$x/$cellLength+ld(${cell.phase})"
    val before = "((X-0.5)/W)/$cellLength+ld(${cell.phase})"
    val endOf = { end: Int ->
      val cellAt = "$row+(floor($at)+$end)*$ROW_CELL_STRIDE"
      signed(if (cell.state == null) hash(pass, tick, cellAt) else hasher.hashFrom("ld(${cell.state})", cellAt))
    }
    val a = endOf(1)
    val b = endOf(2)
    val next = "st(${cell.a},ld(${cell.b}));st(${cell.b},$b)"
    val both = "st(${cell.a},$a);st(${cell.b},$b)"
    val read = "if(X*eq(floor($before),floor($at)),0,if(X*eq(floor($before)+1,floor($at)),$next,$both))"
    val blend = "st(0,ld(0)-floor(ld(0)));ld(${cell.a})+(ld(${cell.b})-ld(${cell.a}))*ld(0)*ld(0)*(3-2*ld(0))"
    return "$read;st(0,$at);$blend"
  }

  // The registers one streak keeps across a row, its phase, its cell's two ends, and the hash's state when it has one.
  private class Cell(
    val phase: Int,
    val a: Int,
    val b: Int,
    val state: Int? = null,
  )

  // Whether a dropout starts on the row in register 3, as the hash VhsSignal.dropout rolls.
  private fun dropEvent() = unit(hash(PASS_DROP_EVENT, "ld(2)", "ld(3)"))

  // VhsSignal.dropout on the row in register 3 at the raster sample's x, with the row's roll in register 4.
  private fun dropout(): String {
    val start = unit(hash(PASS_DROP_POS, "ld(2)", "ld(3)"))
    val length = "($DROPOUT_LENGTH*($DROPOUT_LENGTH_MIN+$DROPOUT_LENGTH_SPREAD*${unit(
      hash(PASS_DROP_LEN, "ld(2)", "ld(3)"),
    )}))"
    val rise = smoothstep("0", "$DROPOUT_RISE*ld(5)", "ld(6)")
    val fall = smoothstep("$DROPOUT_FALL*ld(5)", "ld(5)", "ld(6)")
    val body = "st(6,$x-$start);st(5,$length);if(lt(ld(6),0)+gt(ld(6),ld(5)),0,$rise*(1-$fall))"
    return "if(gte(ld(4),${params.dropoutChance}),0,$body)"
  }

  private fun smoothstep(
    from: String,
    to: String,
    value: String,
  ): String {
    val t = "clip(($value-$from)/($to-$from),0,1)"
    return "($t*$t*(3-2*$t))"
  }

  private companion object {
    const val TAU = 6.283185307179586
  }
}

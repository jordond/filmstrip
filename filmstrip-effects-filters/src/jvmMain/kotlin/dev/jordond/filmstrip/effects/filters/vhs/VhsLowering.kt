package dev.jordond.filmstrip.effects.filters.vhs

import dev.jordond.filmstrip.effect.FilterBranch
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.geometry.Size
import kotlin.math.ceil
import kotlin.math.max

/**
 * The filter graph that draws a [Vhs] on ffmpeg, every figure read from [VhsSignal].
 *
 * The fragment opens by converting whatever it is handed to 8 bit limited range 4:2:0, keeping any alpha, because its
 * expressions and tables are written in those codes. A clip's chain runs on the source's own format until then.
 *
 * A full frame `geq` displaces all three planes, chroma shift included, so the answer is subpixel. `gblur` then softens
 * each plane along the row, and a halo is a second `gblur` taken back off through `lut2`. All of that runs on a tape
 * wider than the frame by a margin either side and is cropped back after, since `gblur` holds its edge pixel past what
 * it is handed where the signal keeps reading the tape, which is black past the picture. Noise, dropouts and the
 * tracking band's noise are drawn by `geq` on a narrow raster, widened by neighbour and merged with `lut2`, so their
 * cost does not follow the frame's width.
 *
 * `gblur` is a recursive approximation of a Gaussian, which is ffmpeg's own limit, and a test pins how close it lands.
 */
internal fun vhsFragment(
  vhs: Vhs,
  frame: Size,
): FilterFragment {
  val params = VhsParams.of(vhs)
  val width = frame.width
  val height = frame.height
  val nodes = VhsExpressions(vhs.seed, params)
  val normalised =
    listOf(
      FilterNode("scale", "out_range" to "tv"),
      FilterNode("format", "pix_fmts" to TAPE_FORMATS),
    )
  val margin = tapeMargin(params, width)
  // A sigma is taken per plane in that plane's own pixels. gblur reads sigmaV=0 as no blur down the column.
  val sharp =
    listOf(
      FilterNode(
        "pad",
        "w" to "${width + 2 * margin}",
        "h" to "$height",
        "x" to "$margin",
        "y" to "0",
        "color" to "black",
      ),
      nodes.displace(width, margin),
      gblur(params.lumaSigma * width, LUMA_PLANE),
      gblur(params.chromaSigma * width / 2.0, CHROMA_PLANES),
    )
  // lut2 does not clamp, and writes a result of -1 or lower as the top code, so black would wrap to white.
  val halo =
    FilterNode("lut2", "c0" to "max(0,0.5+x+${params.haloGain}*(x-y))")
  // Only the width drops. A neighbour scale reads a row at its centre, and virtualRow reads it at its top, so a map
  // scaled down the column would land up to a third of its rows on the wrong virtual row. A map reads no input, so its
  // raster is a crop, which copies nothing. A frame narrower than the raster is widened instead.
  val raster =
    if (width >= RASTER_WIDTH) {
      FilterNode("crop", "w" to "$RASTER_WIDTH", "h" to "$height", "x" to "0", "y" to "0")
    } else {
      FilterNode("scale", "w" to "$RASTER_WIDTH", "h" to "$height", "flags" to "neighbor")
    }
  val upscale = FilterNode("scale", "w" to "$width", "h" to "$height", "flags" to "neighbor")
  val unpad = FilterNode("crop", "w" to "$width", "h" to "$height", "x" to "$margin", "y" to "0")
  return FilterFragment(
    readsCompositionClock = true,
    branches =
      listOf(
        FilterBranch(listOf(FilterBranch.INPUT), normalised, listOf("frame")),
        FilterBranch(listOf("frame"), emptyList(), listOf("tape", "grainIn", "dropIn")),
        FilterBranch(listOf("tape"), sharp, listOf("sharp")),
        FilterBranch(listOf("sharp"), emptyList(), listOf("near", "far")),
        FilterBranch(listOf("far"), listOf(gblur(HALO_SIGMA * width, LUMA_PLANE)), listOf("wide")),
        FilterBranch(listOf("near", "wide"), listOf(halo, unpad), listOf("ringed")),
        FilterBranch(listOf("grainIn"), listOf(raster, nodes.grainMap(), upscale), listOf("grain")),
        FilterBranch(listOf("ringed", "grain"), listOf(nodes.grade()), listOf("graded")),
        FilterBranch(listOf("dropIn"), listOf(raster, nodes.dropMap(), upscale), listOf("drop")),
        FilterBranch(listOf("graded", "drop"), listOf(nodes.landDropout()), listOf("out")),
      ),
  )
}

// Enough tape for four sigmas of the widest blur, on chroma's grid. A chroma sigma in its own plane covers twice as
// many luma pixels, which is the fraction of the width it already is.
private fun tapeMargin(
  params: VhsParams,
  width: Int,
): Int {
  val pixels = ceil(MARGIN_SIGMAS * max(params.wideSigma, params.chromaSigma) * width).toInt()
  return max(pixels + pixels % 2, 2)
}

private fun gblur(
  sigma: Double,
  planes: Int,
): FilterNode =
  FilterNode(
    "gblur",
    "sigma" to "${max(sigma, MIN_SIGMA)}",
    "sigmaV" to "0",
    "steps" to "$GBLUR_STEPS",
    "planes" to "$planes",
  )

/**
 * Writes [VhsSignal]'s functions as ffmpeg expressions.
 *
 * An expression's ten registers last across its pixels, so a value that holds along a row is worked out on the row's
 * first pixel and a streak's two hashes on the first pixel of each cell. Registers 0 and 1 hold a hash being computed,
 * 2 the tick and 3 the row. The rest belong to whichever expression is running.
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
    val gain = "(1-$TRACK_CHROMA_LOSS*ld(7))*(1-(${dropout()}))"
    return FilterNode(
      "geq",
      "lum" to "${onEachRow(prelude(), "st(4,${dropEvent()})")};0.5+$FULL_SCALE*(${dropout()})",
      "cb" to "$chromaRow;0.5+$FULL_SCALE*$gain",
      "cr" to "$chromaRow;0.5+$FULL_SCALE*$gain",
    )
  }

  /**
   * The `lut2` that adds the noise and lifts and desaturates the frame, rounding the sum once, so noise under half a
   * code still moves the codes it tips over. Its table is worked out once a graph, so a pixel costs one read.
   */
  fun grade(): FilterNode {
    val lift = params.lift
    val chroma = "0.5+$CHROMA_ZERO+(x-$CHROMA_ZERO+(y-$MID_SCALE)/$chromaGrainScale)*(1-${params.desaturation})"
    return FilterNode(
      "lut2",
      "c0" to "max(0,0.5+$LUMA_BLACK+$LUMA_CODES*$lift+(x+(y-$MID_SCALE)/$lumaGrainScale-$LUMA_BLACK)*(1-$lift))",
      "c1" to chroma,
      "c2" to chroma,
    )
  }

  /**
   * The `lut2` that lands a dropout on a graded frame. Lifting is the same screen a dropout is, so it does not matter
   * which of them runs first, and a pixel no dropout covers passes through with no second rounding.
   */
  fun landDropout(): FilterNode {
    val chroma = "0.5+$CHROMA_ZERO+(x-$CHROMA_ZERO)*y/$FULL_SCALE"
    return FilterNode(
      "lut2",
      "c0" to "0.5+x+($LUMA_WHITE-x)*y/$FULL_SCALE",
      "c1" to chroma,
      "c2" to chroma,
    )
  }

  /**
   * [VhsSignal.hash24] of [pass], the tick in [tick] and the cell in [cell], leaving it in register 0.
   */
  fun hash(
    pass: Int,
    tick: String,
    cell: String,
  ): String =
    listOf(
      "st(0,$seedBits)",
      absorb("$pass"),
      absorb(tick),
      absorb(cell),
      "st(0,${xorShift("ld(0)", HASH_END_SHIFT)})",
      "st(0,${multiply("ld(0)", HASH_M3)})",
      "st(0,${xorShift("ld(0)", HASH_ABSORB_SHIFT)})",
      "st(0,${multiply("ld(0)", HASH_M4)})",
      "st(0,${xorShift("ld(0)", HASH_END_SHIFT)})",
      "ld(0)",
    ).joinToString(";")

  private val seedBits = seed and HASH_MASK

  // Map codes to a plane's code, so the most noise a plane takes reaches GRAIN_REACH either side of mid scale.
  private val lumaGrainScale = grainScale(LUMA_CODES * (params.lumaNoise + params.trackingNoise))
  private val chromaGrainScale = grainScale(CHROMA_CODES * params.chromaNoise)

  private fun grainScale(mostCodes: Double) = GRAIN_REACH / max(mostCodes, MIN_GRAIN_CODES)

  private fun absorb(input: String): String =
    listOf(
      "st(1,${multiply("mod($input,$HASH_RANGE_INT)", HASH_M1)})",
      "st(1,${xorShift("ld(1)", HASH_ABSORB_SHIFT)})",
      "st(1,${multiply("ld(1)", HASH_M2)})",
      "st(0,mod(${xor("ld(0)", "ld(1)")}*$HASH_FIVE+$HASH_ADD,$HASH_RANGE_INT))",
      "st(0,${xorShift("ld(0)", HASH_MIX_SHIFT)})",
    ).joinToString(";")

  private fun multiply(
    value: String,
    by: Int,
  ) = "mod($value*$by,$HASH_RANGE_INT)"

  private fun xorShift(
    value: String,
    bits: Int,
  ) = xor(value, "floor($value/${1 shl bits})")

  private fun xor(
    a: String,
    b: String,
  ) = "(bitor($a,$b)-bitand($a,$b))"

  private fun unit(hashExpression: String) = "(($hashExpression)/$HASH_RANGE)"

  private fun signed(hashExpression: String) = "(${unit(hashExpression)}*2-1)"

  // The tick and the row of this pixel, from the sample's own position in its plane.
  private fun prelude() = "st(2,floor(T*$TICK_RATE+0.5));st(3,floor(Y*$VIRTUAL_ROWS/H))"

  // A chroma streak row covers two virtual rows.
  private fun chromaPrelude() = "st(2,floor(T*$TICK_RATE+0.5));st(3,floor(floor(Y*$VIRTUAL_ROWS/H)/$CHROMA_NOISE_ROWS))"

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
    val phase = VhsSignal.unorm(VhsSignal.hash24(seed, PASS_WAVE, 0, 0))
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

  // VhsSignal.trackingWeight of the tick and row in registers 2 and 3, as the value of the expression.
  private fun band(): String {
    val windowTicks = VhsSignal.trackWindowTicks
    val window = "floor(ld(2)/$windowTicks)"
    val phase = "(mod(ld(2),$windowTicks)/$windowTicks)"
    val roll = unit(hash(PASS_TRACK_EVENT, window, "0"))
    val start = unit(hash(PASS_TRACK_EVENT, window, "1"))
    val centre = "st(1,$start);st(1,ld(1)+${TRACK_DRIFT * TRACK_WINDOW_SECONDS}*$phase);st(1,ld(1)-floor(ld(1)))"
    val distance = "st(1,abs((ld(3)+0.5)/$VIRTUAL_ROWS-ld(1)));st(1,min(ld(1),1-ld(1)))"
    val edge = "clip(ld(1)/${0.5 * TRACK_BAND},0,1)"
    val fade = "(1-$edge*$edge*(3-2*$edge))"
    return "if(gte($roll,${params.trackingChance}),0,$centre;$distance;sin(PI*$phase)*$fade)"
  }

  // A plane of luma noise, with the tracking band's own noise over it on the rows the band reaches. Register 4 holds
  // the row's phase, 5 the band's weight there, and the streak's cell in 6, 7 and 8. A row inside the band reads a
  // second streak through 6 to 8, so it marks the cell as unread for the pixel after it.
  private fun lumaGrainPlane(): String {
    val row =
      onEachRow(
        prelude(),
        "st(4,${streakPhase(PASS_LUMA_NOISE)})",
        "st(5,${band()})",
        "st($STREAK_CELL,$NO_CELL)",
      )
    val noise = "${params.lumaNoise}*(${cachedStreak(PASS_LUMA_NOISE, STREAK)})"
    val tracking = "${params.trackingNoise}*ld(5)*(${inlineStreak(PASS_TRACK_NOISE, TRACK_NOISE_CELL)})"
    val grain = "if(gt(ld(5),0),st(7,$noise);st(7,ld(7)+$tracking);st($STREAK_CELL,$NO_CELL);ld(7),$noise)"
    return "$row;$MID_SCALE+0.5+$lumaGrainScale*$LUMA_CODES*($grain)"
  }

  private fun chromaGrainPlane(pass: Int): String {
    val row = onEachRow(chromaPrelude(), "st(4,${streakPhase(pass)})", "st($STREAK_CELL,$NO_CELL)")
    return "$row;$MID_SCALE+0.5+$chromaGrainScale*$CHROMA_CODES*${params.chromaNoise}*(${cachedStreak(
      pass,
      chromaCell(),
    )})"
  }

  // VhsSignal.streak's phase on the row in register 3.
  private fun streakPhase(pass: Int) = unit(hash(pass, "ld(2)", "ld(3)"))

  // VhsSignal.streak on the row in register 3 at the raster sample's x, with the phase in register 4. A cell's two ends
  // are hashed on its first sample, into registers 7 and 8 under the cell's number in 6.
  private fun cachedStreak(
    pass: Int,
    cellLength: Double,
  ): String {
    val at = "$x/$cellLength+ld(4)"
    val a = signed(hash(pass, "ld(2)", "ld(3)+(ld($STREAK_CELL)+1)*$ROW_CELL_STRIDE"))
    val b = signed(hash(pass, "ld(2)", "ld(3)+(ld($STREAK_CELL)+2)*$ROW_CELL_STRIDE"))
    val readCell = "st($STREAK_CELL,floor(ld(0)));st(7,$a);st(8,$b);st(0,$at)"
    val read = "st(0,$at);if(eq(floor(ld(0)),ld($STREAK_CELL)),0,$readCell)"
    val blend = "st(0,ld(0)-floor(ld(0)));ld(7)+(ld(8)-ld(7))*ld(0)*ld(0)*(3-2*ld(0))"
    return "$read;$blend"
  }

  // VhsSignal.streak on the row in register 3 at the raster sample's x, read through registers 6 and 8.
  private fun inlineStreak(
    pass: Int,
    cellLength: Double,
  ): String {
    val a = signed(hash(pass, "ld(2)", "ld(3)+(floor(ld(6))+1)*$ROW_CELL_STRIDE"))
    val b = signed(hash(pass, "ld(2)", "ld(3)+(floor(ld(6))+2)*$ROW_CELL_STRIDE"))
    val fraction = "(ld(6)-floor(ld(6)))"
    return "st(6,$x/$cellLength+${streakPhase(pass)});st(8,$a);" +
      "st(8,ld(8)+($b-ld(8))*$fraction*$fraction*(3-2*$fraction));ld(8)"
  }

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
    const val HASH_RANGE_INT = 16777216
    const val STREAK_CELL = 6
    const val NO_CELL = -1
  }
}

// The most gblur takes, and the closest it lands to a Gaussian. Its recursive filter is ffmpeg's limit past this.
private const val GBLUR_STEPS = 6
private const val MARGIN_SIGMAS = 4.0
private const val TAPE_FORMATS = "yuv420p|yuva420p"
private const val LUMA_PLANE = 1
private const val CHROMA_PLANES = 6

// gblur's own floor. Below about 0.005 pixels its recursive filter loses its scale and darkens the frame, to a fifth of
// the picture at 0.0005. A smaller blur runs here instead, which changes no pixel.
private const val MIN_SIGMA = 0.01
private const val LUMA_BLACK = 16
private const val LUMA_WHITE = 235
private const val LUMA_CODES = 219
private const val CHROMA_ZERO = 128
private const val CHROMA_CODES = 224
private const val MID_SCALE = 128
private const val GRAIN_REACH = 120.0
private const val MIN_GRAIN_CODES = 0.01
private const val FULL_SCALE = 255
private const val RASTER_WIDTH = 640

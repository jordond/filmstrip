package dev.jordond.filmstrip.effects.filters.tape

import dev.jordond.filmstrip.motion.Easing

/**
 * Where the figures of a [TapeLook] come from when ffmpeg writes them into an expression.
 *
 * A look that holds one strength writes each figure as the number [TapePictureParams] gives it. A look that settles
 * along a [TapePictureRamp] writes each figure as its value at full strength times the level at the frame's tick, which
 * is [TapePictureRamp.levelAt] as an expression. The colour a band takes is the loss at full strength times
 * [TapePictureRamp.colourShare], and an event band fades along the chances [TapePictureParams.trackingChanceFrom] and
 * [TapePictureParams.trackingChanceTo] give.
 *
 * @property params The strengths a constant look draws with, or the strengths at the highest level a settling look
 * reaches, which size what the graph has to cover.
 * @property unit The strengths at full strength, each figure's coefficient on the level.
 * @property ramp How the level moves, or null for a look that holds one strength.
 */
internal class TapePictureFigures(
  val params: TapePictureParams,
  val unit: TapePictureParams,
  val ramp: TapePictureRamp?,
) {
  val moves: Boolean get() = ramp != null

  /**
   * The strengths the noise maps are drawn at, which are the full strength ones when the level moves, since a map holds
   * the noise as a share of its largest and the grade scales it by the level.
   */
  val noise: TapePictureParams get() = if (moves) unit else params

  /**
   * The figure [read] picks off the strengths, as the number it is at the level written in [level].
   */
  fun linear(
    level: String,
    read: TapePictureParams.() -> Double,
  ): String = if (moves) "(${unit.read()}*$level)" else params.read().toString()

  /**
   * The figure [read] picks off the strengths, for one that scales with the square of the level.
   */
  fun squared(
    level: String,
    read: TapePictureParams.() -> Double,
  ): String = if (moves) "(${unit.read()}*($level*$level))" else params.read().toString()

  /**
   * The colour a tracking band takes, as the number it is at the level written in [level]: the loss at full strength
   * times [TapePictureRamp.colourShare] as an expression.
   */
  fun chromaLoss(level: String): String =
    if (moves) {
      "(${unit.trackingChromaLoss}*min(1,$level/${checkNotNull(ramp).colourReference}))"
    } else {
      params.trackingChromaLoss.toString()
    }

  /**
   * [TapePictureRamp.levelAt] as an expression of the tick the expression [tick] reads.
   */
  fun levelAt(tick: String): String {
    val ramp = checkNotNull(ramp) { "A look that holds one strength has no level to write." }
    val progress = "clip(($tick-${ramp.startTick})/${ramp.overTicks},0,1)"
    val eased = pacedExpression(ramp.easing, progress)
    return "(${ramp.from}*(1-$eased)+${ramp.to}*$eased)"
  }

  /**
   * The condition under which a frame at the tick the expression [tick] reads is at the level the ramp starts at.
   */
  fun before(tick: String): String = "lte($tick,${checkNotNull(ramp).startTick})"

  /**
   * The condition under which a frame at the tick the expression [tick] reads is between the two ends of the ramp.
   */
  fun during(tick: String): String = checkNotNull(ramp).let { "gt($tick,${it.startTick})*lt($tick,${it.endTick})" }

  /**
   * The condition under which a frame at the tick the expression [tick] reads is at the level the ramp ends at.
   */
  fun after(tick: String): String = "gte($tick,${checkNotNull(ramp).endTick})"

  companion object {
    /**
     * The figures of a look that holds one strength, drawn with [params].
     */
    fun constant(params: TapePictureParams): TapePictureFigures = TapePictureFigures(params, params, null)
  }
}

/**
 * [Easing.paced] of the progress the expression [progress] reads, which is already within 0 to 1.
 *
 * The expression is evaluated in double where [Easing.paced] is in float.
 */
internal fun pacedExpression(
  easing: Easing,
  progress: String,
): String =
  when (easing) {
    Easing.Linear -> progress
    Easing.EaseIn -> "($progress*$progress)"
    Easing.EaseOut -> "($progress*(2-$progress))"
    Easing.EaseInOut -> "if(lt($progress,0.5),2*$progress*$progress,(4-2*$progress)*$progress-1)"
  }

/**
 * [TapePictureSignal.tickAt] of the frame time the variable [time] holds, as an expression.
 */
internal fun tickExpression(time: String): String =
  "floor((floor($time*$TICK_CLOCK_BASE+$TICK_CLOCK_ROUNDING)+$TICK_CLOCK_ROUNDING)/$TICK_CLOCK_BASE*$TICK_RATE+" +
    "$TICK_ROUNDING+$TICK_MARGIN)"

package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.AudioLevel
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.EnvelopeAnchor
import dev.jordond.filmstrip.edit.EnvelopePoint
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt
import kotlin.time.Duration

// The gain a backend applies, derived once. The planner folds every scope's level into one curve
// here and a backend reads the segments or samples them, never multiplying the scopes again. Four
// backends each folding their own gain off the same KDoc is how one fade ends up three lengths
// long, and a shared curve is something a test can pin where a sentence is not.

/**
 * The gain one clip contributes, as a run of linear segments in the clip's own time.
 *
 * A constant is a single flat segment, so the common case costs one segment and every backend keeps
 * one code path. [gainAt] samples the curve and [constant] is how a backend that can only scale by
 * one number asks whether it may.
 *
 * @property segments The runs the curve is built from, ordered and touching end to start. Always at
 *   least one.
 */
@InternalFilmstripApi
public class ResolvedGain(
  public val segments: List<GainSegment>,
) {
  /**
   * The single gain this curve holds everywhere, or null while it ramps.
   *
   * A backend takes its ramping path when this is null rather than inspecting the segments to find
   * out whether it has to.
   */
  public val constant: Float?
    get() {
      val gain = segments.first().startGain
      return gain.takeIf { segments.all { segment -> segment.startGain == gain && segment.endGain == gain } }
    }

  /**
   * Whether this curve holds one gain for its whole length.
   */
  public val isConstant: Boolean get() = constant != null

  /**
   * The largest gain anywhere on the curve.
   *
   * A linear segment reaches its extremes at its own ends, so the peak always sits on a breakpoint.
   */
  public val peak: Float get() = segments.maxOf { maxOf(it.startGain, it.endGain) }

  /**
   * Where the curve starts, in the clip's own time.
   */
  public val start: Duration get() = segments.first().start

  /**
   * Where the curve ends, in the clip's own time.
   */
  public val end: Duration get() = segments.last().end

  /**
   * The gain at [time], measured in the same clip time the segments are.
   *
   * Before the first segment and after the last the curve holds flat at that end's gain, so a
   * backend may sample outside the range it was built for without special casing it.
   */
  public fun gainAt(time: Duration): Float {
    val first = segments.first()
    if (time <= first.start) return first.startGain
    val last = segments.last()
    if (time >= last.end) return last.endGain
    return (segments.lastOrNull { it.start <= time } ?: first).gainAt(time)
  }

  /**
   * This curve multiplied by [other], over the breakpoints of both.
   *
   * Every breakpoint of both curves is a breakpoint of the product, so between two of them each
   * side is a straight line and their product is a quadratic. A chord across that stretch sits at
   * worst `|da * db| / 4` away from the real curve, where `da` and `db` are how far each side moves
   * across it, and splitting the stretch into `n` chords divides that by `n` squared. Each stretch
   * therefore takes only the chords its own bow needs, down to one where either side barely moves.
   *
   * That is what keeps the fold from exploding. A long envelope crossed with a slow fade moves the
   * fade almost not at all across any one of the envelope's own steps, so those stay single chords
   * instead of every one of them splitting.
   */
  public operator fun times(other: ResolvedGain): ResolvedGain {
    val from = minOf(start, other.start)
    val to = maxOf(end, other.end)
    if (isConstant && other.isConstant) return constant(constant!! * other.constant!!, from, to)

    val breaks =
      buildSet {
        add(from)
        add(to)
        segments.forEach { add(it.start) }
        segments.forEach { add(it.end) }
        other.segments.forEach { add(it.start) }
        other.segments.forEach { add(it.end) }
      }.filter { it in from..to }.sorted()
    if (breaks.size < 2) return constant(gainAt(from) * other.gainAt(from), from, to)

    val product = mutableListOf<GainSegment>()
    for (index in 0 until breaks.lastIndex) {
      val left = breaks[index]
      val right = breaks[index + 1]
      val steps = chordsAcross(left, right, other)
      var cursor = left
      for (step in 1..steps) {
        val next = if (step == steps) right else left + (right - left) * (step.toDouble() / steps)
        product +=
          GainSegment(
            start = cursor,
            end = next,
            startGain = gainAt(cursor) * other.gainAt(cursor),
            endGain = gainAt(next) * other.gainAt(next),
          )
        cursor = next
      }
    }
    return ResolvedGain(product)
  }

  /**
   * The stretch of this curve running [length] from [from], re-based so it starts at zero.
   *
   * This is how a track's curve is read against one clip on it: the clip's slot in the track is cut
   * out and handed back in the clip's own time, ready to multiply by the clip's own curve. Slicing
   * a linear segment is exact, so nothing is lost here.
   */
  public fun window(
    from: Duration,
    length: Duration,
  ): ResolvedGain {
    val to = from + length
    val breaks =
      buildSet {
        add(from)
        add(to)
        segments.forEach { if (it.start > from && it.start < to) add(it.start) }
        segments.forEach { if (it.end > from && it.end < to) add(it.end) }
      }.sorted()
    if (breaks.size < 2) return constant(gainAt(from), Duration.ZERO, length)
    return ResolvedGain(
      (0 until breaks.lastIndex).map { index ->
        val left = breaks[index]
        val right = breaks[index + 1]
        GainSegment(left - from, right - from, gainAt(left), gainAt(right))
      },
    )
  }

  /**
   * How many chords the product of this curve and [other] needs across `[from, to]` to stay within
   * [PRODUCT_TOLERANCE] of the real quadratic.
   *
   * One whenever either side holds still, since a line times a constant is a line and no chord is
   * an approximation at all.
   */
  private fun chordsAcross(
    from: Duration,
    to: Duration,
    other: ResolvedGain,
  ): Int {
    val bow = abs(gainAt(to) - gainAt(from)) * abs(other.gainAt(to) - other.gainAt(from)) / 4f
    if (bow <= PRODUCT_TOLERANCE) return 1
    return ceil(sqrt(bow / PRODUCT_TOLERANCE)).toInt().coerceIn(1, MAX_PRODUCT_CHORDS)
  }

  public companion object {
    /**
     * How far a folded curve may sit from the real product, in gain.
     *
     * A thousandth of full scale is under a hundredth of a decibel, which no listener and no
     * backend's own quantisation will find.
     */
    public const val PRODUCT_TOLERANCE: Float = 0.001f

    /**
     * The most chords one stretch of a fold is ever split into.
     *
     * Two ramps that each cross the whole of unity bow furthest, and that worst case needs sixteen.
     * The cap is what stops a pathological curve handing a backend an unbounded segment list.
     */
    public const val MAX_PRODUCT_CHORDS: Int = 16

    /**
     * A curve holding [gain] for the whole of `[start, end]`.
     */
    public fun constant(
      gain: Float,
      start: Duration,
      end: Duration,
    ): ResolvedGain = ResolvedGain(listOf(GainSegment(start, end, gain, gain)))
  }
}

/**
 * One linear run of a [ResolvedGain], ramping from [startGain] to [endGain] across `[start, end]`.
 *
 * A zero length segment is a step, which is what two envelope points pinned at the same time mean.
 */
@InternalFilmstripApi
public class GainSegment(
  public val start: Duration,
  public val end: Duration,
  public val startGain: Float,
  public val endGain: Float,
) {
  /**
   * Whether this segment holds one gain rather than ramping.
   */
  public val isFlat: Boolean get() = startGain == endGain

  /**
   * The gain at [time], clamped to this segment's own ends.
   */
  public fun gainAt(time: Duration): Float {
    val span = end - start
    if (span <= Duration.ZERO) return endGain
    val progress = ((time - start) / span).coerceIn(0.0, 1.0).toFloat()
    return startGain + (endGain - startGain) * progress
  }
}

/**
 * The curve this level contributes over a scope [length] long.
 *
 * [AudioLevel.Inherit] is a flat one, so multiplying a child's curve by its parent's leaves the
 * parent's untouched, which is what makes the fold a plain product at every scope.
 */
@InternalFilmstripApi
public fun AudioLevel.curveOver(length: Duration): ResolvedGain =
  when (this) {
    is AudioLevel.Inherit -> ResolvedGain.constant(1f, Duration.ZERO, length)
    is AudioLevel.Mute -> ResolvedGain.constant(0f, Duration.ZERO, length)
    is AudioLevel.Volume -> ResolvedGain.constant(gain, Duration.ZERO, length)
    is AudioLevel.Envelope -> envelopeCurve(points.resolvedOver(length), length)
  }

/**
 * The curve this composition level contributes over a scope [length] long.
 *
 * A composition has no envelope of its own, so this is always flat.
 */
@InternalFilmstripApi
public fun AudioSpec.curveOver(length: Duration): ResolvedGain =
  when (this) {
    is AudioSpec.Keep, is AudioSpec.AudioOnly -> ResolvedGain.constant(1f, Duration.ZERO, length)
    is AudioSpec.Mute, is AudioSpec.Remove -> ResolvedGain.constant(0f, Duration.ZERO, length)
    is AudioSpec.Volume -> ResolvedGain.constant(gain, Duration.ZERO, length)
  }

/**
 * Whether every point sits inside `[0, length]` once anchored, with times that do not run backwards
 * and gains that are not negative.
 *
 * The planner refuses a composition this returns false for, which is where an envelope read off
 * disk rather than built by the DSL is checked.
 */
@InternalFilmstripApi
public fun AudioLevel.Envelope.isValidOver(length: Duration): Boolean {
  if (points.any { it.gain < 0f || it.gain.isNaN() || it.at < Duration.ZERO || it.at > length }) return false
  val times = points.resolvedOver(length).map { it.first }
  return times == times.sorted()
}

// Each point's anchored time paired with its gain, in the order the points were written. An End
// anchored point is measured back from the scope's own end, which is the only place the length a
// fade out sits at is known.
private fun List<EnvelopePoint>.resolvedOver(length: Duration): List<Pair<Duration, Float>> =
  map { point ->
    val at =
      when (point.from) {
        EnvelopeAnchor.Start -> point.at
        EnvelopeAnchor.End -> length - point.at
      }
    at.coerceIn(Duration.ZERO, length) to point.gain
  }

// A curve spanning [0, length] that ramps between the pinned points and holds flat outside them.
private fun envelopeCurve(
  points: List<Pair<Duration, Float>>,
  length: Duration,
): ResolvedGain {
  if (points.isEmpty()) return ResolvedGain.constant(1f, Duration.ZERO, length)
  val pinned = points.sortedBy { it.first }
  val segments = mutableListOf<GainSegment>()
  val (firstAt, firstGain) = pinned.first()
  if (firstAt > Duration.ZERO) segments += GainSegment(Duration.ZERO, firstAt, firstGain, firstGain)
  pinned.zipWithNext { (leftAt, leftGain), (rightAt, rightGain) ->
    segments += GainSegment(leftAt, rightAt, leftGain, rightGain)
  }
  val (lastAt, lastGain) = pinned.last()
  if (lastAt < length) segments += GainSegment(lastAt, length, lastGain, lastGain)
  return ResolvedGain(segments.ifEmpty { listOf(GainSegment(Duration.ZERO, length, firstGain, firstGain)) })
}

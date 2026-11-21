package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.edit.AudioLevel
import dev.jordond.filmstrip.edit.CLIP_SPEED_RANGE
import dev.jordond.filmstrip.edit.EnvelopeAnchor
import dev.jordond.filmstrip.edit.EnvelopePoint
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The kinds of level a clip can carry, one for each [AudioLevel].
 */
public enum class ClipAudioMode {
  Inherit,
  Mute,
  Volume,
  Envelope,
}

/**
 * Which kind of level the clip carries. Setting the kind it already has keeps the gain or the points it holds,
 * and any other starts at a level that changes nothing.
 */
public var ClipEdit.audioMode: ClipAudioMode
  get() =
    when (audio) {
      AudioLevel.Inherit -> ClipAudioMode.Inherit
      AudioLevel.Mute -> ClipAudioMode.Mute
      is AudioLevel.Volume -> ClipAudioMode.Volume
      is AudioLevel.Envelope -> ClipAudioMode.Envelope
    }
  set(mode) {
    audio =
      when (mode) {
        ClipAudioMode.Inherit -> AudioLevel.Inherit
        ClipAudioMode.Mute -> AudioLevel.Mute
        ClipAudioMode.Volume -> audio as? AudioLevel.Volume ?: AudioLevel.Volume(1f)
        ClipAudioMode.Envelope -> audio as? AudioLevel.Envelope ?: AudioLevel.Envelope(emptyList())
      }
  }

/**
 * The points of the clip's envelope, or none while its level is not an envelope.
 */
public val ClipEdit.envelope: List<EnvelopePoint>
  get() = (audio as? AudioLevel.Envelope)?.points.orEmpty()

/**
 * Appends a point at full gain, one second after the last point measured from the start, or at the start when
 * there is none. The level becomes an envelope when it was not one.
 */
public fun ClipEdit.addEnvelopePoint() {
  val last = envelope.lastOrNull { it.from == EnvelopeAnchor.Start }
  val at = if (last == null) Duration.ZERO else last.at + ENVELOPE_STEP
  audio = AudioLevel.Envelope(envelope + EnvelopePoint(at, 1f))
}

/**
 * Replaces the envelope point at [index] with [point].
 */
public fun ClipEdit.setEnvelopePoint(
  index: Int,
  point: EnvelopePoint,
) {
  audio = AudioLevel.Envelope(envelope.toMutableList().also { it[index] = point })
}

/**
 * Drops the envelope point at [index].
 */
public fun ClipEdit.removeEnvelopePoint(index: Int) {
  audio = AudioLevel.Envelope(envelope.filterIndexed { place, _ -> place != index })
}

/**
 * An envelope that dips under whatever else is playing and comes back before the clip ends.
 */
public val DUCK_ENVELOPE: List<EnvelopePoint> =
  listOf(
    EnvelopePoint(Duration.ZERO, 1f),
    EnvelopePoint(500.milliseconds, DUCK_GAIN),
    EnvelopePoint(500.milliseconds, DUCK_GAIN, EnvelopeAnchor.End),
    EnvelopePoint(Duration.ZERO, 1f, EnvelopeAnchor.End),
  )

/**
 * An envelope that rises from silence over the first second and falls back to it over the last.
 */
public val FADE_ENVELOPE: List<EnvelopePoint> =
  listOf(
    EnvelopePoint(Duration.ZERO, 0f),
    EnvelopePoint(1.seconds, 1f),
    EnvelopePoint(1.seconds, 1f, EnvelopeAnchor.End),
    EnvelopePoint(Duration.ZERO, 0f, EnvelopeAnchor.End),
  )

/**
 * The clip speed a slider at [fraction] of its travel stands for.
 *
 * The slider covers [CLIP_SPEED_RANGE] on a log scale, so the middle is the shot's own pace and each side
 * doubles or halves evenly. The speed is rounded to a hundredth and held inside the range.
 */
public fun speedOfSlider(fraction: Float): Double {
  val span = CLIP_SPEED_RANGE.endInclusive / CLIP_SPEED_RANGE.start
  val speed = CLIP_SPEED_RANGE.start * span.pow(fraction.coerceIn(0f, 1f).toDouble())
  return ((speed * SPEED_STEPS).roundToLong() / SPEED_STEPS).coerceIn(CLIP_SPEED_RANGE)
}

/**
 * Where a slider sits for [speed], the reverse of [speedOfSlider].
 */
public fun sliderOfSpeed(speed: Double): Float {
  val span = CLIP_SPEED_RANGE.endInclusive / CLIP_SPEED_RANGE.start
  return (ln(speed.coerceIn(CLIP_SPEED_RANGE) / CLIP_SPEED_RANGE.start) / ln(span)).toFloat()
}

private val ENVELOPE_STEP = 1.seconds
private const val DUCK_GAIN = 0.3f
private const val SPEED_STEPS = 100.0

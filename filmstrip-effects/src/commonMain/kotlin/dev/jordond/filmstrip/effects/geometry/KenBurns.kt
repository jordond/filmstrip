package dev.jordond.filmstrip.effects.geometry

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.ExperimentalFilmstripApi
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.EffectScope
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.motion.Easing
import dev.jordond.filmstrip.motion.paced
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration

/**
 * Pan and zoom across the frame over the clip's span.
 *
 * [from] and [to] are the regions filling the frame at the start of the clip and at its end, and
 * every frame between them shows the region [easing] paces to. Both are fractions of the frame
 * entering this effect, so a crop declared on the same clip has already chosen the framing this
 * travels within.
 *
 * The region is resampled to fill the frame it was measured against, so the frame keeps the size it
 * arrived at and a rect whose shape differs from the frame's stretches to fill it.
 *
 * Valid on a clip alone. Its result depends on where a frame sits in the clip's span, so it cannot
 * be fanned onto a run of clips the way a track effect is, and a plan refuses it anywhere else.
 *
 * @property from The region visible at the start of the clip.
 * @property to The region visible at the end.
 * @property easing How the motion is paced between them.
 */
@Serializable
@SerialName(EffectIds.KEN_BURNS)
@Poko
@ExperimentalFilmstripApi
public class KenBurns(
  public val from: NormalizedRect,
  public val to: NormalizedRect,
  public val easing: Easing = Easing.EaseInOut,
) : EffectSpec {
  override val id: String get() = EffectIds.KEN_BURNS

  override val stage: EffectStage get() = EffectStage.Geometry

  override val scope: EffectScope get() = EffectScope.ClipOnly
}

/**
 * Pan and zoom from [from] to [to] over the clip's span.
 *
 * Valid on a clip alone, since the region it shows depends on where the frame sits inside that
 * clip.
 */
@ExperimentalFilmstripApi
public fun EffectsBuilder.kenBurns(
  from: NormalizedRect,
  to: NormalizedRect,
  easing: Easing = Easing.EaseInOut,
): EffectsBuilder = add(KenBurns(from, to, easing))

/**
 * Computes the region this pan shows at [time], as a fraction of the frame entering it.
 *
 * Every backend that draws a pan reads its region from here rather than interpolating one of its
 * own, which is what keeps two engines from putting the same clip in two different places at the
 * same composition time.
 *
 * [time] is composition-relative on every backend, so [span] is the clip's own slot on the
 * composition timeline. A time before the span holds [KenBurns.from] and a time after it holds
 * [KenBurns.to], so a frame landing a rounding error outside the slot draws the end it is nearest.
 *
 * @param time Where the frame sits on the composition's timeline.
 * @param span The clip's slot on that timeline.
 * @return The region that fills the frame at [time].
 */
@ExperimentalFilmstripApi
public fun KenBurns.regionAt(
  time: Duration,
  span: TimeRange,
): NormalizedRect {
  val eased = easing.paced(span.fractionAt(time))
  return NormalizedRect(
    left = interpolate(from.left, to.left, eased),
    top = interpolate(from.top, to.top, eased),
    right = interpolate(from.right, to.right, eased),
    bottom = interpolate(from.bottom, to.bottom, eased),
  )
}

// A span with no measurable length has no travel to be partway through, which is the trim that
// collapsed to a single frame and the open-ended range nothing has probed yet.
private fun TimeRange.fractionAt(time: Duration): Float {
  val length = duration ?: return 0f
  if (length <= Duration.ZERO) return 0f
  return ((time - start) / length).toFloat().coerceIn(0f, 1f)
}

private fun interpolate(
  from: Float,
  to: Float,
  fraction: Float,
): Float = from + (to - from) * fraction

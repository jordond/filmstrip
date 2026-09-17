package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Size
import kotlin.math.roundToInt
import kotlin.time.Duration

/**
 * The composition time range this overlay is drawn over.
 *
 * [OverlayEffect.visibleDuring] clipped to [span], or [span] itself when no window was named. An
 * open end on either side takes the other's, and the answer is open only when both are. A window
 * falling entirely outside [span] answers a range of no length, which contains no frame.
 *
 * Derive it once per pipeline and hand it to [frameWithin] rather than calling [frameAt] per frame.
 *
 * @param span The composition time range the frames entering this effect fall in.
 * @return The range the overlay is drawn over, in composition time.
 */
@InternalFilmstripApi
public fun OverlayEffect.runWithin(span: TimeRange): TimeRange {
  val window = visibleDuring ?: return span
  val windowEnd = window.endExclusive
  val spanEnd = span.endExclusive
  val end =
    when {
      windowEnd == null -> spanEnd
      spanEnd == null -> windowEnd
      else -> if (windowEnd < spanEnd) windowEnd else spanEnd
    }

  val start = if (window.start > span.start) window.start else span.start
  return TimeRange(if (end != null && start > end) end else start, end)
}

/**
 * Samples this overlay at one output frame of a run already derived by [runWithin].
 *
 * Allocates nothing beyond what it answers, so a backend may call it on its render thread for every
 * frame it draws.
 *
 * An overlay carrying no [OverlayEffect.visibleDuring] is drawn on every frame it is handed. The
 * run is the planner's own half-open slot and each backend counts [compositionTime] its own way, so
 * a timestamp landing on either bound would otherwise drop a frame nothing asked to hide. One
 * carrying a window is gated by that window and answers null outside it.
 *
 * [OverlayTime.elapsed] and [OverlayTime.remaining] are held at zero or more, so a timestamp
 * drifting off the run reads as the nearer end of it rather than as negative time.
 *
 * The answer already carries [ImageOverlay.opacity], with the opacity held in `0f..1f` and the
 * scale at zero or more, so a backend draws what it is handed without multiplying anything itself.
 * A NaN or an infinity from a caller's animation reads as the value that changes nothing, so a
 * runaway scale cannot grow the drawn size past what a backend can hold.
 *
 * @param run The range the overlay is drawn over, in composition time.
 * @param compositionTime Where the frame sits on the composition's timeline.
 * @return What to draw the overlay with, or null when it is not drawn on that frame.
 */
@InternalFilmstripApi
public fun OverlayEffect.frameWithin(
  run: TimeRange,
  compositionTime: Duration,
): OverlayFrame? {
  if (visibleDuring != null && compositionTime !in run) return null

  val authored = ((this as? ImageOverlay)?.opacity ?: 1f).orUnchanged(1f)
  val animation = animation
  if (animation == null) {
    val still = authored.coerceIn(0f, 1f)
    return if (still == 1f) OverlayFrame.Identity else OverlayFrame(opacity = still)
  }

  val since = compositionTime - run.start
  val elapsed = if (since < Duration.ZERO) Duration.ZERO else since
  val end = run.endExclusive
  val remaining =
    when {
      end == null -> Duration.INFINITE
      end <= compositionTime -> Duration.ZERO
      else -> end - compositionTime
    }
  val sampled = animation.frameAt(OverlayTime(elapsed, remaining, compositionTime))

  val opacity = (authored * sampled.opacity.orUnchanged(1f)).coerceIn(0f, 1f)
  val scale = sampled.scale.orUnchanged(1f).coerceAtLeast(0f)
  val offset = sampled.offset
  if (opacity == sampled.opacity && scale == sampled.scale && offset.x.isFinite() && offset.y.isFinite()) {
    return sampled
  }

  return OverlayFrame(opacity, OverlayOffset(offset.x.orUnchanged(0f), offset.y.orUnchanged(0f)), scale)
}

/**
 * Samples this overlay at one output frame, deriving its run from [span].
 *
 * [compositionTime] is read against the same clock [Attributes.span] is, which is the one every
 * backend hands an effect. The sampling is [frameWithin]'s, and the run is [runWithin]'s. A caller
 * drawing frame after frame derives the run once and calls [frameWithin] instead.
 *
 * @param compositionTime Where the frame sits on the composition's timeline.
 * @param span The composition time range the frames entering this effect fall in.
 * @return What to draw the overlay with, or null when it is not drawn on that frame.
 */
@InternalFilmstripApi
public fun OverlayEffect.frameAt(
  compositionTime: Duration,
  span: TimeRange,
): OverlayFrame? = frameWithin(runWithin(span), compositionTime)

/**
 * Moves and resizes this placement by what an animation answered for one frame.
 *
 * The scale multiplies [OverlayPlacement.size] and the offset adds to
 * [OverlayPlacement.frameAnchor], so the overlay grows away from whichever point it is pinned by
 * and a corner watermark keeps its authored margin as it grows. Each side is held at one pixel or
 * more, since no backend draws an overlay of no extent.
 *
 * @param frame What the animation answered for this output frame.
 * @return Where to draw the overlay on that frame.
 */
@InternalFilmstripApi
public fun OverlayPlacement.animatedBy(frame: OverlayFrame): OverlayPlacement {
  if (frame.scale == 1f && frame.offset.x == 0f && frame.offset.y == 0f) return this

  return OverlayPlacement(
    size =
      Size(
        (size.width * frame.scale).roundToInt().coerceAtLeast(1),
        (size.height * frame.scale).roundToInt().coerceAtLeast(1),
      ),
    overlayAnchor = overlayAnchor,
    frameAnchor = Anchor(frameAnchor.x + frame.offset.x, frameAnchor.y + frame.offset.y),
  )
}

// A NaN passes through both coerceIn and coerceAtLeast and an infinity through coerceAtLeast, so
// neither is clamped into anything a backend can draw. Both read as the value that leaves the
// property as it was authored.
private fun Float.orUnchanged(unchanged: Float): Float = if (isFinite()) this else unchanged

package dev.jordond.filmstrip.effects.overlay

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import dev.drewhamilton.poko.Poko
import kotlin.math.min
import kotlin.time.Duration

/**
 * How an overlay is drawn at one instant.
 *
 * Sampled once per output frame over the run the overlay is drawn across, so the opacity, offset
 * and scale it answers may differ on every frame. It is a `fun interface`, so a lambda is the
 * shorthand for a curve the built-in helpers do not cover.
 */
@Stable
public fun interface OverlayAnimation {
  /**
   * Answers how the overlay is drawn at [time].
   *
   * @param time Where the frame sits inside the run the overlay is drawn over.
   * @return What to draw the overlay with on that frame.
   */
  public fun frameAt(time: OverlayTime): OverlayFrame
}

/**
 * Where one frame sits inside the run an overlay is drawn over.
 *
 * The run is the one [runWithin] derives, so [elapsed] and [remaining] count from the first frame
 * the overlay is drawn on and to the last rather than from the start of the composition.
 *
 * @property elapsed How long the overlay has been drawn for, never negative.
 * @property remaining How much of the run is left, never negative, or [Duration.INFINITE] while the
 *   run has no end. A ramp measured off an infinite remainder never begins, so [fadeOut] holds full
 *   opacity and [slideOut] holds the authored position.
 * @property composition Where the frame sits on the composition's own timeline.
 */
@Poko
@Immutable
public class OverlayTime(
  public val elapsed: Duration,
  public val remaining: Duration,
  public val composition: Duration,
)

/**
 * The three things an animation may change about one drawn frame.
 *
 * @property opacity Alpha the overlay is drawn with, in `0f..1f`.
 * @property offset Displacement from where the overlay would otherwise sit, as a fraction of the
 *   frame it is drawn on.
 * @property scale Multiplier on the overlay's drawn size, at least zero. It grows the overlay away
 *   from the point it is anchored by, and on a text overlay it resamples the raster rather than
 *   re-laying the glyphs.
 */
@Poko
@Immutable
public class OverlayFrame(
  public val opacity: Float = 1f,
  public val offset: OverlayOffset = OverlayOffset.Zero,
  public val scale: Float = 1f,
) {
  public companion object {
    /**
     * The overlay drawn exactly where and how it was authored.
     */
    public val Identity: OverlayFrame = OverlayFrame()
  }
}

/**
 * A displacement from where an overlay would otherwise sit, as a fraction of the frame it is drawn
 * on.
 *
 * Positive [x] moves the overlay towards the end edge and positive [y] moves it down, the same
 * origin every other normalised measurement in filmstrip uses. An offset large enough to carry the
 * overlay off the frame is allowed, and the part that leaves the frame is not drawn.
 *
 * @property x Horizontal displacement as a fraction of the frame width.
 * @property y Vertical displacement as a fraction of the frame height.
 */
@Poko
@Immutable
public class OverlayOffset(
  public val x: Float,
  public val y: Float,
) {
  public companion object {
    /**
     * No displacement, which leaves the overlay where it was authored.
     */
    public val Zero: OverlayOffset = OverlayOffset(0f, 0f)
  }
}

/**
 * Ramps the overlay's opacity up from nothing over [duration], counted from its first drawn frame.
 *
 * The ramp is linear. A duration of zero or less draws the overlay at full opacity from the start.
 *
 * @param duration How long the overlay takes to reach full opacity.
 * @return An animation fading the overlay in.
 */
public fun fadeIn(duration: Duration): OverlayAnimation = FadeAnimation(duration, Duration.ZERO)

/**
 * Ramps the overlay's opacity down to nothing over [duration], counted back from its last drawn
 * frame.
 *
 * The ramp is linear. A run with no end never reaches it, so the overlay holds full opacity.
 *
 * @param duration How long the overlay takes to fade away.
 * @return An animation fading the overlay out.
 */
public fun fadeOut(duration: Duration): OverlayAnimation = FadeAnimation(Duration.ZERO, duration)

/**
 * Fades the overlay in over [inDuration] and back out over [outDuration].
 *
 * The two ramps are measured from opposite ends of the run, so a run shorter than the pair takes
 * the lower of the two opacities rather than the product. A duration of zero or less on either side
 * skips that side's ramp.
 *
 * @param inDuration How long the overlay takes to reach full opacity.
 * @param outDuration How long the overlay takes to fade away.
 * @return An animation fading the overlay in and out.
 */
public fun fade(
  inDuration: Duration,
  outDuration: Duration,
): OverlayAnimation = FadeAnimation(inDuration, outDuration)

/**
 * Slides the overlay from [from] to where it was authored over [duration], counted from its first
 * drawn frame.
 *
 * The travel is linear and the offset is a fraction of the frame, so a start of `-0.2f` on x brings
 * the overlay in from a fifth of the frame width off its authored position. A duration of zero or
 * less draws the overlay at its authored position from its first frame.
 *
 * @param from Where the overlay starts, relative to where it was authored.
 * @param duration How long the overlay takes to arrive.
 * @return An animation sliding the overlay in.
 */
public fun slideIn(
  from: OverlayOffset,
  duration: Duration,
): OverlayAnimation = SlideAnimation(from, duration, leading = true)

/**
 * Slides the overlay from where it was authored to [to] over [duration], counted back from its last
 * drawn frame.
 *
 * The travel is linear. A run with no end never reaches it, and a duration of zero or less skips
 * it, so the overlay holds its authored position in both cases.
 *
 * @param to Where the overlay ends, relative to where it was authored.
 * @param duration How long the overlay takes to leave.
 * @return An animation sliding the overlay out.
 */
public fun slideOut(
  to: OverlayOffset,
  duration: Duration,
): OverlayAnimation = SlideAnimation(to, duration, leading = false)

/**
 * Runs both animations on every frame and merges what they answer.
 *
 * Opacities and scales multiply, offsets add, so a fade combined with a slide carries both. The
 * result compares by value exactly as far as its two operands do: two built-in helpers combine into
 * something structurally equal, and anything touching a caller's lambda compares by identity.
 *
 * @param other The animation to run alongside this one.
 * @return An animation carrying both.
 */
public operator fun OverlayAnimation.plus(other: OverlayAnimation): OverlayAnimation = CombinedAnimation(this, other)

@Poko
@Immutable
private class FadeAnimation(
  val inDuration: Duration,
  val outDuration: Duration,
) : OverlayAnimation {
  override fun frameAt(time: OverlayTime): OverlayFrame =
    OverlayFrame(opacity = min(ramp(time.elapsed, inDuration), ramp(time.remaining, outDuration)))
}

@Poko
@Immutable
private class SlideAnimation(
  val offset: OverlayOffset,
  val duration: Duration,
  val leading: Boolean,
) : OverlayAnimation {
  override fun frameAt(time: OverlayTime): OverlayFrame {
    val travelled = ramp(if (leading) time.elapsed else time.remaining, duration)
    val left = 1f - travelled
    return OverlayFrame(offset = OverlayOffset(offset.x * left, offset.y * left))
  }
}

@Poko
@Immutable
private class CombinedAnimation(
  val first: OverlayAnimation,
  val second: OverlayAnimation,
) : OverlayAnimation {
  override fun frameAt(time: OverlayTime): OverlayFrame {
    val a = first.frameAt(time)
    val b = second.frameAt(time)
    return OverlayFrame(
      opacity = a.opacity * b.opacity,
      offset = OverlayOffset(a.offset.x + b.offset.x, a.offset.y + b.offset.y),
      scale = a.scale * b.scale,
    )
  }
}

// How far a linear ramp of length [over] has run at [position], in 0f..1f. A ramp of no length and
// an infinite position both read as fully run, an infinite length as not yet started, so neither
// division by zero nor infinity over infinity reaches the caller as a NaN.
private fun ramp(
  position: Duration,
  over: Duration,
): Float =
  when {
    over <= Duration.ZERO -> 1f
    position.isInfinite() -> 1f
    over.isInfinite() -> 0f
    else -> (position / over).toFloat().coerceIn(0f, 1f)
  }

package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.WINDOW_CLOCK
import java.util.Locale

/**
 * One output frame of an animated overlay's drawn range, as the shared sampler answers it.
 *
 * @property index The frame's place on the branch's own clock, counted from the slot the span opens on.
 * @property frame What [OverlayEffect.frameWithin] answers for the frame.
 * @property drawn Where [drawnRect] puts the placement [frame] moves.
 */
internal class SampledFrame(
  val index: Int,
  val frame: OverlayFrame,
  val drawn: PixelRect,
)

/**
 * What an animated overlay's drawn range samples to, once per output frame.
 */
internal sealed interface Sampling {
  /**
   * Nothing to drive. The overlay holds still, or its drawn range reaches no output frame.
   */
  object Still : Sampling

  /**
   * The animation cannot be sampled here.
   *
   * @property message Why.
   */
  class Refused(
    val message: String,
  ) : Sampling

  /**
   * Every output frame the sampler draws on, in order.
   *
   * @property frames The frames, never empty.
   */
  class Sampled(
    val frames: List<SampledFrame>,
  ) : Sampling
}

// Why this overlay's animation cannot be driven over [drawnRange] whatever its picture holds, or null when it can or
// has none. It needs no read of the picture, so it runs ahead of one.
internal fun OverlayEffect.animationRefusal(
  attributes: Attributes,
  drawnRange: TimeRange,
): String? =
  when {
    animation == null -> null
    attributes.gridRate == null -> ANIMATION_NO_GRID
    drawnRange.endExclusive == null -> ANIMATION_NO_END
    else -> null
  }

/**
 * Samples this picture's animation once per output frame of [drawnRange].
 *
 * Each frame's placement is the still placement moved by what [OverlayEffect.frameWithin] answers at the frame's slot,
 * and [drawnRect] rounds it to whole pixels. A frame the sampler draws nothing on is left out, so the value before it
 * carries on across it.
 */
internal fun PlacedPicture.sampledFrames(
  attributes: Attributes,
  drawnRange: TimeRange,
): Sampling {
  if (effect.animation == null) return Sampling.Still
  val rate = attributes.gridRate ?: return Sampling.Refused(ANIMATION_NO_GRID)
  val end = drawnRange.endExclusive ?: return Sampling.Refused(ANIMATION_NO_END)
  val span = attributes.span
  val firstSlot = outputSlotOf(span.start, rate)

  val frames =
    (frameIndexAt(drawnRange.start, span, rate) until frameIndexAt(end, span, rate)).mapNotNull { index ->
      effect.frameWithin(drawnRange, outputSlotTime(firstSlot + index, rate))?.let { frame ->
        SampledFrame(index, frame, drawnRect(placement.animatedBy(frame), attributes.inputSize))
      }
    }
  // A drawn range reaching no output frame, which is a window falling outside the span or one shorter than a frame.
  // The gate on the branch already draws nothing there.
  return if (frames.isEmpty()) Sampling.Still else Sampling.Sampled(frames)
}

/**
 * One property of an animated overlay across its frames, as the runs it holds one value over.
 *
 * Each run opens on the frame index its value starts on and lasts until the next one opens. The first run also covers
 * every frame before it and the last every frame after it, which the gate on the branch hides anyway.
 *
 * @property runs The runs in order, never empty.
 */
internal class FrameRuns(
  val runs: List<FrameRun>,
) {
  /**
   * Whether the property holds one value on every frame.
   */
  val held: Boolean get() = runs.size == 1

  /**
   * The value as a filter expression keyed on [clock], or the one value it holds as a literal.
   *
   * [clock] is evaluated once into the expression's first variable and every run is found by a balanced search over
   * where the runs open, so the expression is as deep as the log of how many runs there are. Each frame reads the value
   * sampled for it exactly.
   */
  fun on(clock: String): String {
    if (held) return runs.single().value
    return buildString {
      append("st(0,").append(clock).append(");")
      appendRuns(0, runs.size)
    }
  }

  private fun StringBuilder.appendRuns(
    from: Int,
    until: Int,
  ) {
    if (until - from == 1) {
      append(runs[from].value)
      return
    }
    val middle = (from + until) / 2
    append("if(lt(ld(0),").append(runs[middle].from).append("),")
    appendRuns(from, middle)
    append(',')
    appendRuns(middle, until)
    append(')')
  }
}

/**
 * A run of [FrameRuns], opening on the frame index [from] and holding [value].
 *
 * @property from The first frame index the run covers.
 * @property value The value as the graph writes it.
 */
internal class FrameRun(
  val from: Int,
  val value: String,
)

/**
 * The runs [value] reads off these frames, each opening where the value moves.
 */
internal fun List<SampledFrame>.runsOf(value: (SampledFrame) -> String): FrameRuns {
  val runs = mutableListOf<FrameRun>()
  forEach { sampled ->
    val written = value(sampled)
    if (runs.lastOrNull()?.value != written) runs += FrameRun(sampled.index, written)
  }
  return FrameRuns(runs)
}

/**
 * The opacity of [frame] as the graph writes it, to six decimals.
 */
internal fun opacityValue(frame: OverlayFrame): String = String.format(Locale.ROOT, OPACITY_FORMAT, frame.opacity)

/**
 * The frame index a filter on an overlay's own branch reads off its `t`, at [rate] frames a second.
 *
 * The branch's frames sit on the output slots from the span's start in an export, and from where the scrub lands in a
 * preview, so the index is the one the sampler drew for. `t` times the rate lands just off a whole number, so it is
 * rounded.
 */
internal fun branchFrameClock(rate: Int): String = "round(t*$rate)"

/**
 * The frame index `geq` reads on an overlay's own branch, which is [branchFrameClock] off the filter's `T`.
 */
internal fun branchPlaneClock(rate: Int): String = "round(T*$rate)"

/**
 * The frame index `overlay` reads off the frame it merges onto, at [rate] frames a second.
 *
 * That frame's clock restarts where a preview window opens, so the window's own slot, read from [WINDOW_CLOCK], is
 * added back. On an export the window opens at zero and adds nothing.
 */
internal fun mergeFrameClock(rate: Int): String = "round(t*$rate)+round($WINDOW_CLOCK*$rate)"

/**
 * Six decimals, which is where an opacity stops moving a code at sixteen bits.
 */
internal const val OPACITY_FORMAT = "%.6f"

internal const val ANIMATION_NO_GRID =
  "An animated overlay is sampled once per output frame, and this composition resolved no frame " +
    "rate to sample it onto. Give the export a frame rate, or drop the animation to draw the " +
    "overlay still."

internal const val ANIMATION_NO_END =
  "An animated overlay is sampled across the range it is drawn over, and this composition names no " +
    "end for that range. Give the export a duration, or drop the animation to draw the overlay still."

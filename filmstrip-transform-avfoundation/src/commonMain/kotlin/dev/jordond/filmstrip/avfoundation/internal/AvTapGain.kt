package dev.jordond.filmstrip.avfoundation.internal

import dev.jordond.filmstrip.transform.internal.audio.timelineFrameOf
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedGain

/**
 * A clip's gain as the breakpoints a tap reads it from, in frames from the start of the clip's span.
 *
 * Each segment of the curve is a straight line between two breakpoints, the same line a volume ramp over that
 * segment draws. Before the first breakpoint and after the last the level holds.
 */
internal class GainCurve(
  private val from: LongArray,
  private val to: LongArray,
  private val fromGain: FloatArray,
  private val toGain: FloatArray,
) {
  /**
   * Scales the first [frames] frames of [block], the first of which sits [opens] frames into the span.
   */
  fun apply(
    block: FloatArray,
    frames: Int,
    channels: Int,
    opens: Long,
  ) {
    var segment = 0
    for (frame in 0 until frames) {
      val at = opens + frame
      while (segment < from.lastIndex && at >= to[segment]) segment++
      val gain = gainAt(segment, at)
      if (gain != 1f) for (channel in 0 until channels) block[frame * channels + channel] *= gain
    }
  }

  private fun gainAt(
    segment: Int,
    at: Long,
  ): Float {
    val start = from[segment]
    val end = to[segment]
    return when {
      at <= start -> fromGain[segment]
      at >= end -> toGain[segment]
      else -> fromGain[segment] + (toGain[segment] - fromGain[segment]) * ((at - start).toFloat() / (end - start))
    }
  }

  companion object {
    fun of(
      gain: ResolvedGain,
      sampleRate: Int,
    ): GainCurve {
      val segments = gain.segments
      return GainCurve(
        from = LongArray(segments.size) { timelineFrameOf(segments[it].start, sampleRate) },
        to = LongArray(segments.size) { timelineFrameOf(segments[it].end, sampleRate) },
        fromGain = FloatArray(segments.size) { segments[it].startGain },
        toGain = FloatArray(segments.size) { segments[it].endGain },
      )
    }
  }
}

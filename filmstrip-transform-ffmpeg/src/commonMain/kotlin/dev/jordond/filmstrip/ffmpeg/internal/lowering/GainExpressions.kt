package dev.jordond.filmstrip.ffmpeg.internal.lowering

import dev.jordond.filmstrip.transform.internal.resolve.GainSegment
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedGain
import kotlin.time.Duration

/**
 * One clip's gain curve as the `volume` expressions a chain of nodes reads, in the clip's own time.
 *
 * ffmpeg's own limit, not the curve's: its expression parser refuses a nest much deeper than a
 * hundred `if`s, and a curve folded from an envelope and a fade runs to hundreds of segments. So a
 * long curve is handed to several nodes, each holding one run of it and reading one everywhere
 * else. `volume` nodes multiply, so the chain lands on the same gain a single node would have
 * rather than on an approximation of it.
 */
internal fun volumeExpressions(gain: ResolvedGain): List<String> {
  val segments = gain.segments
  if (segments.size <= EXPRESSION_SEGMENTS) return listOf(gainExpression(segments))

  return segments.chunked(EXPRESSION_SEGMENTS).map { run ->
    gainExpression(
      buildList {
        if (run.first().start > gain.start) add(GainSegment(gain.start, run.first().start, 1f, 1f))
        addAll(run)
        if (run.last().end < gain.end) add(GainSegment(run.last().end, gain.end, 1f, 1f))
      },
    )
  }
}

/**
 * One run of segments as an expression read against the frame time.
 *
 * The segments are selected the way [ResolvedGain.gainAt] selects them, the last one whose start
 * the frame has reached, which an `if` chain walked from the end spells directly.
 */
private fun gainExpression(segments: List<GainSegment>): String {
  var expression = segmentExpression(segments.last())
  for (index in segments.lastIndex - 1 downTo 0) {
    val next = formatSeconds(segments[index + 1].start.seconds())
    expression = "if(lt(t,$next),${segmentExpression(segments[index])},$expression)"
  }
  return expression
}

/**
 * One segment as the clamped ramp [GainSegment.gainAt] describes.
 *
 * The clamp is what holds the curve flat either side of the whole run, since the first and last
 * segments are the ones a frame outside the clip's own range lands in.
 */
private fun segmentExpression(segment: GainSegment): String {
  val span = segment.end - segment.start
  // A flat run and a zero length step both hold the one number their end carries.
  if (segment.isFlat || span <= Duration.ZERO) return segment.endGain.toString()
  val start = formatSeconds(segment.start.seconds())
  val length = formatSeconds(span.seconds())
  return "${segment.startGain}+(${segment.endGain}-${segment.startGain})*clip((t-$start)/$length,0,1)"
}

// How many segments one volume node's expression carries. ffmpeg 9.0.1 refuses a nest of about a
// hundred ifs, so this leaves the parser twice the room it needs even once a run has been padded
// flat at both ends.
internal const val EXPRESSION_SEGMENTS = 48

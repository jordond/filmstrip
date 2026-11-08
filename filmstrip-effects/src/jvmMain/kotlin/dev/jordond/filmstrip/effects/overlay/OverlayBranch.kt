package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.AuxTimeline
import dev.jordond.filmstrip.effect.FilterArgument
import dev.jordond.filmstrip.effect.FilterNode
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.DurationUnit

/**
 * The planar format `geq` reads a picture's alpha in, and the packed one the picture goes back to after it.
 *
 * @property planar The planar format with alpha `geq` runs on.
 * @property packed The packed RGBA format the branch carries.
 */
internal class OpacityPlanes(
  val planar: String,
  val packed: String,
)

/**
 * The nodes that draw the sampled [frames] at their opacity, keyed on the branch's own clock at [rate].
 *
 * An opacity that holds is a `colorchannelmixer` on the alpha, or nothing at full strength. One that moves is a `geq`
 * that scales each texel's alpha by the frame's value, in [planes]. The value held on the most frames is left to the
 * mixer instead, and `geq` is switched off on those frames, which leaves it only the format round trip to pay there.
 * `geq` reads the frame size it was configured at, so it has to run where the picture's size holds.
 */
internal fun opacityNodes(
  frames: List<SampledFrame>,
  rate: Int,
  planes: OpacityPlanes,
): List<FilterNode> {
  val opacity = frames.runsOf { opacityValue(it.frame) }
  if (opacity.held) return heldOpacityNodes(opacity.runs.single().value, enable = null)

  val clock = branchFrameClock(rate)
  // A value no two frames share leaves the mixer nothing to take over, so geq runs on every frame.
  val common =
    frames
      .groupingBy { opacityValue(it.frame) }
      .eachCount()
      .maxBy { it.value }
      .takeIf { it.value > 1 }
      ?.key
  val moving = frames.runsOf { if (opacityValue(it.frame) == common) OFF else ON }
  val resting = frames.runsOf { if (opacityValue(it.frame) == common) ON else OFF }
  val fade =
    buildList {
      add(FilterArgument("r", "r(X,Y)"))
      add(FilterArgument("g", "g(X,Y)"))
      add(FilterArgument("b", "b(X,Y)"))
      add(FilterArgument("a", "alpha(X,Y)*(${opacity.on(branchPlaneClock(rate))})"))
      if (common != null) add(FilterArgument("enable", moving.on(clock)))
    }
  return listOf(
    FilterNode("format", "pix_fmts" to planes.planar),
    FilterNode("geq", fade),
    FilterNode("format", "pix_fmts" to planes.packed),
  ) + common?.let { heldOpacityNodes(it, enable = resting.on(clock)) }.orEmpty()
}

// A mixer that holds the alpha at value below full strength, on the frames enable lets through when there is one.
private fun heldOpacityNodes(
  value: String,
  enable: String?,
): List<FilterNode> {
  if (value.toFloat() >= 1f) return emptyList()
  val arguments = listOfNotNull(FilterArgument("aa", value), enable?.let { FilterArgument("enable", it) })
  return listOf(FilterNode("colorchannelmixer", arguments))
}

// What an enable expression answers on a frame a filter runs on, and on one it passes through untouched.
private const val ON = "1"
private const val OFF = "0"

// The authored opacity of a picture that holds still, which a mixer applies below full strength.
internal fun stillOpacityNodes(opacity: Float): List<FilterNode> =
  if (opacity < 1f) listOf(FilterNode("colorchannelmixer", "aa" to opacity.toString())) else emptyList()

/**
 * The scale that draws a picture at [width] by [height], re-read on every frame where either moves.
 */
internal fun scaleNode(
  width: FrameRuns,
  height: FrameRuns,
  rate: Int?,
): FilterNode {
  val clock = rate?.let(::branchFrameClock).orEmpty()
  return FilterNode(
    "scale",
    buildList {
      add(FilterArgument("w", width.on(clock)))
      add(FilterArgument("h", height.on(clock)))
      if (!width.held || !height.held) add(FilterArgument("eval", "frame"))
    },
  )
}

// A property that holds one value from the first frame on.
internal fun heldAt(value: Int): FrameRuns = FrameRuns(listOf(FrameRun(0, value.toString())))

// The window, as a node on the overlay's own branch: outside the drawn range the branch is handed on
// fully transparent, so the merge blends nothing onto the frame under it, and inside it the node
// is switched off and passes the picture through untouched.
//
// t is the image branch's own clock, read ahead of the setpts that rebases it: an export opens
// that branch at zero and a scrubbed preview carries it to the seek with -itsoffset, so either
// way it reads the composition time the frame belongs to. It counts from the slot the span opens
// on, which is where the branch's first frame sits.
//
// The bound is half open, the way TimeRange and frameWithin read it, so the frame sitting on the
// drawn range's end is outside it, as it is on every other backend.
//
// The branch advances on the output frame grid, so the frames this opens over are the ones the
// shared sampler names. A clip's merge runs ahead of the tail's fps, and that resampler builds
// two output frames out of one source frame either side of a boundary, so on a clip whose own
// rate differs from the output's an edge lands within one output frame of the sampler's. That is
// the resampler's limit rather than the window's.
internal fun PlacedPicture.windowGate(
  drawnRange: TimeRange,
  span: TimeRange,
  rate: Int?,
): FilterNode? {
  if (effect.visibleDuring == null) return null
  val end = drawnRange.endExclusive ?: return null

  val open = "gte(t,${branchSeconds(drawnRange.start, span, rate)})*lt(t,${branchSeconds(end, span, rate)})"
  return FilterNode("colorchannelmixer", "aa" to "0", "enable" to "not($open)")
}

// An image input is one frame at t = 0, and a branch carrying one frame ends on it. The overlay
// is drawn for as long as the branch it merges onto, so the still is repeated across that whole
// drawn range. A pipeline that resolved neither a rate nor a bounded span has nothing to repeat it over.
internal fun Attributes.auxTimeline(): AuxTimeline? {
  val rate = frameRate?.takeIf { it > 0f } ?: return null
  val length = span.duration ?: return null

  return AuxTimeline(rate, length)
}

// The output rate the planner resolved, which it carries as a Float holding a whole number.
internal val Attributes.gridRate: Int?
  get() = frameRate?.takeIf { it > 0f }?.roundToInt()

// The first output frame at or after a composition time, counted on the branch's own clock. The
// branch's frames sit on the output slots from the one the span's start lands on, so this counts
// the slots from there to the first one at or after [time]. The comparison is in whole
// microseconds, the grid's own unit, so a time sitting on a slot counts as that slot.
internal fun frameIndexAt(
  time: Duration,
  span: TimeRange,
  rate: Int,
): Int {
  val landed = outputSlotOf(time, rate)
  val atOrAfter = if (outputSlotTime(landed, rate) < time.inWholeMicroseconds.microseconds) landed + 1 else landed
  return (atOrAfter - outputSlotOf(span.start, rate)).coerceAtLeast(0).toInt()
}

// Where a composition time lands on the branch the effect runs on, which is the clock every time
// expression on that branch is read against. With a rate, the bound sits half a frame ahead of the
// first frame at or after [time], so that frame's own time is clear of it however either side
// rounds its division. With none, the time is moved onto the branch as it stands.
private fun branchSeconds(
  time: Duration,
  span: TimeRange,
  rate: Int?,
): String =
  if (rate == null) {
    (time - span.start).toDouble(DurationUnit.SECONDS).toString()
  } else {
    ((frameIndexAt(time, span, rate) - HALF_FRAME) / rate).toString()
  }

// Half a frame, in frames, which is how far ahead of a frame a window bound is written.
private const val HALF_FRAME = 0.5

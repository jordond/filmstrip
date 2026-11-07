package dev.jordond.filmstrip.ffmpeg.internal.lowering

import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.transform.internal.geometry.PixelRect
import dev.jordond.filmstrip.transform.internal.heldTail
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.videoSlots
import kotlin.time.Duration

/**
 * The size stage, which places the clip at [rect] in the resolved output frame.
 *
 * Not an effect and not in `ExportPlan.effectOrder`: it is what every clip carries so `concat` sees
 * uniform inputs, and it is why the scale effect emits nothing of its own.
 *
 * [padColor] is the fill's own color, or transparent while that color is waiting for composition
 * effects to run before it is painted in. The caller decides which. [slots] is how many frames the
 * clip is cut to, as [trailingNodes] takes it. [grid] is the chroma grid of the chain the clip is placed from.
 */
internal fun tailNodes(
  outputSize: Size,
  rect: PixelRect,
  padColor: String,
  frameRate: Int,
  pixelFormat: String,
  slots: Long,
  grid: ChromaGrid,
): List<FilterNode> = placedNodes(rect, outputSize, padColor, grid) + trailingNodes(frameRate, pixelFormat, slots)

/**
 * An `overlay` that keeps whatever bit depth its inputs arrived at.
 *
 * The filter's own `format` defaults to `yuv420`, which is 8-bit, so a 10-bit frame reaching any
 * overlay is taken down and back up again on its way to the encoder, and nothing about the exported
 * file says it happened. Every overlay this backend writes is built here so the option cannot be
 * left at its default in one place and set in another.
 */
internal fun overlayNode(vararg arguments: Pair<String, String>): FilterNode =
  FilterNode("overlay", *arguments, "format" to "auto")

/**
 * The stage every tail ends on, blurred letterbox or not.
 *
 * Pins the sample aspect, the frame rate and the pixel format so `concat` sees uniform inputs
 * across every clip, and cuts the clip to exactly [slots] frames, the output slots its span covers.
 * The clip's last frame is cloned ahead of the cut, so a span whose end runs past the source's last
 * frame still has a frame for every slot it covers. A branch that ends on its last frame's own time
 * has that end moved a slot on before `fps` reads it, so the frame is written rather than dropped.
 * [pixelFormat] is the encoder's own 10-bit format for an HDR export, or `yuv420p` for everything
 * else.
 */
internal fun trailingNodes(
  frameRate: Int,
  pixelFormat: String,
  slots: Long,
): List<FilterNode> =
  listOf(
    // scale does not reset the sample aspect ratio and concat refuses to join clips whose sample
    // aspects differ, so this is not cosmetic.
    FilterNode("setsar", "r" to "1"),
    // ffmpeg 6.1.1 ends an overlay on its last frame's own time rather than after it, and fps drops
    // the frame its input ends on. setpts evaluates the end too, and only there does PTS equal the
    // frame before it, so that end moves a slot on and every frame keeps its time. Builds that end
    // after the frame pass through unchanged.
    FilterNode("setpts", "expr" to "PTS+eq(PTS,PREV_INPTS)/($frameRate*TB)"),
    FilterNode("fps", "fps" to frameRate.toString()),
    FilterNode("tpad", "stop_mode" to "clone", "stop" to HELD_FRAMES.toString()),
    FilterNode("trim", "end_frame" to slots.toString()),
    FilterNode("setpts", "expr" to "PTS-STARTPTS"),
    FilterNode("format", "pix_fmts" to pixelFormat),
  )

/**
 * Moves a clip's frames onto the output slots [speed] plays them at, counted from [trimStart] whichever frame the trim
 * opens on, so a slot shows the same source frame from any window.
 *
 * The clock runs in ticks a slot divides into exactly, so a window's shift is a whole number of slots and a frame
 * halfway between two slots rounds the same way from every window. `fps` then writes one frame per slot from the
 * window's opening, dropping what the seek delivered ahead of it and holding the frame showing when it opens. The slot
 * count the tail cuts to is what holds the clip's length, so nothing here decides how long it runs.
 */
internal fun retimedNodes(
  trimStart: Duration,
  speed: Double,
  frameRate: Int,
): List<FilterNode> =
  listOf(
    FilterNode("settb", "expr" to "1/${frameRate * TICKS_PER_SLOT}"),
    FilterNode(
      "setpts",
      "expr" to "trunc((PTS-${formatSeconds(trimStart.seconds())}/TB)/$speed)-round($WINDOW_CLOCK/TB)",
    ),
    FilterNode("fps", "fps" to frameRate.toString(), "start_time" to "0"),
  )

private const val TICKS_PER_SLOT = 1_000

// ffmpeg's limit rather than the grid's: a snapped end runs at most half a slot past the clip's own
// end, and fps drops the frame it is part way through when its input ends, so two clones cover the
// furthest a clip's frames can fall short of its slots. The cut after them drops whatever is spare.
private const val HELD_FRAMES = 2

/**
 * The alpha-carrying format at [pixelFormat]'s own bit depth and chroma layout, for a clip tail
 * that has to pad or composite transparently rather than write an opaque frame outright.
 *
 * `p010le` and `yuv420p10le` are both 10-bit 4:2:0, one packed and one planar, and both convert
 * losslessly to and from the same planar alpha format, `yuva420p10le`. The trailing
 * `format=<pixelFormat>` after the overlay converts back to whichever one the encoder actually
 * wants. Fails loudly on a format this has no equivalent for, so a new HDR format shows up as a
 * build failure instead of a black bar nobody notices.
 */
internal fun alphaPixelFormat(pixelFormat: String): String =
  when (pixelFormat) {
    "yuv420p" -> "yuva420p"
    "yuv420p10le", "p010le" -> "yuva420p10le"
    else -> error("No alpha pixel format known for $pixelFormat.")
  }

/**
 * Clones the frame on the primary's last covered slot over the held tail, then cuts the video to one frame per slot
 * the composition covers.
 *
 * Sits after the composition effects and the deferred fill, so the frame held is the finished one with every effect
 * frozen where it was on that slot. Nothing when no audio track outlasts the primary.
 */
internal fun ResolvedComposition.heldTailNodes(): List<FilterNode> {
  val tail = heldTail
  if (tail.isEmpty()) return emptyList()

  return listOf(
    FilterNode("tpad", "stop_mode" to "clone", "stop" to (tail.last - tail.first + 1).toString()),
    FilterNode("trim", "end_frame" to videoSlots.toString()),
  )
}

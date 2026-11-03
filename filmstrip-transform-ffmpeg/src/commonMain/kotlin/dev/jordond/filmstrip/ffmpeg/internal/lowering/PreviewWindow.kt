package dev.jordond.filmstrip.ffmpeg.internal.lowering

import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import kotlin.time.Duration

/**
 * Where a preview asked to open at [at] opens, which is the time of the output slot [at] lands on.
 *
 * The clip is seeked there and a rebased branch is moved there, so the first frame the pump hands over is that slot's
 * own frame and this is the composition time it carries. Zero for a graph an input seek cannot window, which is read
 * forward from its head instead.
 */
internal fun Invocation.previewLanding(at: Duration): Duration {
  if (seekBase == null) return Duration.ZERO
  val frameRate = output.frameRate
  return outputSlotTime(outputSlotOf(at, frameRate), frameRate)
}

/**
 * Where in the composition a preview asked to open at [at] reads the clip from.
 *
 * The [previewLanding] everywhere the primary covers. Inside the [Invocation.heldTail] the clip has no frame of its
 * own to seek to, so it opens on the primary's last covered slot instead and the pump holds that frame over the
 * rest. The first frame the pump hands over is then the held frame, carrying what every effect drew on that slot.
 */
internal fun Invocation.previewSeek(at: Duration): Duration {
  val landing = previewLanding(at)
  if (heldTail.isEmpty()) return landing
  val frameRate = output.frameRate
  return minOf(landing, outputSlotTime(heldTail.first - 1, frameRate))
}

/**
 * How many frames a preview opened at [at] takes from the graph, and how many more the pump holds after them.
 *
 * Together they cover every output slot from the [previewLanding] to the video's end. The graph renders the slots the
 * primary covers from the one the clip opens on, and the pump repeats the last of them over what is left of the
 * [Invocation.heldTail], so every tail frame is the one the primary's last covered slot finished on, with every effect
 * drawn as it was there.
 */
internal fun Invocation.previewFrames(at: Duration): PreviewFrames {
  val slots = videoSlots
  if (seekBase == null || slots == null) return PreviewFrames(rendered = null, held = 0)
  val frameRate = output.frameRate
  val remaining = (slots - outputSlotOf(previewLanding(at), frameRate)).coerceAtLeast(0)
  if (heldTail.isEmpty()) return PreviewFrames(rendered = remaining, held = 0)

  // Nothing the graph writes past the primary is taken. The clip's cut counts from the slot it opened on, so the frames
  // it clones past its own end reach the composition effects on tail slots, which draw them later than the export does.
  val rendered = minOf(remaining, heldTail.first - outputSlotOf(previewSeek(at), frameRate))
  return PreviewFrames(rendered = rendered, held = remaining - rendered)
}

/**
 * The frames a preview hands over, split between the graph and the pump.
 *
 * @property rendered How many frames the graph renders from the slot the clip opens on, which `-frames:v` stops it at.
 * Null for a graph read forward from its head, which runs to its own end.
 * @property held How many times the pump repeats the last rendered frame once the graph has rendered them all.
 */
internal class PreviewFrames(
  val rendered: Long?,
  val held: Long,
)

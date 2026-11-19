package dev.jordond.filmstrip.ffmpeg.internal.lowering

import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * The lavfi `color` source a [solid] is read from, at the size the planner laid [clip] at and one frame per slot of
 * [frameRate].
 *
 * It runs one frame past the clip's end so the trim the clip reads through never comes up short. A translucent colour
 * is read as RGBA so its alpha reaches the merge. On a kept HDR grade on [transfer] the frame is repainted by
 * [hdrPaintNodes], the same way the fill is.
 */
internal fun solidInput(
  solid: MediaSource.Solid,
  clip: ResolvedClip,
  frameRate: Int,
  transfer: HdrTransfer?,
): InputSource.Generated {
  val size = checkNotNull(clip.info.video) { "A laid solid always carries a picture." }.displaySize
  val length = (clip.end + 1.seconds / frameRate).toDouble(DurationUnit.SECONDS)
  val frame = "s=${size.width}x${size.height}:r=$frameRate:d=${formatSeconds(length)}"
  if (transfer != null) return InputSource.Generated("color=$frame," + hdrPaintNodes(solid.color, transfer).render())

  val color = "color=c=${solidColor(solid.color)}:$frame"
  return InputSource.Generated(if (solid.color ushr ALPHA_SHIFT == OPAQUE) color else "$color,format=rgba")
}

private const val ALPHA_SHIFT = 24
private const val OPAQUE = 0xFF

package dev.jordond.filmstrip.ffmpeg.internal.lowering

import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * The lavfi `color` source a [solid] is read from, at the size the planner laid [clip] at and one frame per slot of
 * [frameRate].
 *
 * It runs one frame past the clip's end so the trim the clip reads through never comes up short. A translucent colour
 * is read as RGBA so its alpha reaches the merge.
 */
internal fun solidInput(
  solid: MediaSource.Solid,
  clip: ResolvedClip,
  frameRate: Int,
): InputSource.Generated {
  val size = checkNotNull(clip.info.video) { "A laid solid always carries a picture." }.displaySize
  val length = (clip.end + 1.seconds / frameRate).toDouble(DurationUnit.SECONDS)
  val hex = solidColor(solid.color)
  val color = "color=c=$hex:s=${size.width}x${size.height}:r=$frameRate:d=${formatSeconds(length)}"
  return InputSource.Generated(if (solid.color ushr ALPHA_SHIFT == OPAQUE) color else "$color,format=rgba")
}

private const val ALPHA_SHIFT = 24
private const val OPAQUE = 0xFF

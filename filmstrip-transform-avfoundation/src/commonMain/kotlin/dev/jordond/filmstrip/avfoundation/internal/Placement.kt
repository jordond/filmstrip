package dev.jordond.filmstrip.avfoundation.internal

import dev.jordond.filmstrip.avfoundation.internal.composition.SdSourceColor
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import kotlin.time.Duration

/**
 * One clip, once it has a slot on the composition timeline.
 */
internal class Placement(
  val clip: ResolvedClip,
  val start: Duration,
  val end: Duration,
  val heldFrom: Duration? = null,
  val sdColor: SdSourceColor? = null,
) {
  /**
   * This slot with [clip] in it, the same clip planned again.
   */
  fun laying(clip: ResolvedClip): Placement = Placement(clip, start, end, heldFrom, sdColor)
}

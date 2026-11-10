package dev.jordond.filmstrip.ffmpeg.internal.lowering

import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.transform.internal.negotiation.storedSizeOf
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip

// The nodes that widen a clip stored with non-square pixels to the size it shows, or none when its pixels are square.
// scale keeps the shown shape by moving the sample aspect, so setsar pins it to square for the effects after it.
internal fun ResolvedClip.squaringNodes(): List<FilterNode> {
  val video = info.video ?: return emptyList()
  val shown = video.displaySize
  if (shown == storedSizeOf(video)) return emptyList()
  return listOf(
    FilterNode("scale", "w" to "${shown.width}", "h" to "${shown.height}"),
    FilterNode("setsar", "r" to "1"),
  )
}

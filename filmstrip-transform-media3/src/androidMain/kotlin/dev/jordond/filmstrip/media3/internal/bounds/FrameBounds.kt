package dev.jordond.filmstrip.media3.internal.bounds

import dev.jordond.filmstrip.media.MediaSource
import kotlin.time.Duration

/**
 * The video samples one clip's window can show, read off its source's container.
 *
 * Every time is on the source's own clock, in presentation order. They run from the sync sample before the clip's start
 * up to the second sync sample past its end, so the sample nearest either end is among them even when it sits outside
 * the window.
 *
 * @property samples Each sample's presentation time.
 * @property end Where the last of [samples] stops showing. That is the sample the read stopped on, or the end of the
 * video track when the read ran out of samples.
 */
internal class FrameBounds(
  val samples: List<Duration>,
  val end: Duration,
) {
  init {
    require(samples.isNotEmpty() && end > samples.last()) { "end ($end) must come after the last sample" }
  }
}

/**
 * The window a clip cuts from its source, which is all its [FrameBounds] depend on.
 */
internal data class ClipWindow(
  val source: MediaSource,
  val start: Duration,
  val end: Duration,
)

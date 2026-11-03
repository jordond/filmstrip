package dev.jordond.filmstrip.media3.internal.bounds

import dev.jordond.filmstrip.media.MediaSource
import kotlin.time.Duration

/**
 * The video samples one clip's window decodes, read off its source's container.
 *
 * Every time is on the source's own clock. media3 opens a clipped item on the earliest sample at or after its start and
 * stops decoding at [end], so an item's decoded length runs from [first] to [end].
 *
 * @property first The earliest sample at or after the clip's start, in presentation order.
 * @property last The latest sample before [end], in presentation order.
 * @property end Where the decoded window stops, which is the clip's end or the source's own end when that comes first.
 */
internal class FrameBounds(
  val first: Duration,
  val last: Duration,
  val end: Duration,
)

/**
 * The window a clip cuts from its source, which is all its [FrameBounds] depend on.
 */
internal data class ClipWindow(
  val source: MediaSource,
  val start: Duration,
  val end: Duration,
)

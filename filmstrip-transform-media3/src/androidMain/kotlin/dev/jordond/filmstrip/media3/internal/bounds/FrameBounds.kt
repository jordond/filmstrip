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
 * Where a clip's audio decode opens on its source, read off its container.
 *
 * @property unit The start of the access unit the decode opens on, timed from the head of the track with the encoder
 * delay included, which is how media3 times the units it decodes.
 * @property encoderDelay How long the encoder delay the source's audio track names runs. media3 trims it from the head
 * of an item clipped from the source's own start, and from no other.
 */
internal class AudioPreroll(
  val unit: Duration,
  val encoderDelay: Duration,
) {
  /**
   * Where the first frame an item clipped from [unit] decodes sits on the source's presented clock, which is the one a
   * trim is given on.
   *
   * An item clipped from the source's own start has the delay trimmed, so it opens on the first presented frame. Any
   * other keeps the delay, so its first frame comes the delay earlier than its unit's time.
   */
  val opens: Duration get() = if (unit == Duration.ZERO) Duration.ZERO else unit - encoderDelay
}

/**
 * The window a clip cuts from its source, which is all its [FrameBounds] depend on.
 */
internal data class ClipWindow(
  val source: MediaSource,
  val start: Duration,
  val end: Duration,
)

/**
 * Where a clip's audio opens on its source, and which of the source's audio tracks it plays, which is all its
 * [AudioPreroll] depends on.
 *
 * @property audioOrdinal Where the clip's audio track sits among the source's audio tracks, counted from zero.
 */
internal data class AudioWindow(
  val source: MediaSource,
  val start: Duration,
  val audioOrdinal: Int,
)

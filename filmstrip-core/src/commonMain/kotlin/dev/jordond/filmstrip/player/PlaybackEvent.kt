package dev.jordond.filmstrip.player

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.export.Adjustment
import kotlin.time.Duration

/**
 * Something that happened, delivered once.
 *
 * Events are edge-triggered and lossless, so drive anything that advances the session from them:
 * looping at the out point, auto-advance, analytics. Passive UI reads [PlayerState] instead.
 *
 * Delivered with no replay, so subscribe before issuing commands.
 */
public sealed interface PlaybackEvent {
  /**
   * Playback reached the end of the composition.
   *
   * @property finalPosition Where the playhead stopped.
   * @property duration The composition's duration.
   */
  @Poko
  public class Ended(
    public val finalPosition: Duration,
    public val duration: Duration,
  ) : PlaybackEvent

  /**
   * A seek landed and its frame was presented.
   *
   * @property position Where the seek landed.
   */
  @Poko
  public class SeekCompleted(
    public val position: Duration,
  ) : PlaybackEvent

  /**
   * The first frame of a newly set composition reached the surface.
   *
   * The signal to stop showing whatever was on screen before.
   */
  public data object FirstFrameRendered : PlaybackEvent

  /**
   * A loop range wrapped around.
   *
   * @property range The range that wrapped.
   */
  @Poko
  public class RangeLooped(
    public val range: TimeRange,
  ) : PlaybackEvent

  /**
   * Playback failed.
   *
   * @property error Why it failed.
   */
  @Poko
  public class Failed(
    public val error: PlaybackError,
  ) : PlaybackEvent

  /**
   * Something outside filmstrip changed whether playback is wanted, such as an audio-focus loss, a
   * route change, or the system pausing the app.
   *
   * @property playWhenReady Whether playback is wanted after the change.
   */
  @Poko
  public class ExternalPlayWhenReadyChanged(
    public val playWhenReady: Boolean,
  ) : PlaybackEvent

  /**
   * The preview graph had to approximate something.
   *
   * Surface it to the user rather than only logging it.
   *
   * @property adjustments What the graph had to change.
   */
  @Poko
  public class EffectsDegraded(
    public val adjustments: List<Adjustment>,
  ) : PlaybackEvent

  /**
   * The preview had to reduce its own quality to keep up.
   *
   * @property policy The policy now in force.
   * @property message A human-readable description, safe to show and unsuitable for parsing.
   */
  @Poko
  public class QualityDegraded(
    public val policy: PreviewQualityPolicy,
    public val message: String,
  ) : PlaybackEvent
}

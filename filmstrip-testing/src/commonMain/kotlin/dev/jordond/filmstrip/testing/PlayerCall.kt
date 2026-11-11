package dev.jordond.filmstrip.testing

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.player.SeekAccuracy
import kotlin.time.Duration

/**
 * One call a test's subject made on a [FakeVideoPlayer], with the arguments it passed.
 *
 * The player keeps them in the order they arrived, so a test asserts on what was asked for and when.
 */
public sealed interface PlayerCall {
  /**
   * A call to `play`.
   */
  public data object Play : PlayerCall

  /**
   * A call to `pause`.
   */
  public data object Pause : PlayerCall

  /**
   * A call to `seekTo`.
   *
   * @property position Where the seek was aimed.
   * @property accuracy How exact a landing was asked for.
   */
  @Poko
  public class SeekTo(
    public val position: Duration,
    public val accuracy: SeekAccuracy,
  ) : PlayerCall

  /**
   * A call to `stepFrames`.
   *
   * @property frames How many frames to step, negative for backwards.
   */
  @Poko
  public class StepFrames(
    public val frames: Int,
  ) : PlayerCall

  /**
   * A call to `beginScrub`.
   */
  public data object BeginScrub : PlayerCall

  /**
   * A call to `endScrub`.
   */
  public data object EndScrub : PlayerCall

  /**
   * A call to `beginShuttle`, recorded whether or not the rate was one the player takes.
   *
   * @property rate The rate asked for.
   */
  @Poko
  public class BeginShuttle(
    public val rate: Float,
  ) : PlayerCall

  /**
   * A call to `endShuttle`.
   */
  public data object EndShuttle : PlayerCall

  /**
   * A call to `setVolume`.
   *
   * @property volume The volume asked for.
   */
  @Poko
  public class SetVolume(
    public val volume: Float,
  ) : PlayerCall

  /**
   * A call to `setLoopRange`.
   *
   * @property range The range to loop, or null to stop looping.
   */
  @Poko
  public class SetLoopRange(
    public val range: TimeRange?,
  ) : PlayerCall

  /**
   * A call to `setPlaybackRange`.
   *
   * @property range The range to play inside, or null for the whole composition.
   */
  @Poko
  public class SetPlaybackRange(
    public val range: TimeRange?,
  ) : PlayerCall

  /**
   * A call to `setQualityPolicy`.
   *
   * @property policy The policy asked for.
   */
  @Poko
  public class SetQualityPolicy(
    public val policy: PreviewQualityPolicy,
  ) : PlayerCall

  /**
   * A call to `setExportSpec`.
   *
   * @property spec The spec asked for.
   */
  @Poko
  public class SetExportSpec(
    public val spec: ExportSpec,
  ) : PlayerCall

  /**
   * A call to either `setComposition`, and the composition a player was made with.
   *
   * @property composition The edit handed over.
   * @property startAt Where to start, or null to keep the playhead.
   * @property playWhenReady Whether playback was asked to start once loaded.
   */
  @Poko
  public class SetComposition(
    public val composition: Composition,
    public val startAt: Duration?,
    public val playWhenReady: Boolean,
  ) : PlayerCall

  /**
   * A call to `close`.
   */
  public data object Close : PlayerCall
}

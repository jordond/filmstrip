package dev.jordond.filmstrip.player

import dev.drewhamilton.poko.Poko

/**
 * An atomic snapshot of the player, across four orthogonal axes.
 *
 * Never torn: a reader cannot see playing and errored at the same time.
 *
 * @property status Where the player is in its lifecycle.
 * @property playWhenReady Intent. Flipped synchronously by [VideoPlayer.play] and
 *   [VideoPlayer.pause], and never by buffering, so this is what a play or pause button reads.
 * @property isStalled Data. True when playback cannot advance at the current position, so this is
 *   what a spinner reads.
 * @property isSeeking True when a seek has been issued and its frame has not been presented.
 */
@Poko
public class PlayerState(
  public val status: PlaybackStatus,
  public val playWhenReady: Boolean,
  public val isStalled: Boolean,
  public val isSeeking: Boolean,
) {
  /**
   * Strictly playing: ready, wanted, and not stalled.
   *
   * Answers "is video advancing", so it drives analytics rather than buttons. It is false while
   * buffering even though the user pressed play.
   */
  public val isPlaying: Boolean
    get() = status == PlaybackStatus.Ready && playWhenReady && !isStalled

  /**
   * True when something is in flight that the user should see a spinner for.
   */
  public val isBusy: Boolean
    get() = status == PlaybackStatus.Preparing || isStalled || isSeeking

  /**
   * True when a composition is loaded and presentable.
   */
  public val hasComposition: Boolean
    get() = status == PlaybackStatus.Ready || status == PlaybackStatus.Ended

  public companion object {
    /**
     * Idle, not wanted, not stalled, not seeking.
     */
    public val Initial: PlayerState = PlayerState(PlaybackStatus.Idle, false, false, false)
  }
}

/**
 * Where the player is in its lifecycle.
 *
 * [Ready] means presentable, not buffered: whether data is available is [PlayerState.isStalled], on
 * its own axis.
 */
public sealed interface PlaybackStatus {
  /**
   * No composition set.
   */
  public data object Idle : PlaybackStatus

  /**
   * A composition is being prepared.
   */
  public data object Preparing : PlaybackStatus

  /**
   * Presentable.
   */
  public data object Ready : PlaybackStatus

  /**
   * Playback reached the end of the composition.
   */
  public data object Ended : PlaybackStatus

  /**
   * Playback failed.
   *
   * @property error Why it failed.
   */
  @Poko
  public class Error(
    public val error: PlaybackError,
  ) : PlaybackStatus

  /**
   * The player was closed. Terminal: no flow of this player emits again.
   */
  public data object Released : PlaybackStatus
}

/**
 * Why playback failed.
 */
public sealed interface PlaybackError {
  /**
   * A human-readable description, safe to log and unsuitable for parsing.
   */
  public val message: String

  /**
   * The source could not be opened or read.
   */
  @Poko
  public class SourceUnreadable(
    override val message: String,
  ) : PlaybackError

  /**
   * The container or codec is not playable here.
   */
  @Poko
  public class UnsupportedFormat(
    override val message: String,
  ) : PlaybackError

  /**
   * No decoder was available, or every decode session was already taken.
   */
  @Poko
  public class DecoderUnavailable(
    override val message: String,
  ) : PlaybackError

  /**
   * The source is DRM-protected. It cannot be previewed with effects applied, and it cannot be
   * exported either.
   */
  @Poko
  public class SourceNotExportable(
    override val message: String,
  ) : PlaybackError

  /**
   * No preview backend was registered.
   *
   * @property artifact The Maven coordinate that supplies one.
   */
  @Poko
  public class BackendMissing(
    public val artifact: String,
    override val message: String,
  ) : PlaybackError

  /**
   * Ran out of memory building or rendering the preview.
   */
  @Poko
  public class OutOfMemory(
    override val message: String,
  ) : PlaybackError

  /**
   * A platform failure filmstrip could not classify.
   *
   * @property platformCode The platform's own error code, for a bug report.
   */
  @Poko
  public class Underlying(
    public val platformCode: Int,
    override val message: String,
  ) : PlaybackError
}

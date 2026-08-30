package dev.jordond.filmstrip.player

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import kotlin.time.Duration

/**
 * A playback engine.
 *
 * The extension point behind [VideoPlayer]. Implement it to plug in an engine, from Kotlin or from
 * Swift, and register it with a [PlayerEngineFactory]. Consumers use [VideoPlayer], which is the
 * facade built on top of this.
 */
public interface PlayerEngine {
  /**
   * A stable identifier for diagnostics, such as `filmstrip.exoplayer`.
   */
  public val id: String

  /**
   * What this engine can do on this device, discovered rather than assumed.
   */
  public val features: PlayerFeatures

  /**
   * Reads rendered frames back out of the preview pipeline. Required, not optional.
   */
  public val readback: PreviewFrameReadback

  /**
   * The platform player object, for a surface to attach itself to. Null when there is none.
   */
  public val nativePlayer: Any?

  /**
   * Loads a composition, or replaces the current one.
   *
   * Implementations must classify the diff against what is already loaded rather than rebuilding
   * unconditionally. A structural change forces a decoder reinitialisation costing tens to low
   * hundreds of milliseconds: clips added, removed, reordered, or a trim that changes duration. A
   * change confined to effect parameters must not, and instead updates the live parameter objects
   * the pipeline already holds and redraws. Setting an equal composition must do nothing at all.
   *
   * @param request What to load, and how.
   * @param callback Receives the outcome exactly once.
   * @return a handle that cancels the request.
   */
  public fun setComposition(
    request: SetCompositionRequest,
    callback: SetCompositionCallback,
  ): Cancellable

  /**
   * Registers a listener for state, events, position and preview info.
   *
   * @return a handle that unregisters the listener when cancelled.
   */
  public fun addListener(listener: EngineListener): Cancellable

  /**
   * Starts playback, or resumes it.
   */
  public fun play()

  /**
   * Pauses playback, holding the current position.
   */
  public fun pause()

  /**
   * Seeks to [position].
   *
   * Every issued seek must eventually yield exactly one completion, real or synthesised, or an
   * unseekable source wedges the caller's scrubber.
   *
   * @param accuracy How exact the landing frame has to be.
   */
  public fun seekTo(
    position: Duration,
    accuracy: SeekAccuracy,
  )

  /**
   * Steps by whole frames, forwards for a positive count and backwards for a negative one.
   */
  public fun stepFrames(frames: Int)

  /**
   * Enters scrubbing mode, where the engine may relax seek accuracy to keep up with a finger.
   */
  public fun beginScrub()

  /**
   * Leaves scrubbing mode and settles on an exact frame.
   */
  public fun endScrub()

  /**
   * Sets monitor volume in `0f..1f`.
   *
   * Monitor volume only, on both platforms. It does not reach the export. All gain that should end
   * up in the file belongs in the composition's audio spec.
   */
  public fun setVolume(volume: Float)

  /**
   * Loops within [range], or plays straight through when null.
   */
  public fun setLoopRange(range: TimeRange?)

  /**
   * Restricts playback to [range], or to the whole composition when null.
   */
  public fun setPlaybackRange(range: TimeRange?)

  /**
   * Sets how hard the preview may work.
   */
  public fun setQualityPolicy(policy: PreviewQualityPolicy)

  /**
   * Releases every resource. Idempotent, and nothing may be called afterwards.
   */
  public fun dispose()
}

/**
 * Builds a [PlayerEngine], or declines.
 *
 * Factories are asked in registration order, and registration is always explicit.
 */
public fun interface PlayerEngineFactory {
  /**
   * Builds an engine for [config].
   *
   * @param config How the player should be built.
   * @param components The registry the owning `Filmstrip` was built with, holding the same
   *   resolvers and probers an export runs through.
   * @return an engine, or null to defer to the next factory.
   */
  public fun create(
    config: PlayerConfig,
    components: ComponentRegistry,
  ): PlayerEngine?
}

/**
 * How a player should be built.
 *
 * @property surfaceType Which kind of surface the preview renders into.
 * @property qualityPolicy How hard the preview may work.
 */
@Poko
public class PlayerConfig(
  public val surfaceType: PreviewSurfaceType = PreviewSurfaceType.Surface,
  public val qualityPolicy: PreviewQualityPolicy = PreviewQualityPolicy.Full,
)

/**
 * What to load, and how.
 *
 * @property composition The composition to present.
 * @property startAt Where to start, or null to hold the current position. Holding it is
 *   almost always right, since a structural edit rebuilds the pipeline and dropping the playhead
 *   back to zero on every clip reorder reads as a bug.
 * @property playWhenReady Whether playback should start once the composition is presentable.
 */
@Poko
public class SetCompositionRequest(
  public val composition: EditComposition,
  public val startAt: Duration? = null,
  public val playWhenReady: Boolean = false,
)

/**
 * Receives the outcome of one [PlayerEngine.setComposition].
 */
public fun interface SetCompositionCallback {
  /**
   * Called exactly once per request.
   */
  public fun onResult(result: SetCompositionResult)
}

/**
 * The outcome of setting a composition.
 */
public sealed interface SetCompositionResult {
  /**
   * The composition is loaded and presentable.
   *
   * @property duration The loaded composition's duration.
   */
  @Poko
  public class Success(
    public val duration: Duration,
  ) : SetCompositionResult

  /**
   * The composition could not be loaded.
   *
   * @property error Why it failed.
   */
  @Poko
  public class Failure(
    public val error: PlaybackError,
  ) : SetCompositionResult

  /**
   * A later request replaced this one before it completed.
   */
  public data object Superseded : SetCompositionResult
}

/**
 * Receives an engine's state and event pushes.
 */
public interface EngineListener {
  /**
   * A new state snapshot. Delivered whenever any axis changes.
   */
  public fun onStateChanged(state: PlayerState)

  /**
   * An edge fact. Delivered once, with no replay.
   */
  public fun onEvent(event: PlaybackEvent)

  /**
   * The playhead moved. Delivered often, so keep the implementation cheap.
   */
  public fun onPosition(position: Duration)

  /**
   * What the preview is delivering changed.
   */
  public fun onPreviewInfo(info: PreviewInfo)
}

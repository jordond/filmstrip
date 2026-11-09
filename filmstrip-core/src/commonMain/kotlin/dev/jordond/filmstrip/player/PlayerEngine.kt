package dev.jordond.filmstrip.player

import androidx.compose.runtime.Immutable
import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.FilmstripComponents
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.export.ExportSpec
import kotlin.time.Duration

/**
 * A playback engine.
 *
 * The extension point behind [VideoPlayer]. Implement it to plug in an engine, from Kotlin or from
 * Swift, and register it with a [PlayerEngineFactory]. Consumers use [VideoPlayer], which is the
 * facade built on top of this.
 */
public interface PlayerEngine : PlayerTransport {
  /**
   * A stable identifier for diagnostics, such as `filmstrip.exoplayer`.
   */
  public val id: String

  /**
   * What this engine can do, as it finds it on this device.
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
   * Releases every resource. Idempotent, and nothing may be called afterwards.
   */
  public fun dispose()
}

/**
 * Builds a [PlayerEngine], or declines.
 *
 * Factories are asked newest registration first, so one registered later overrides one registered
 * earlier. Registration is always explicit.
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
    components: FilmstripComponents,
  ): PlayerEngine?
}

/**
 * How a player should be built.
 *
 * @property qualityPolicy How hard the preview may work.
 * @property exportSpec The export this preview stands in for. The preview renders at the HDR grade that export resolves
 * to, read from the whole spec since [dev.jordond.filmstrip.export.HdrMode.Auto] depends on the rest of it. Only the
 * grade is mirrored. Height, frame rate, codec and bitrate do not reach the preview's frame, and `strict` refuses no
 * preview, so a strict spec previews the grade it would have conceded to.
 */
@Immutable
@Poko
public class PlayerConfig(
  public val qualityPolicy: PreviewQualityPolicy = PreviewQualityPolicy.Full,
  public val exportSpec: ExportSpec = ExportSpec(),
)

/**
 * What to load, and how.
 *
 * @property composition The composition to present.
 * @property startAt Where to start, or null to hold the current position. Holding it is
 *   almost always right, since a structural edit rebuilds the pipeline and dropping the playhead
 *   back to zero on every clip reorder reads as a bug.
 * @property playWhenReady Whether playback should start once the composition is presentable. False leaves a playing
 *   preview playing, and only [PlayerEngine.pause] stops one.
 */
@Poko
public class SetCompositionRequest(
  public val composition: Composition,
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

  /**
   * The frame at the playhead changed while the playhead stayed where it was.
   *
   * Delivered once the changed frame can be read back, which is what sends a surface drawing the readback to read it.
   */
  @InternalFilmstripApi
  public fun onFrameRedrawn(): Unit = Unit
}

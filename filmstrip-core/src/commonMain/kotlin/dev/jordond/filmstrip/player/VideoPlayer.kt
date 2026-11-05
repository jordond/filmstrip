package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.edit.EditComposition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/**
 * A player over an [EditComposition].
 *
 * Implemented by filmstrip, never by a consumer. State, events and the playhead come in a coroutine
 * form and a listener form, so a caller without coroutines loses nothing.
 */
public interface VideoPlayer :
  PlayerTransport,
  AutoCloseable {
  /**
   * Serialised, in-order, internally consistent snapshots. Never torn across axes.
   */
  public val state: StateFlow<PlayerState>

  /**
   * Edge facts, delivered losslessly to keeping-up collectors, with no replay.
   *
   * Subscribe before issuing commands.
   */
  public val events: SharedFlow<PlaybackEvent>

  /**
   * What the preview is actually delivering, including its parity and per-property fidelity.
   */
  public val previewInfo: StateFlow<PreviewInfo>

  /**
   * What this backend can do on this device.
   */
  public val features: PlayerFeatures

  /**
   * Reads rendered preview frames back, post-effects and pre-encode.
   */
  public val readback: PreviewFrameReadback

  /**
   * The platform player underneath, or null when no backend is attached.
   *
   * An escape hatch, for attaching a platform surface without a platform type reaching this
   * interface. Reading it is fine. Driving transport or lifecycle through it is not: the state
   * machine here will not notice, and nothing recovers from the mismatch.
   */
  public val nativePlayer: Any?

  /**
   * Reads the playhead.
   *
   * Optimistic: a seek's target is returned as soon as it is issued, before the backend has landed
   * on it, so a scrubber thumb does not snap backwards under the user's finger.
   *
   * @return the current position.
   */
  public fun currentPosition(): Duration

  /**
   * Observes the playhead as a cold flow.
   *
   * Emits at most once per [tick] of media time, snapped to that grid. Pass [Duration.ZERO] for
   * every UI frame, which is only worth it for a smooth scrubber thumb, and even then prefer
   * drawing over recomposing.
   *
   * @param tick The grid to snap emissions to, or [Duration.ZERO] for every update.
   * @return a cold flow of playhead positions.
   */
  public fun positionFlow(tick: Duration): Flow<Duration>

  /**
   * Loads a composition, or replaces the current one, suspending until it is presentable.
   *
   * Cheap to call repeatedly with a changed value. An equal composition does nothing, a change
   * confined to effect parameters updates the live pipeline and redraws, and only a structural
   * change rebuilds: clips added, removed, reordered, or a trim that changes duration. Debounce
   * structural edits so a drag-reorder issues one rebuild rather than thirty.
   *
   * @param composition The composition to present, the same value that goes to `export`.
   * @param startAt Where to start, or null to hold the current position.
   * @param playWhenReady Whether playback should start once the composition is presentable. False leaves a playing
   *   preview playing, and only [pause] stops one.
   * @return whether the composition loaded, failed, or was superseded by a later call.
   */
  public suspend fun setComposition(
    composition: EditComposition,
    startAt: Duration? = null,
    playWhenReady: Boolean = false,
  ): SetCompositionResult

  /**
   * The callback form of [setComposition], for callers without coroutines.
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
   * Observes state without coroutines.
   *
   * @return a handle that unregisters the listener when cancelled.
   */
  public fun addStateListener(listener: PlayerStateListener): Cancellable

  /**
   * Observes events without coroutines.
   *
   * @return a handle that unregisters the listener when cancelled.
   */
  public fun addEventListener(listener: PlaybackEventListener): Cancellable

  /**
   * Observes the playhead without coroutines, at most once per [tick].
   *
   * Reads the same playhead as [positionFlow]. The current position arrives as soon as the listener
   * is registered, and a seek's target arrives as soon as it is issued.
   *
   * @param tick The grid to snap callbacks to.
   * @param listener Receives each tick.
   * @return a handle that unregisters the listener when cancelled.
   */
  public fun addPositionListener(
    tick: Duration,
    listener: PlayerPositionListener,
  ): Cancellable

  /**
   * Releases every resource. Idempotent, and nothing may be called afterwards.
   */
  override fun close()
}

/**
 * Receives [PlayerState] snapshots.
 */
public fun interface PlayerStateListener {
  /**
   * Called on every state change, on filmstrip's player dispatcher.
   */
  public fun onStateChanged(state: PlayerState)
}

/**
 * Receives [PlaybackEvent] edges.
 */
public fun interface PlaybackEventListener {
  /**
   * Called once per event, with no replay for late subscribers.
   */
  public fun onEvent(event: PlaybackEvent)
}

/**
 * Receives playhead ticks.
 */
public fun interface PlayerPositionListener {
  /**
   * Called at most once per requested tick of media time.
   */
  public fun onPosition(position: Duration)
}

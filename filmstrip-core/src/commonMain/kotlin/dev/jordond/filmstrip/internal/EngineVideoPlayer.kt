package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.player.EngineListener
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackEventListener
import dev.jordond.filmstrip.player.PlayerEngine
import dev.jordond.filmstrip.player.PlayerFeatures
import dev.jordond.filmstrip.player.PlayerPositionListener
import dev.jordond.filmstrip.player.PlayerState
import dev.jordond.filmstrip.player.PlayerStateListener
import dev.jordond.filmstrip.player.PlayerTransport
import dev.jordond.filmstrip.player.PreviewFrameReadback
import dev.jordond.filmstrip.player.PreviewInfo
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.SetCompositionCallback
import dev.jordond.filmstrip.player.SetCompositionRequest
import dev.jordond.filmstrip.player.SetCompositionResult
import dev.jordond.filmstrip.player.VideoPlayer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.time.Duration

// Turns an engine's callbacks into the flows and suspend members VideoPlayer exposes. Transport goes straight to the
// engine, apart from a seek, which moves the playhead first.
internal class EngineVideoPlayer(
  private val engine: PlayerEngine,
  initial: EditComposition,
) : VideoPlayer,
  PlayerTransport by engine {
  private val _state = MutableStateFlow(PlayerState.Initial)
  private val _previewInfo = MutableStateFlow(EMPTY_PREVIEW_INFO)
  private val _events =
    MutableSharedFlow<PlaybackEvent>(
      replay = 0,
      extraBufferCapacity = EVENT_BUFFER,
    )
  private val positionState = MutableStateFlow(Duration.ZERO)
  private val frameRevisionState = MutableStateFlow(0)

  // Swapped whole on every change, so a listener cancelled from inside a callback does not
  // disturb the walk that called it.
  private var positionListeners: List<(Duration) -> Unit> = emptyList()

  private val listenerHandle: Cancellable =
    engine.addListener(
      object : EngineListener {
        override fun onStateChanged(state: PlayerState) {
          _state.value = state
        }

        override fun onEvent(event: PlaybackEvent) {
          // A load can leave and reach a presentable status inside one dispatch, which state alone conflates away.
          if (event == PlaybackEvent.FirstFrameRendered) onFrameRedrawn()
          _events.tryEmit(event)
        }

        override fun onFrameRedrawn() {
          frameRevisionState.update { it + 1 }
        }

        override fun onPosition(position: Duration) {
          movePlayhead(position)
        }

        override fun onPreviewInfo(info: PreviewInfo) {
          _previewInfo.value = info
        }
      },
    )

  init {
    // The result is dropped. A failure surfaces as PlaybackStatus.Error on state, which is where a
    // UI reads it.
    engine.setComposition(SetCompositionRequest(initial)) { }
  }

  override val state: StateFlow<PlayerState> get() = _state.asStateFlow()

  override val events: SharedFlow<PlaybackEvent> get() = _events.asSharedFlow()

  override val previewInfo: StateFlow<PreviewInfo> get() = _previewInfo.asStateFlow()

  override fun frameRevision(): Flow<Int> = frameRevisionState

  override val features: PlayerFeatures get() = engine.features

  override val readback: PreviewFrameReadback get() = engine.readback

  override val nativePlayer: Any? get() = engine.nativePlayer

  override fun currentPosition(): Duration = positionState.value

  override fun positionFlow(tick: Duration): Flow<Duration> =
    if (tick <= Duration.ZERO) {
      positionState
    } else {
      // Snapped to the tick grid so a collector only wakes when the displayed value changes.
      positionState.map { it.snapTo(tick) }.distinctUntilChanged()
    }

  override suspend fun setComposition(
    composition: EditComposition,
    startAt: Duration?,
    playWhenReady: Boolean,
  ): SetCompositionResult =
    suspendCancellableCoroutine { continuation ->
      val handle =
        engine.setComposition(
          SetCompositionRequest(composition, startAt, playWhenReady),
        ) { result -> continuation.resume(result) }
      continuation.invokeOnCancellation { handle.cancel() }
    }

  override fun setComposition(
    request: SetCompositionRequest,
    callback: SetCompositionCallback,
  ): Cancellable = engine.setComposition(request, callback)

  override fun seekTo(
    position: Duration,
    accuracy: SeekAccuracy,
  ) {
    // Optimistic, so a scrubber thumb does not snap backwards while the engine catches up.
    movePlayhead(position)
    engine.seekTo(position, accuracy)
  }

  override fun addStateListener(listener: PlayerStateListener): Cancellable =
    engine.addListener(
      object : EngineListener {
        override fun onStateChanged(state: PlayerState) = listener.onStateChanged(state)

        override fun onEvent(event: PlaybackEvent) = Unit

        override fun onPosition(position: Duration) = Unit

        override fun onPreviewInfo(info: PreviewInfo) = Unit
      },
    )

  override fun addEventListener(listener: PlaybackEventListener): Cancellable =
    engine.addListener(
      object : EngineListener {
        override fun onStateChanged(state: PlayerState) = Unit

        override fun onEvent(event: PlaybackEvent) = listener.onEvent(event)

        override fun onPosition(position: Duration) = Unit

        override fun onPreviewInfo(info: PreviewInfo) = Unit
      },
    )

  override fun addPositionListener(
    tick: Duration,
    listener: PlayerPositionListener,
  ): Cancellable {
    var lastTick: Duration? = null
    val onPosition: (Duration) -> Unit = { position ->
      val snapped = position.snapTo(tick)
      if (snapped != lastTick) {
        lastTick = snapped
        listener.onPosition(snapped)
      }
    }
    positionListeners = positionListeners + onPosition
    onPosition(positionState.value)
    return Cancellable { positionListeners = positionListeners - onPosition }
  }

  // The one place the playhead moves, so the flow and the listeners read the same value, an
  // optimistic seek included.
  private fun movePlayhead(position: Duration) {
    positionState.value = position
    positionListeners.forEach { it(position) }
  }

  override fun close() {
    listenerHandle.cancel()
    engine.dispose()
  }

  private companion object {
    const val EVENT_BUFFER = 64

    val EMPTY_PREVIEW_INFO =
      PreviewInfo(
        outputSize = Size(0, 0),
        renderScale = 1f,
        parity = EffectParity.Exact,
        parityNotes = emptyList(),
        fidelity = emptyList(),
      )
  }
}

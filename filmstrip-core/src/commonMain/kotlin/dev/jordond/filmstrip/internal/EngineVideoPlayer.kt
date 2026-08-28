package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.player.EngineListener
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackEventListener
import dev.jordond.filmstrip.player.PlayerEngine
import dev.jordond.filmstrip.player.PlayerFeatures
import dev.jordond.filmstrip.player.PlayerPositionListener
import dev.jordond.filmstrip.player.PlayerState
import dev.jordond.filmstrip.player.PlayerStateListener
import dev.jordond.filmstrip.player.PreviewFrameReadback
import dev.jordond.filmstrip.player.PreviewInfo
import dev.jordond.filmstrip.player.PreviewQualityPolicy
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.time.Duration

// Turns an engine's callbacks into the flows and suspend members VideoPlayer exposes.
internal class EngineVideoPlayer(
  private val engine: PlayerEngine,
  initial: EditComposition,
) : VideoPlayer {
  private val _state = MutableStateFlow(PlayerState.Initial)
  private val _previewInfo = MutableStateFlow(EMPTY_PREVIEW_INFO)
  private val _events =
    MutableSharedFlow<PlaybackEvent>(
      replay = 0,
      extraBufferCapacity = EVENT_BUFFER,
    )
  private val positionState = MutableStateFlow(Duration.ZERO)

  private val listenerHandle: Cancellable =
    engine.addListener(
      object : EngineListener {
        override fun onStateChanged(state: PlayerState) {
          _state.value = state
        }

        override fun onEvent(event: PlaybackEvent) {
          _events.tryEmit(event)
        }

        override fun onPosition(position: Duration) {
          positionState.value = position
        }

        override fun onPreviewInfo(info: PreviewInfo) {
          _previewInfo.value = info
        }
      },
    )

  init {
    engine.setComposition(SetCompositionRequest(initial)) { }
  }

  override val state: StateFlow<PlayerState> get() = _state.asStateFlow()

  override val events: SharedFlow<PlaybackEvent> get() = _events.asSharedFlow()

  override val previewInfo: StateFlow<PreviewInfo> get() = _previewInfo.asStateFlow()

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

  override fun play(): Unit = engine.play()

  override fun pause(): Unit = engine.pause()

  override fun seekTo(
    position: Duration,
    accuracy: SeekAccuracy,
  ) {
    // Optimistic, so a scrubber thumb does not snap backwards while the engine catches up.
    positionState.value = position
    engine.seekTo(position, accuracy)
  }

  override fun stepFrames(frames: Int): Unit = engine.stepFrames(frames)

  override fun beginScrub(): Unit = engine.beginScrub()

  override fun endScrub(): Unit = engine.endScrub()

  override fun setVolume(volume: Float): Unit = engine.setVolume(volume)

  override fun setLoopRange(range: TimeRange?): Unit = engine.setLoopRange(range)

  override fun setPlaybackRange(range: TimeRange?): Unit = engine.setPlaybackRange(range)

  override fun setQualityPolicy(policy: PreviewQualityPolicy): Unit = engine.setQualityPolicy(policy)

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
    return engine.addListener(
      object : EngineListener {
        override fun onStateChanged(state: PlayerState) = Unit

        override fun onEvent(event: PlaybackEvent) = Unit

        override fun onPosition(position: Duration) {
          val snapped = position.snapTo(tick)
          if (snapped != lastTick) {
            lastTick = snapped
            listener.onPosition(snapped)
          }
        }

        override fun onPreviewInfo(info: PreviewInfo) = Unit
      },
    )
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

package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackEventListener
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.PlayerFeatures
import dev.jordond.filmstrip.player.PlayerPositionListener
import dev.jordond.filmstrip.player.PlayerState
import dev.jordond.filmstrip.player.PlayerStateListener
import dev.jordond.filmstrip.player.PreviewFrameReadback
import dev.jordond.filmstrip.player.PreviewInfo
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.player.ReadbackCallback
import dev.jordond.filmstrip.player.ReadbackResult
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
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Duration

// What preview() returns with no playback backend registered: it reports the missing artifact
// through state, the same way every other playback failure reaches the UI.
@OptIn(InternalFilmstripApi::class)
internal class MissingEnginePlayer(
  message: String,
) : VideoPlayer {
  private val error =
    PlaybackError.BackendMissing(artifact = PLAYER_ARTIFACT, message = message)

  private val _state =
    MutableStateFlow(PlayerState(PlaybackStatus.Error(error), false, false, false, null))
  private val _previewInfo = MutableStateFlow(EMPTY_PREVIEW_INFO)
  private val _events = MutableSharedFlow<PlaybackEvent>(replay = 0, extraBufferCapacity = 1)

  override val state: StateFlow<PlayerState> get() = _state

  override val events: SharedFlow<PlaybackEvent> get() = _events.asSharedFlow()

  override val previewInfo: StateFlow<PreviewInfo> get() = _previewInfo

  override val features: PlayerFeatures = PlayerFeatures(emptySet())

  override val readback: PreviewFrameReadback =
    PreviewFrameReadback { _, callback ->
      callback.onReadback(ReadbackResult.Failure(error))
      Cancellable { }
    }

  override val nativePlayer: Any? = null

  override fun currentPosition(): Duration = Duration.ZERO

  override fun positionFlow(tick: Duration): Flow<Duration> = flowOf(Duration.ZERO)

  override suspend fun setComposition(
    composition: EditComposition,
    startAt: Duration?,
    playWhenReady: Boolean,
  ): SetCompositionResult = SetCompositionResult.Failure(error)

  override fun setComposition(
    request: SetCompositionRequest,
    callback: SetCompositionCallback,
  ): Cancellable {
    callback.onResult(SetCompositionResult.Failure(error))
    return Cancellable { }
  }

  override fun play(): Unit = Unit

  override fun pause(): Unit = Unit

  override fun seekTo(
    position: Duration,
    accuracy: SeekAccuracy,
  ): Unit = Unit

  override fun stepFrames(frames: Int): Unit = Unit

  override fun beginScrub(): Unit = Unit

  override fun endScrub(): Unit = Unit

  override fun setVolume(volume: Float): Unit = Unit

  override fun setLoopRange(range: TimeRange?): Unit = Unit

  override fun setPlaybackRange(range: TimeRange?): Unit = Unit

  override fun setQualityPolicy(policy: PreviewQualityPolicy): Unit = Unit

  override fun addStateListener(listener: PlayerStateListener): Cancellable {
    listener.onStateChanged(_state.value)
    return Cancellable { }
  }

  override fun addEventListener(listener: PlaybackEventListener): Cancellable = Cancellable { }

  override fun addPositionListener(
    tick: Duration,
    listener: PlayerPositionListener,
  ): Cancellable = Cancellable { }

  override fun close(): Unit = Unit

  private companion object {
    const val PLAYER_ARTIFACT = "dev.jordond.filmstrip:filmstrip-player"

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

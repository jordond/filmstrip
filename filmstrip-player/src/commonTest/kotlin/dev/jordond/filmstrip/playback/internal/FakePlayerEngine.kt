package dev.jordond.filmstrip.playback.internal

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.player.EngineListener
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.PlayerFeatures
import dev.jordond.filmstrip.player.PlayerState
import dev.jordond.filmstrip.player.PreviewFrameReadback
import dev.jordond.filmstrip.player.PreviewInfo
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.SetCompositionCallback
import dev.jordond.filmstrip.player.SetCompositionRequest
import dev.jordond.filmstrip.player.SetCompositionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

// A backend with no platform under it. Everything the base calls down into is recorded, and the
// protected axis pushes are re-exposed so a test can drive them the way a real engine would.
internal class FakePlayerEngine(
  parent: CoroutineScope,
  positionTick: Duration = 16.milliseconds,
) : BasePlayerEngine(parent, positionTick) {
  val platform: FakePlatformSeek = FakePlatformSeek()
  val scrubbing: MutableList<Boolean> = mutableListOf()

  var position: Duration = Duration.ZERO
  var plays: Int = 0
    private set
  var pauses: Int = 0
    private set
  var releases: Int = 0
    private set

  override val id: String = "filmstrip.fake"

  override val features: PlayerFeatures = PlayerFeatures(emptySet())

  override val readback: PreviewFrameReadback = PreviewFrameReadback { _, _ -> Cancellable { } }

  override val nativePlayer: Any? = null

  // The engine's own scope, so a test can pin what dispose() takes down.
  fun launchOnEngine(block: suspend () -> Unit): Job = scope.launch { block() }

  override fun readPosition(): Duration = position

  override fun isSeekReady(): Boolean = platform.isReady

  override fun performSeek(
    position: Duration,
    accuracy: SeekAccuracy,
    onComplete: () -> Unit,
  ): Unit = platform.seek(position, accuracy, onComplete)

  override fun onPlay() {
    plays++
  }

  override fun onPause() {
    pauses++
  }

  override fun onRelease() {
    releases++
  }

  override fun onScrubbingChanged(scrubbing: Boolean) {
    this.scrubbing += scrubbing
  }

  override fun setComposition(
    request: SetCompositionRequest,
    callback: SetCompositionCallback,
  ): Cancellable {
    callback.onResult(SetCompositionResult.Superseded)
    return Cancellable { }
  }

  override fun stepFrames(frames: Int): Unit = Unit

  override fun setVolume(volume: Float): Unit = Unit

  override fun setLoopRange(range: TimeRange?): Unit = Unit

  override fun setPlaybackRange(range: TimeRange?): Unit = Unit

  override fun setQualityPolicy(policy: PreviewQualityPolicy): Unit = Unit

  fun becomeReady(duration: Duration) {
    setDuration(duration)
    setStatus(PlaybackStatus.Ready)
  }

  fun becomePreparing(): Unit = setStatus(PlaybackStatus.Preparing)

  fun fail(error: PlaybackError): Unit = setStatus(PlaybackStatus.Error(error))

  fun stall(stalled: Boolean): Unit = setStalled(stalled)

  fun reportExternal(playWhenReady: Boolean): Unit = reportExternalPlayWhenReady(playWhenReady)

  fun publishPreviewInfo(info: PreviewInfo): Unit = emitPreviewInfo(info)
}

// Records everything an engine pushes, in order.
internal class RecordingListener : EngineListener {
  val states: MutableList<PlayerState> = mutableListOf()
  val events: MutableList<PlaybackEvent> = mutableListOf()
  val positions: MutableList<Duration> = mutableListOf()
  val previewInfo: MutableList<PreviewInfo> = mutableListOf()

  val seekCompletions: List<PlaybackEvent.SeekCompleted>
    get() = events.filterIsInstance<PlaybackEvent.SeekCompleted>()

  val externalChanges: List<PlaybackEvent.ExternalPlayWhenReadyChanged>
    get() = events.filterIsInstance<PlaybackEvent.ExternalPlayWhenReadyChanged>()

  override fun onStateChanged(state: PlayerState) {
    states += state
  }

  override fun onEvent(event: PlaybackEvent) {
    events += event
  }

  override fun onPosition(position: Duration) {
    positions += position
  }

  override fun onPreviewInfo(info: PreviewInfo) {
    previewInfo += info
  }
}

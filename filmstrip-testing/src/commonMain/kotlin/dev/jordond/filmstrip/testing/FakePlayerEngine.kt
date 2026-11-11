package dev.jordond.filmstrip.testing

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.PlayerFeature
import dev.jordond.filmstrip.player.PlayerFeatures
import dev.jordond.filmstrip.player.PreviewFrameReadback
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.SetCompositionCallback
import dev.jordond.filmstrip.player.SetCompositionRequest
import dev.jordond.filmstrip.player.SetCompositionResult
import dev.jordond.filmstrip.player.internal.BasePlayerEngine
import dev.jordond.filmstrip.player.internal.frameStepLanding
import dev.jordond.filmstrip.player.internal.seekLandingOf
import dev.jordond.filmstrip.player.internal.shuttleBoundsOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlin.time.Duration
import kotlin.time.TimeMark

// An engine with a clock where a platform would be. The playhead runs on the test scheduler while the engine is
// playing, ready and not stalled, a seek lands on the frame grid, and the end of the edit stops it.
@OptIn(ExperimentalCoroutinesApi::class)
internal class FakePlayerEngine(
  private val testScope: TestScope,
  supported: Set<PlayerFeature>,
  private val frameRate: Int,
  private var length: Duration,
) : BasePlayerEngine(testScope, timeSource = testScope.testScheduler.timeSource) {
  val compositions: MutableList<Composition> = mutableListOf()

  var holdSeeks: Boolean = false

  var onSetComposition: ((SetCompositionRequest) -> SetCompositionResult)? = null

  private var status: PlaybackStatus = PlaybackStatus.Idle
  private var stalled = false
  private var anchor = Duration.ZERO
  private var running: TimeMark? = null
  private var ending: Job? = null
  private var heldSeek: (() -> Unit)? = null
  private var playbackRange: TimeRange? = null

  override val id: String = "filmstrip.fake"

  override val features: PlayerFeatures = PlayerFeatures(supported)

  override val readback: PreviewFrameReadback = PreviewFrameReadback { _, _ -> Cancellable { } }

  override val nativePlayer: Any? = null

  override fun readPosition(): Duration = (anchor + (running?.elapsedNow() ?: Duration.ZERO)).coerceAtMost(length)

  override fun landingOf(target: Duration): Duration = seekLandingOf(target, length, frameRate)

  override fun isSeekReady(): Boolean = status == PlaybackStatus.Ready || status == PlaybackStatus.Ended

  override fun performSeek(
    position: Duration,
    accuracy: SeekAccuracy,
    onComplete: (landed: Duration) -> Unit,
  ) {
    val landed = landingOf(position)
    val land = {
      moveTo(landed)
      if (status == PlaybackStatus.Ended) show(PlaybackStatus.Ready)
      onComplete(landed)
    }
    if (holdSeeks) heldSeek = land else scope.launch { land() }
  }

  fun landSeek() {
    val land = heldSeek ?: return
    heldSeek = null
    land()
  }

  override fun onPlay(): Unit = syncClock()

  override fun onPause(): Unit = syncClock()

  override fun onRelease() {
    ending?.cancel()
    running = null
  }

  override fun shuttleBounds(): ClosedRange<Duration> = shuttleBoundsOf(length, frameRate, playbackRange)

  override fun onStepFrames(frames: Int) {
    if (frames == 0) return
    val target = frameStepLanding(readPosition(), frames, length, frameRate) ?: return
    seekTo(target, SeekAccuracy.Exact)
  }

  override fun setComposition(
    request: SetCompositionRequest,
    callback: SetCompositionCallback,
  ): Cancellable {
    compositions += request.composition
    val result = onSetComposition?.invoke(request) ?: SetCompositionResult.Success(length)
    when (result) {
      is SetCompositionResult.Success -> {
        length = result.duration
        setDuration(result.duration)
        request.startAt?.let {
          moveTo(landingOf(it))
          movePlayhead(anchor)
        }
        show(PlaybackStatus.Ready)
        if (request.playWhenReady) play()
      }
      is SetCompositionResult.Failure -> {
        show(PlaybackStatus.Error(result.error))
      }
      SetCompositionResult.Superseded -> {
        // Nothing loads, so the player stays as it was.
      }
    }
    callback.onResult(result)
    return Cancellable { }
  }

  override fun setVolume(volume: Float): Unit = Unit

  override fun setLoopRange(range: TimeRange?): Unit = Unit

  override fun setPlaybackRange(range: TimeRange?) {
    playbackRange = range
  }

  override fun setQualityPolicy(policy: PreviewQualityPolicy): Unit = Unit

  override fun setExportSpec(spec: ExportSpec): Unit = Unit

  fun show(next: PlaybackStatus) {
    status = next
    setStatus(next)
    syncClock()
  }

  fun stall(next: Boolean) {
    stalled = next
    setStalled(next)
    syncClock()
  }

  fun lengthen(next: Duration) {
    length = next
    setDuration(next)
    restartClock()
  }

  fun fail(error: PlaybackError): Unit = show(PlaybackStatus.Error(error))

  fun emit(event: PlaybackEvent): Unit = emitEvent(event)

  private fun moveTo(position: Duration) {
    stopClock()
    anchor = position
    syncClock()
  }

  private fun restartClock() {
    if (running == null) return
    moveTo(readPosition())
  }

  private fun syncClock() {
    val shouldRun = isPlayWanted && status == PlaybackStatus.Ready && !stalled
    if (shouldRun && running == null) {
      running = testScope.testScheduler.timeSource.markNow()
      ending =
        scope.launch {
          delay(length - anchor)
          reachEnd()
        }
    } else if (!shouldRun && running != null) {
      anchor = readPosition()
      stopClock()
    }
  }

  private fun stopClock() {
    running = null
    ending?.cancel()
    ending = null
  }

  private fun reachEnd() {
    anchor = length
    running = null
    ending = null
    status = PlaybackStatus.Ended
    setStatus(PlaybackStatus.Ended)
    setPlayWhenReady(false)
    emitEvent(PlaybackEvent.Ended(length, length))
  }
}

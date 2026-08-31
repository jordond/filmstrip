package dev.jordond.filmstrip.playback.contract

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.edit.CompositionDiff
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.diff
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.playback.internal.BasePlayerEngine
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.PlayerFeature
import dev.jordond.filmstrip.player.PlayerFeatures
import dev.jordond.filmstrip.player.PreviewFrameReadback
import dev.jordond.filmstrip.player.PreviewInfo
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.SetCompositionCallback
import dev.jordond.filmstrip.player.SetCompositionRequest
import dev.jordond.filmstrip.player.SetCompositionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A backend with no platform under it, shaped the way a real one is.
 *
 * Unlike the fake in `BasePlayerEngineTest`, this one classifies a composition change, counts what
 * a platform load would have cost and completes a seek on its own timer. That is what makes it a
 * subject the contract suite can run against rather than a puppet a test drives step by step.
 */
internal class ContractFakeEngine(
  parent: CoroutineScope,
  private val seekLatency: Duration = SEEK_LATENCY,
) : BasePlayerEngine(parent) {
  var platformLoads: Int = 0
    private set

  /**
   * What the fake's pretend graph measured once the clips were laid down.
   *
   * Walked off the tracks rather than read back off [EditComposition.duration], so the cross check
   * in the suite compares two answers instead of one answer with itself.
   */
  var platformDuration: Duration? = null
    private set

  private var loaded: EditComposition? = null
  private var playhead: Duration = Duration.ZERO

  override val id: String = "filmstrip.contract-fake"

  override val features: PlayerFeatures = PlayerFeatures(setOf(PlayerFeature.FrameReadback))

  override val readback: PreviewFrameReadback = PreviewFrameReadback { _, _ -> Cancellable { } }

  override val nativePlayer: Any? = null

  override fun setComposition(
    request: SetCompositionRequest,
    callback: SetCompositionCallback,
  ): Cancellable {
    val change = diff(loaded, request.composition)
    val duration = request.composition.duration ?: UNTRIMMED_DURATION

    if (change == CompositionDiff.Structural) {
      platformLoads++
      setStatus(PlaybackStatus.Preparing)
    }

    loaded = request.composition
    platformDuration = layDown(request.composition)
    request.startAt?.let { playhead = it }

    if (change != CompositionDiff.Equal) {
      setDuration(duration)
      emitPreviewInfo(PREVIEW_INFO)
      setStatus(PlaybackStatus.Ready)
      emitEvent(PlaybackEvent.FirstFrameRendered)
    }

    if (request.playWhenReady) play()
    callback.onResult(SetCompositionResult.Success(duration))
    return Cancellable { }
  }

  override fun readPosition(): Duration = playhead

  override fun isSeekReady(): Boolean = loaded != null

  override fun performSeek(
    position: Duration,
    accuracy: SeekAccuracy,
    onComplete: () -> Unit,
  ) {
    scope.launch {
      delay(seekLatency)
      playhead = position
      onComplete()
    }
  }

  override fun onPlay(): Unit = Unit

  override fun onPause(): Unit = Unit

  override fun onRelease(): Unit = Unit

  override fun stepFrames(frames: Int): Unit = Unit

  override fun setVolume(volume: Float): Unit = Unit

  override fun setLoopRange(range: TimeRange?): Unit = Unit

  override fun setPlaybackRange(range: TimeRange?): Unit = Unit

  override fun setQualityPolicy(policy: PreviewQualityPolicy): Unit = Unit

  /**
   * Takes playback away the way a system callback would.
   *
   * Every occasion looks the same to a backend with no platform under it, so which one was staged
   * is not carried in. What the suite asserts is the single external change that follows.
   */
  fun raise() {
    reportExternalPlayWhenReady(false)
  }

  /**
   * Lays the clips end to end the way a platform graph would, and answers how long the result runs.
   *
   * Each track is walked from its own start, and the longest non-looping one wins, which is what a
   * graph that inserted the spans would report. A clip with no trim contributes the length the fake
   * would have probed for it.
   */
  private fun layDown(composition: EditComposition): Duration {
    if (composition.tracks.isEmpty()) return Duration.ZERO
    return composition.tracks
      .filterNot { it.looping }
      .maxOfOrNull { track ->
        track.clips.fold(track.start) { at, clip -> at + (clip.trim?.duration ?: UNTRIMMED_DURATION) }
      } ?: UNTRIMMED_DURATION
  }

  private companion object {
    // Long enough that a burst of seeks is genuinely outstanding when the next one arrives, and
    // short enough that a suite of them finishes in a moment.
    val SEEK_LATENCY = 5.milliseconds

    // What the fake would have probed for a composition whose clips are untrimmed.
    val UNTRIMMED_DURATION = 30.seconds

    // A frame the fake pretends to render at, so a suite reading what the preview delivers has a
    // real size to look at rather than a zero one every backend would pass.
    val PREVIEW_INFO =
      PreviewInfo(
        outputSize = Size(1280, 720),
        renderScale = 1f,
        parity = EffectParity.Exact,
        parityNotes = emptyList(),
        fidelity = emptyList(),
      )
  }
}

/**
 * The fake wired up as a contract subject.
 */
internal class FakeEngineUnderTest(
  scope: CoroutineScope,
) : EngineUnderTest {
  override val engine: ContractFakeEngine = ContractFakeEngine(scope)

  override val platformLoads: Int get() = engine.platformLoads

  override val platformDuration: Duration? get() = engine.platformDuration

  override fun stage(interruption: Interruption): Unit = engine.raise()
}

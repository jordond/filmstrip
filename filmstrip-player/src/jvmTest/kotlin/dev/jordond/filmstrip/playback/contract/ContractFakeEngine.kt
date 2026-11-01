package dev.jordond.filmstrip.playback.contract

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.edit.CompositionDiff
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.diff
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.outputSlotOf
import dev.jordond.filmstrip.internal.outputSlotTime
import dev.jordond.filmstrip.playback.internal.BasePlayerEngine
import dev.jordond.filmstrip.playback.internal.loopWrapOf
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A backend with no platform under it, shaped the way a real one is.
 *
 * Unlike the fake in `BasePlayerEngineTest`, this one classifies a composition change, counts what
 * a platform load would have cost, completes a seek on its own timer and plays one output slot per
 * frame in real time. That is what makes it a subject the contract suite can run against rather than
 * a puppet a test drives step by step.
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
  private var loopRange: TimeRange? = null
  private var transport: Job? = null
  private var isLanding = false

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
    onComplete: (landed: Duration) -> Unit,
  ) {
    isLanding = true
    scope.launch {
      delay(seekLatency)
      playhead = landingOf(position)
      isLanding = false
      onComplete(playhead)
    }
  }

  /**
   * Where a seek to [position] lands, which is the output slot it rounds to at [FAKE_FRAME_RATE].
   *
   * Clamped to the slots the laid down clips cover, so a seek past the end lands on the last one.
   */
  private fun landingOf(position: Duration): Duration {
    val last = (outputSlotOf(platformDuration ?: Duration.ZERO, FAKE_FRAME_RATE) - 1).coerceAtLeast(0)
    return outputSlotTime(outputSlotOf(position, FAKE_FRAME_RATE).coerceIn(0, last), FAKE_FRAME_RATE)
  }

  override fun onPlay() {
    if (transport?.isActive == true) return
    transport =
      scope.launch {
        while (true) {
          delay(FRAME)
          if (!isLanding) advance()
        }
      }
  }

  override fun onPause() {
    transport?.cancel()
    transport = null
  }

  override fun onRelease(): Unit = Unit

  override fun stepFrames(frames: Int): Unit = Unit

  override fun setVolume(volume: Float): Unit = Unit

  override fun setLoopRange(range: TimeRange?) {
    loopRange = range
  }

  override fun setPlaybackRange(range: TimeRange?): Unit = Unit

  override fun setQualityPolicy(policy: PreviewQualityPolicy): Unit = Unit

  /**
   * Moves the playhead on one output slot at [FAKE_FRAME_RATE].
   *
   * Past the last slot of the loop range it wraps to the range's first, which is also what happens at the end of a
   * composition that ends inside the range. Past the composition's last slot with no range to wrap it ends.
   */
  private fun advance() {
    val length = platformDuration ?: return
    val next = outputSlotOf(playhead, FAKE_FRAME_RATE) + 1
    val range = loopRange
    val wrap = range?.let { loopWrapOf(it, length, FAKE_FRAME_RATE) }
    when {
      range != null && wrap != null && next > wrap.lastSlot -> {
        seekTo(outputSlotTime(wrap.firstSlot, FAKE_FRAME_RATE), SeekAccuracy.Exact)
        emitEvent(PlaybackEvent.RangeLooped(range))
      }
      next >= outputSlotOf(length, FAKE_FRAME_RATE) -> {
        end(length)
      }
      else -> {
        playhead = outputSlotTime(next, FAKE_FRAME_RATE)
      }
    }
  }

  private fun end(length: Duration) {
    playhead = length
    onPause()
    setStatus(PlaybackStatus.Ended)
    setPlayWhenReady(false)
    emitPosition(playhead)
    emitEvent(PlaybackEvent.Ended(playhead, length))
  }

  /**
   * Suspends until every seek and transport the fake launched has finished, going round again for one launched while
   * it waits.
   *
   * Never returns while the transport runs.
   */
  suspend fun awaitQuiet() {
    val launched = checkNotNull(scope.coroutineContext[Job])
    do {
      val running = launched.children.toList()
      running.joinAll()
    } while (running.isNotEmpty())
  }

  /**
   * Takes playback away the way a system callback would.
   *
   * Every occasion looks the same to a backend with no platform under it, so which one was staged
   * is not carried in. What the suite asserts is the single external change that follows.
   */
  fun raise() {
    reportExternalPlayWhenReady(false)
    onPause()
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

    val FRAME = 1.seconds / FAKE_FRAME_RATE

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
 * The output frame rate [ContractFakeEngine] pretends to present at.
 *
 * Every seek into the fake lands on this grid, the way a real backend lands on the one its lowered composition carries.
 */
internal const val FAKE_FRAME_RATE: Int = 30

/**
 * The fake wired up as a contract subject.
 */
internal class FakeEngineUnderTest(
  scope: CoroutineScope,
) : EngineUnderTest {
  override val engine: ContractFakeEngine = ContractFakeEngine(scope)

  override val platformLoads: Int get() = engine.platformLoads

  override val platformDuration: Duration? get() = engine.platformDuration

  override suspend fun awaitQuiet() {
    engine.awaitQuiet()
  }

  override fun stage(interruption: Interruption): Unit = engine.raise()
}

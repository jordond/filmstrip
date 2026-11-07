package dev.jordond.filmstrip.player.contract

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.CompositionDiff
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.diff
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.PlayerFeature
import dev.jordond.filmstrip.player.PlayerFeatures
import dev.jordond.filmstrip.player.PreviewFrameReadback
import dev.jordond.filmstrip.player.PreviewInfo
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.player.ReadbackFrame
import dev.jordond.filmstrip.player.ReadbackResult
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.SetCompositionCallback
import dev.jordond.filmstrip.player.SetCompositionRequest
import dev.jordond.filmstrip.player.SetCompositionResult
import dev.jordond.filmstrip.player.internal.BasePlayerEngine
import dev.jordond.filmstrip.player.internal.heldPosition
import dev.jordond.filmstrip.player.internal.loopWrapOf
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
  private val refuses: () -> Boolean = { false },
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

  override val features: PlayerFeatures =
    PlayerFeatures(setOf(PlayerFeature.FrameReadback, PlayerFeature.LiveParameterRedraw))

  override val readback: PreviewFrameReadback =
    PreviewFrameReadback { position, callback ->
      callback.onReadback(
        loaded?.let { ReadbackResult.Success(frameOf(it, position)) }
          ?: ReadbackResult.Failure(PlaybackError.SourceUnreadable("No composition is loaded.")),
      )
      Cancellable { }
    }

  override val nativePlayer: Any? = null

  override fun setComposition(
    request: SetCompositionRequest,
    callback: SetCompositionCallback,
  ): Cancellable {
    if (request.composition == FAKE_UNREADABLE || refuses()) {
      val error = PlaybackError.SourceUnreadable("The fake reads nothing from this composition.")
      setStatus(PlaybackStatus.Error(error))
      emitEvent(PlaybackEvent.Failed(error))
      callback.onResult(SetCompositionResult.Failure(error))
      return Cancellable { }
    }

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
      if (request.startAt == null && change == CompositionDiff.Structural) {
        playhead = heldPosition(playhead, duration, FAKE_FRAME_RATE, loopRange, isPlayWanted)
      }
      emitPosition(playhead)
      setDuration(duration)
      emitPreviewInfo(PREVIEW_INFO)
      setStatus(PlaybackStatus.Ready)
      if (change == CompositionDiff.ParametersOnly) emitFrameRedrawn() else emitEvent(PlaybackEvent.FirstFrameRendered)
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

  override fun setExportSpec(spec: ExportSpec): Unit = Unit

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

  private fun frameOf(
    composition: EditComposition,
    position: Duration,
  ): ReadbackFrame {
    val channel = if (composition.effects.isEmpty()) LIT_CHANNEL else DARK_CHANNEL
    return ReadbackFrame(byteArrayOf(channel, channel, channel, -1), Size(1, 1), position, ColorSpace.Bt709, 1f)
  }

  private companion object {
    // What a frame reads with no effect on it and with one, so a parameter edit moves the pixels a surface reads.
    const val LIT_CHANNEL: Byte = 100
    const val DARK_CHANNEL: Byte = 20

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
 * The one composition [ContractFakeEngine] refuses to load.
 *
 * The fake reads no bytes, so nothing about a source can stop it the way it stops a real backend.
 */
internal val FAKE_UNREADABLE: EditComposition =
  EditComposition(tracks = listOf(Track(listOf(Clip(MediaSource.of("unreadable.mp4"))))))

/**
 * The fake wired up as a contract subject.
 *
 * @param scope The dispatcher the fake is confined to.
 * @param refuses Asked on each load whether to refuse it, whatever it holds.
 */
internal class FakeEngineUnderTest(
  scope: CoroutineScope,
  refuses: () -> Boolean = { false },
) : EngineUnderTest {
  override val engine: ContractFakeEngine = ContractFakeEngine(scope, refuses = refuses)

  override val platformLoads: Int get() = engine.platformLoads

  override val platformDuration: Duration? get() = engine.platformDuration

  override suspend fun awaitQuiet() {
    engine.awaitQuiet()
  }

  override fun stage(interruption: Interruption): Unit = engine.raise()
}

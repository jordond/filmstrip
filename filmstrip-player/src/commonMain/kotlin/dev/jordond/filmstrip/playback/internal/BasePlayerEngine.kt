package dev.jordond.filmstrip.playback.internal

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.player.EngineListener
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.PlayerEngine
import dev.jordond.filmstrip.player.PlayerState
import dev.jordond.filmstrip.player.PreviewInfo
import dev.jordond.filmstrip.player.SeekAccuracy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The half of a [PlayerEngine] that has nothing to do with a platform.
 *
 * A backend subclasses this and answers for decoding, rendering and transport. Everything a
 * listener sees is assembled here: the listener list, the [PlayerState] snapshot, the seek chase,
 * the scrub policy and the position ticker.
 *
 * No backend assembles a snapshot. A subclass moves one axis at a time through [setStatus],
 * [setStalled], [setSeeking], [setPlayWhenReady] and [setDuration], and the snapshot is rebuilt
 * here from all five, so the "never torn" promise holds by construction rather than by four
 * backends each remembering to be careful. [PlayerState]'s constructor is marked internal, so a
 * backend cannot route around this even by accident.
 *
 * Not thread safe. Confine every call, including platform callbacks, to [scope]'s dispatcher.
 *
 * @param parent The scope this engine's own is a child of. Its dispatcher is where the position
 *   ticker runs, and where every call must arrive.
 * @param positionTick How often the playhead is read while playing.
 */
internal abstract class BasePlayerEngine(
  parent: CoroutineScope,
  positionTick: Duration = DEFAULT_POSITION_TICK,
) : PlayerEngine {
  /**
   * Where this engine launches, and what [dispose] takes down.
   *
   * A child of the scope handed in. Everything an engine launches outlives the call that started
   * it, a debounced seek or a plan in flight included, and cancelling this leaves the caller's own
   * scope running.
   */
  protected val scope: CoroutineScope =
    CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))

  private var listeners: List<EngineListener> = emptyList()
  private var isPublishing = false

  private var status: PlaybackStatus = PlaybackStatus.Idle
  private var playWhenReady = false
  private var isStalled = false
  private var isSeeking = false
  private var duration: Duration? = null

  private var snapshot: PlayerState = PlayerState.Initial
  private var previewInfo: PreviewInfo? = null
  private var isReleased = false

  private var isScrubbing = false
  private var scrubTarget: Duration? = null

  private val ticker =
    PositionTicker(
      scope = scope,
      interval = positionTick,
      read = { readPosition() },
      emit = { position -> listeners.forEach { it.onPosition(position) } },
    )

  private val chase =
    SeekChase(
      platformSeek = { position, accuracy, onComplete -> performSeek(position, accuracy, onComplete) },
      isReady = { isSeekReady() },
      onResolved = ::onSeekResolved,
    )

  /**
   * The playhead, read straight from the platform.
   */
  protected abstract fun readPosition(): Duration

  /**
   * Whether the platform can seek right now.
   *
   * A request made while this is false waits rather than being dropped. Call [onSeekReady] once it
   * turns true, or move [setStatus] to [PlaybackStatus.Ready], which nudges the chase for you.
   */
  protected abstract fun isSeekReady(): Boolean

  /**
   * Issues one seek and calls [onComplete] when its frame is presented.
   *
   * Called at most once at a time. A seek still running when a newer one is issued is the
   * platform's to cancel, and its [onComplete] may then never arrive.
   */
  protected abstract fun performSeek(
    position: Duration,
    accuracy: SeekAccuracy,
    onComplete: () -> Unit,
  )

  /**
   * Starts the platform transport. [PlayerState.playWhenReady] is already true.
   */
  protected abstract fun onPlay()

  /**
   * Stops the platform transport. [PlayerState.playWhenReady] is already false.
   */
  protected abstract fun onPause()

  /**
   * Releases platform resources. Called once, after the last snapshot has been delivered.
   */
  protected abstract fun onRelease()

  /**
   * Reacts to scrubbing starting or stopping, for a backend with a scrubbing mode of its own.
   *
   * Relaxing seek accuracy is handled here and needs no override.
   */
  protected open fun onScrubbingChanged(scrubbing: Boolean): Unit = Unit

  final override fun addListener(listener: EngineListener): Cancellable {
    listeners = listeners + listener
    listener.onStateChanged(snapshot)
    previewInfo?.let(listener::onPreviewInfo)
    return Cancellable { listeners = listeners - listener }
  }

  final override fun play() {
    setPlayWhenReady(true)
    onPlay()
  }

  final override fun pause() {
    setPlayWhenReady(false)
    onPause()
  }

  final override fun seekTo(
    position: Duration,
    accuracy: SeekAccuracy,
  ) {
    if (isScrubbing) scrubTarget = position
    chase.request(position, if (isScrubbing) SeekAccuracy.Nearest else accuracy)
    setSeeking(chase.isSeeking)
  }

  final override fun beginScrub() {
    if (isScrubbing) return
    isScrubbing = true
    scrubTarget = null
    onScrubbingChanged(true)
  }

  final override fun endScrub() {
    if (!isScrubbing) return
    isScrubbing = false
    onScrubbingChanged(false)

    // Every seek taken during the scrub was relaxed to Nearest, so the frame on screen is a sync
    // sample rather than the one under the finger. Settling is a fresh request and resolves like
    // any other.
    val target = scrubTarget ?: return
    scrubTarget = null
    chase.request(target, SeekAccuracy.Exact)
    setSeeking(chase.isSeeking)
  }

  final override fun dispose() {
    if (isReleased) return
    ticker.stop()
    chase.release()
    setSeeking(false)
    setStatus(PlaybackStatus.Released)
    isReleased = true
    listeners = emptyList()
    onRelease()
    // After onRelease, so a backend tears its platform down against the state it was left in rather
    // than racing a cancellation through it.
    scope.cancel()
  }

  /**
   * Moves the lifecycle axis.
   *
   * A status that cannot answer a seek resolves everything the chase is holding, so a rebuild or a
   * failure never leaves a caller waiting on a completion that cannot come.
   */
  protected fun setStatus(status: PlaybackStatus) {
    // The chase runs whether or not the axis moved. A backend that reaches Ready, is not yet
    // seekable, and says Ready again once it is would otherwise leave a queued seek waiting
    // forever behind an early return.
    val moved = this.status != status
    this.status = status
    if (moved) publish()

    if (status == PlaybackStatus.Ready) {
      chase.onReady()
    } else if (status != PlaybackStatus.Ended) {
      chase.release()
      setSeeking(false)
    }
  }

  /**
   * Moves the data axis, which is what a spinner reads.
   */
  protected fun setStalled(stalled: Boolean) {
    if (isStalled == stalled) return
    isStalled = stalled
    publish()
  }

  /**
   * Moves the seeking axis.
   *
   * [seekTo] already drives this. A backend only needs it for a seek it issues outside the chase.
   */
  protected fun setSeeking(seeking: Boolean) {
    if (isSeeking == seeking) return
    isSeeking = seeking
    publish()
  }

  /**
   * Moves the intent axis.
   *
   * [play] and [pause] already drive this. Use [reportExternalPlayWhenReady] for a change that came
   * from outside filmstrip.
   */
  protected fun setPlayWhenReady(playWhenReady: Boolean) {
    if (this.playWhenReady == playWhenReady) return
    this.playWhenReady = playWhenReady
    publish()
  }

  /**
   * Records the loaded composition's duration, or null once nothing is loaded.
   */
  protected fun setDuration(duration: Duration?) {
    if (this.duration == duration) return
    this.duration = duration
    publish()
  }

  /**
   * Records that something outside filmstrip changed whether playback is wanted.
   *
   * A backend reports the occasion and nothing else. Flipping the snapshot and emitting
   * [PlaybackEvent.ExternalPlayWhenReadyChanged] happen here, together, and only when the value
   * actually changed, so two occasions landing at once read as one interruption.
   */
  protected fun reportExternalPlayWhenReady(playWhenReady: Boolean) {
    if (this.playWhenReady == playWhenReady) return
    setPlayWhenReady(playWhenReady)
    emitEvent(PlaybackEvent.ExternalPlayWhenReadyChanged(playWhenReady))
  }

  /**
   * Dispatches an edge fact to every listener.
   */
  protected fun emitEvent(event: PlaybackEvent) {
    if (isReleased) return
    listeners.forEach { it.onEvent(event) }
  }

  /**
   * Dispatches what the preview is delivering, and replays it to a listener registering later.
   */
  protected fun emitPreviewInfo(info: PreviewInfo) {
    if (isReleased) return
    previewInfo = info
    listeners.forEach { it.onPreviewInfo(info) }
  }

  /**
   * Pushes one playhead reading outside the ticker, for a jump the ticker would not catch.
   */
  protected fun emitPosition(position: Duration) {
    if (isReleased) return
    listeners.forEach { it.onPosition(position) }
  }

  /**
   * Dispatches a seek that was waiting on [isSeekReady].
   */
  protected fun onSeekReady() {
    chase.onReady()
  }

  private fun onSeekResolved(
    position: Duration,
    resolution: SeekResolution,
  ) {
    // Both resolutions emit. A superseded request never reached the platform, so its completion is
    // synthesised, and a caller that waits for one before issuing the next seek would otherwise
    // wedge on the first request a burst threw away.
    if (resolution == SeekResolution.Landed) emitPosition(position)
    emitEvent(PlaybackEvent.SeekCompleted(position))
    setSeeking(chase.isSeeking)
  }

  private fun publish() {
    if (isReleased) return
    val next = PlayerState(status, playWhenReady, isStalled, isSeeking, duration)
    if (next == snapshot) return
    snapshot = next
    if (next.isPlaying) ticker.start() else ticker.stop()

    // A listener that changes an axis from inside onStateChanged re-enters here. The nested call
    // records the newer snapshot and returns, and this loop delivers it, so no listener is left
    // holding a state older than the one a sibling already saw.
    if (isPublishing) return
    isPublishing = true
    try {
      var delivered = next
      while (true) {
        val recipients = listeners
        // Re-read on each call: a listener that cancelled during this dispatch stops hearing.
        recipients.forEach { if (it in listeners) it.onStateChanged(delivered) }
        if (snapshot == delivered) break
        delivered = snapshot
      }
    } finally {
      isPublishing = false
    }
  }

  private companion object {
    // Roughly display rate. positionFlow(tick) snaps this down to whatever a collector asked for.
    val DEFAULT_POSITION_TICK = 16.milliseconds
  }
}

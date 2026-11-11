package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.player.EngineListener
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.PlayerEngine
import dev.jordond.filmstrip.player.PlayerFeature
import dev.jordond.filmstrip.player.PlayerState
import dev.jordond.filmstrip.player.PreviewInfo
import dev.jordond.filmstrip.player.SeekAccuracy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * The half of a [PlayerEngine] that has nothing to do with a platform.
 *
 * A backend subclasses this and answers for decoding, rendering and transport. Everything a
 * listener sees is assembled here: the listener list, the [PlayerState] snapshot, the seek chase,
 * the scrub policy, the shuttle and the position ticker.
 *
 * No backend assembles a snapshot. A subclass moves one axis at a time through [setStatus],
 * [setStalled], [setSeeking], [setPlayWhenReady] and [setDuration], and the snapshot is rebuilt
 * here from all of them, so the "never torn" promise holds by construction. [PlayerState]'s
 * constructor is marked internal, so a backend cannot route around this even by accident.
 *
 * Not thread safe. Confine every call, including platform callbacks, to [scope]'s dispatcher.
 *
 * @param parent The scope this engine's own is a child of. Its dispatcher is where the position
 *   ticker runs, and where every call must arrive.
 * @param positionTick How often the playhead is read while playing.
 * @param timeSource The clock a shuttle's playhead is measured on.
 */
internal abstract class BasePlayerEngine(
  parent: CoroutineScope,
  positionTick: Duration = DEFAULT_POSITION_TICK,
  timeSource: TimeSource = TimeSource.Monotonic,
) : PlayerEngine {
  /**
   * Whether playback is wanted right now, which is [PlayerState.playWhenReady] on the snapshot.
   */
  protected val isPlayWanted: Boolean get() = playWhenReady

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
  private var shuttleRate: Float? = null

  private var snapshot: PlayerState = PlayerState.Initial
  private var previewInfo: PreviewInfo? = null
  private var isReleased = false

  /**
   * Whether the lifecycle axis is on [PlaybackStatus.Error], so a load knows there is a failure to clear and an equal
   * edit knows the graph standing is not worth keeping.
   */
  protected val isFailed: Boolean get() = status is PlaybackStatus.Error

  private var isScrubbing = false
  private var scrubTarget: Duration? = null
  private var heldPlayhead: Duration? = null

  private val ticker =
    PositionTicker(
      scope = scope,
      interval = positionTick,
      read = { reportedPosition },
      emit = { position -> listeners.forEach { it.onPosition(position) } },
    )

  private val chase =
    SeekChase(
      platformSeek = { position, accuracy, wrap, onComplete ->
        val outer = isWrapSeek
        isWrapSeek = wrap
        try {
          performSeek(position, accuracy, onComplete)
        } finally {
          isWrapSeek = outer
        }
      },
      isReady = { isSeekReady() },
      onResolved = ::onSeekResolved,
    )

  private val pump =
    ShuttlePump(
      scope = scope,
      pollInterval = positionTick,
      timeSource = timeSource,
      bounds = { shuttleBounds() },
      landingOf = { landingOf(it) },
      canPresent = { isSeekReady() && !chase.isSeeking },
      present = { position, onPresented ->
        ridingSeeks = ridingSeeks + waitingSeeks
        waitingSeeks = emptyList()
        presentFrame(position, onPresented)
      },
      onFrame = ::onShuttleFrame,
      onEdge = { edge, position -> emitEvent(PlaybackEvent.ShuttleReachedEdge(edge, position)) },
      onLateFrame = { landed -> if (!chase.isSeeking) emitPosition(landed) },
    )

  // Whether the shuttle running interrupted playback, which ending it starts again.
  private var resumeAfterShuttle = false

  // Seeks taken during a shuttle. One waits for the next frame to be asked for, then rides on it and completes when
  // it shows, so a frame asked for before the seek never completes it.
  private var waitingSeeks: List<Duration> = emptyList()
  private var ridingSeeks: List<Duration> = emptyList()

  /**
   * Whether the seek [performSeek] is issuing right now was asked for through [seekToWrap].
   *
   * Only meaningful inside [performSeek]. A wrap the chase held back until the platform could seek still reads true
   * when it finally goes out.
   */
  protected var isWrapSeek: Boolean = false
    private set

  /**
   * The playhead, read straight from the platform.
   */
  protected abstract fun readPosition(): Duration

  /**
   * The playhead as listeners read it.
   *
   * From a seek being asked for until it resolves this is the slot the newest target asked for lands on, by
   * [landingOf], whatever the platform reads, so a wrap never reports the range's end once it is decided. Otherwise it
   * is [readPosition].
   */
  protected val reportedPosition: Duration get() = heldPlayhead ?: readPosition()

  /**
   * The slot time an exact seek to [target] lands on, which is what the playhead holds while the seek is in flight.
   *
   * An engine with nothing loaded has no frame grid to land on, so this returns [target] unchanged.
   */
  protected open fun landingOf(target: Duration): Duration = target

  /**
   * Whether the platform can seek right now.
   *
   * A request made while this is false waits rather than being dropped. Call [onSeekReady] once it
   * turns true, or move [setStatus] to [PlaybackStatus.Ready], which nudges the chase for you.
   */
  protected abstract fun isSeekReady(): Boolean

  /**
   * Issues one seek and calls [onComplete] with where it landed once its frame is presented.
   *
   * [onComplete] carries the slot time the frame on screen sits on, as [PlatformSeek.seek] describes, and that is what
   * the playhead and [PlaybackEvent.SeekCompleted] report. Called at most once at a time. A seek still running when a
   * newer one is issued is the platform's to cancel, and its [onComplete] may then never arrive.
   */
  protected abstract fun performSeek(
    position: Duration,
    accuracy: SeekAccuracy,
    onComplete: (landed: Duration) -> Unit,
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

  /**
   * The times of the first and last frames a shuttle may show, as [shuttleBoundsOf] gives them, or null when nothing
   * is loaded.
   */
  protected open fun shuttleBounds(): ClosedRange<Duration>? = null

  /**
   * Shows the frame for [position] during a shuttle and calls [onPresented] once it is on screen.
   *
   * [onPresented] carries the slot time of the frame shown, the way [performSeek] reports a landing. Called at most
   * once at a time and never while a seek from [performSeek] is in flight. Goes through [performSeek] as an exact
   * seek unless a backend has a cheaper way to show a frame.
   */
  protected open fun presentFrame(
    position: Duration,
    onPresented: (landed: Duration) -> Unit,
  ): Unit = performSeek(position, SeekAccuracy.Exact, onPresented)

  /**
   * Reacts to a shuttle starting or stopping, for whatever makes a backend's frames cheap to show and for muting.
   *
   * The transport is already paused when a shuttle starts, and is started again after it stops when it was playing.
   */
  protected open fun onShuttleChanged(shuttling: Boolean): Unit = Unit

  /**
   * Steps by whole frames. A shuttle has already ended.
   */
  protected abstract fun onStepFrames(frames: Int)

  final override fun addListener(listener: EngineListener): Cancellable {
    listeners = listeners + listener
    listener.onStateChanged(snapshot)
    previewInfo?.let(listener::onPreviewInfo)
    return Cancellable { listeners = listeners - listener }
  }

  final override fun play() {
    if (shuttleRate != null) {
      finishShuttle(resume = true)
      return
    }
    setPlayWhenReady(true)
    onPlay()
  }

  final override fun pause() {
    if (shuttleRate != null) {
      finishShuttle(resume = false)
      return
    }
    setPlayWhenReady(false)
    onPause()
  }

  final override fun stepFrames(frames: Int) {
    endShuttle()
    onStepFrames(frames)
  }

  final override fun seekTo(
    position: Duration,
    accuracy: SeekAccuracy,
  ) {
    if (shuttleRate != null) {
      seekShuttle(position)
      return
    }
    heldPlayhead = landingOf(position)
    if (isScrubbing) scrubTarget = position
    chase.request(position, if (isScrubbing) SeekAccuracy.Nearest else accuracy)
    setSeeking(chase.isSeeking)
  }

  /**
   * Seeks exactly to [position] as a loop wrap, through the same chase as [seekTo].
   *
   * The request is marked as a wrap for as long as the chase holds it, so [isWrapSeek] reads true in the [performSeek]
   * that finally issues it. The playhead reports where [position] lands straight away and holds it until the seek
   * resolves, the way [seekTo] holds its target.
   */
  protected fun seekToWrap(position: Duration) {
    heldPlayhead = landingOf(position)
    emitPosition(position)
    if (isScrubbing) scrubTarget = position
    chase.request(position, if (isScrubbing) SeekAccuracy.Nearest else SeekAccuracy.Exact, wrap = true)
    setSeeking(chase.isSeeking)
  }

  final override fun beginScrub() {
    if (isScrubbing) return
    endShuttle()
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
    heldPlayhead = landingOf(target)
    chase.request(target, SeekAccuracy.Exact)
    setSeeking(chase.isSeeking)
  }

  final override fun beginShuttle(rate: Float) {
    requireShuttleRate(rate)
    if (isReleased || !features.supports(PlayerFeature.Shuttle)) return
    if (status != PlaybackStatus.Ready && status != PlaybackStatus.Ended) return
    if (shuttleBounds() == null) return

    if (shuttleRate == null) endScrub()
    val origin = reportedPosition
    if (shuttleRate == null) {
      resumeAfterShuttle = playWhenReady
      if (playWhenReady) {
        setPlayWhenReady(false)
        onPause()
      }
      onShuttleChanged(true)
      heldPlayhead = landingOf(origin)
    }
    shuttleRate = rate
    publish()
    pump.begin(rate, origin)
  }

  final override fun endShuttle() {
    if (shuttleRate == null) return
    finishShuttle(resume = resumeAfterShuttle)
  }

  // Leaves the shuttle with the frame on screen as the playhead, then starts the transport when asked to.
  private fun finishShuttle(resume: Boolean) {
    pump.end()
    shuttleRate = null
    resumeAfterShuttle = false
    ridingSeeks = ridingSeeks + waitingSeeks
    waitingSeeks = emptyList()
    completeShuttleSeeks()
    if (!chase.isSeeking) heldPlayhead = null
    onShuttleChanged(false)
    publish()
    if (resume) {
      setPlayWhenReady(true)
      onPlay()
    }
  }

  // A seek taken while shuttling moves the shuttle and never reaches the chase. It completes on the next frame asked
  // for, or straight away when the frame it asks for is the one showing.
  private fun seekShuttle(position: Duration) {
    val landing = landingOf(position)
    waitingSeeks = waitingSeeks + landing
    heldPlayhead = landing
    pump.moveTo(position)
    if (waitingSeeks.isNotEmpty() && !pump.isInFlight && pump.shown == landing) {
      ridingSeeks = ridingSeeks + waitingSeeks
      waitingSeeks = emptyList()
      completeShuttleSeeks()
    }
  }

  private fun onShuttleFrame(landed: Duration) {
    heldPlayhead = landed
    emitPosition(landed)
    completeShuttleSeeks()
  }

  private fun completeShuttleSeeks() {
    val completed = ridingSeeks
    ridingSeeks = emptyList()
    completed.forEach { emitEvent(PlaybackEvent.SeekCompleted(it)) }
  }

  final override fun dispose() {
    if (isReleased) return
    ticker.stop()
    pump.end()
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
    if (moved) {
      heldPlayhead = null
      publish()
    }

    if (status == PlaybackStatus.Ready) {
      chase.onReady()
      pump.kick()
    } else if (status != PlaybackStatus.Ended) {
      endShuttle()
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
   *
   * While a seek holds the playhead the held target is pushed instead, so a frame shown or a seek landing on the way
   * to it never reports somewhere else.
   */
  protected fun emitPosition(position: Duration) {
    if (isReleased) return
    val reported = heldPlayhead ?: position
    listeners.forEach { it.onPosition(reported) }
  }

  /**
   * Reports a jump to [position] the backend made outside the chase, and pushes it.
   *
   * A seek still holding the playhead holds [position] from here on instead of its own target, until it resolves. That
   * is a wrap sent on to a loop range that moved while it was in flight, or a load moving to where it starts.
   */
  protected fun movePlayhead(position: Duration) {
    if (heldPlayhead != null) heldPlayhead = position
    emitPosition(position)
  }

  /**
   * Reports that the frame at the playhead changed with the playhead standing still.
   *
   * Call it once [PlayerEngine.readback] answers with the changed frame, since that is what the listener goes on to
   * read.
   */
  protected fun emitFrameRedrawn() {
    if (isReleased) return
    listeners.forEach { it.onFrameRedrawn() }
  }

  /**
   * Dispatches a seek that was waiting on [isSeekReady].
   */
  protected fun onSeekReady() {
    chase.onReady()
    pump.kick()
  }

  private fun onSeekResolved(
    position: Duration,
    resolution: SeekResolution,
  ) {
    // Both resolutions emit. A superseded request never reached the platform, so its completion is
    // synthesised, and a caller that waits for one before issuing the next seek would otherwise
    // wedge on the first request a burst threw away.
    if (!chase.isSeeking) heldPlayhead = null
    if (resolution == SeekResolution.Landed) emitPosition(position)
    emitEvent(PlaybackEvent.SeekCompleted(position))
    setSeeking(chase.isSeeking)
    pump.kick()
  }

  private fun publish() {
    if (isReleased) return
    val next = PlayerState(status, playWhenReady, isStalled, isSeeking, duration, shuttleRate)
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

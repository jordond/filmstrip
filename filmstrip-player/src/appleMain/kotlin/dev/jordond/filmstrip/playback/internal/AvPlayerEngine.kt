package dev.jordond.filmstrip.playback.internal

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.avfoundation.internal.AvComposition
import dev.jordond.filmstrip.avfoundation.internal.toAvComposition
import dev.jordond.filmstrip.avfoundation.internal.toCMTime
import dev.jordond.filmstrip.avfoundation.internal.toDuration
import dev.jordond.filmstrip.edit.CompositionDiff
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.diff
import dev.jordond.filmstrip.internal.outputSlotTime
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.PlayerConfig
import dev.jordond.filmstrip.player.PlayerFeature
import dev.jordond.filmstrip.player.PlayerFeatures
import dev.jordond.filmstrip.player.PreviewFrameReadback
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.SetCompositionCallback
import dev.jordond.filmstrip.player.SetCompositionRequest
import dev.jordond.filmstrip.player.SetCompositionResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemPlaybackStalledNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVPlayerTimeControlStatusPaused
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.AVPlayerWaitingToMinimizeStallsReason
import platform.AVFoundation.addBoundaryTimeObserverForTimes
import platform.AVFoundation.addPeriodicTimeObserverForInterval
import platform.AVFoundation.audioMix
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.playbackBufferEmpty
import platform.AVFoundation.playbackLikelyToKeepUp
import platform.AVFoundation.reasonForWaitingToPlay
import platform.AVFoundation.removeTimeObserver
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.setForwardPlaybackEndTime
import platform.AVFoundation.setVolume
import platform.AVFoundation.timeControlStatus
import platform.AVFoundation.valueWithCMTime
import platform.AVFoundation.videoComposition
import platform.CoreMedia.CMTimeGetSeconds
import platform.Foundation.NSValue
import platform.darwin.dispatch_queue_create
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The AVFoundation preview engine.
 *
 * Everything a frame goes through is the object an export of the same edit would run: one
 * `ResolvedComposition` lowered by [toAvComposition] into one `AVMutableComposition`, one
 * `AVVideoComposition` carrying one [dev.jordond.filmstrip.avfoundation.internal.CoreImageChain],
 * and an `AVPlayerItem` over both. Preview and export are not two pipelines that agree, they are
 * the same one.
 *
 * Not thread safe, and neither is [BasePlayerEngine]. Every platform callback hops onto [scope]
 * before it touches anything here, and nothing here dispatches to the main queue: a host test drives
 * the engine from its own dispatcher and has no main run loop to pump.
 *
 * @param parent The scope this engine's own is a child of, on the dispatcher everything is
 *   confined to.
 * @param planner Lowers an edit the way an export of it would be lowered.
 * @param config How the player was built.
 */
@OptIn(ExperimentalForeignApi::class)
internal class AvPlayerEngine(
  parent: CoroutineScope,
  private val planner: AvPreviewPlanner,
  config: PlayerConfig,
) : BasePlayerEngine(parent) {
  private val player = AVPlayer()
  private val observer = AvKeyValueObserver { scope.launch { onPlatformChanged() } }
  private val callbackQueue = dispatch_queue_create("dev.jordond.filmstrip.player", null)
  private val interruptions = AvInterruptions(player) { scope.launch { onInterrupted() } }

  private var itemNotifications: List<AvNotificationObserver> = emptyList()
  private var positionToken: Any? = null
  private var loopToken: Any? = null

  private var loaded: EditComposition? = null
  private var plan: AvPreviewPlan? = null
  private var lowered: AvComposition? = null
  private var item: AVPlayerItem? = null
  private var surfaceLayer: AVPlayerLayer? = null
  private var layerReady = false
  private var firstFrameEmitted = false
  private var pending: PendingLoad? = null
  private var policy: PreviewQualityPolicy = config.qualityPolicy
  private var loopRange: TimeRange? = null
  private var reachedEnd = false
  private var seekInFlight = false
  private var playing = false
  private var outsidePlayback = false
  private var stopReadingDue = false
  private var playhead: Duration = Duration.ZERO
  private val wrapTick = PositionTicker(scope, POSITION_INTERVAL, read = ::itemTime, emit = ::onTick)

  /**
   * How many times the AVFoundation graph has been built or rebuilt.
   *
   * A structural edit costs a fresh `AVPlayerItem` and a decoder reinitialisation. A
   * parameters-only edit and an equal one cost nothing here, which is what this counts.
   */
  var platformLoads: Int = 0
    private set

  /**
   * The duration the player's own item reports, or null while AVFoundation has settled none.
   *
   * Read off the item rather than off the composition that was handed to it, so a cross check
   * against the resolved duration compares AVFoundation's own reading with filmstrip's rather than
   * comparing filmstrip's with itself. An item whose asset has not finished loading answers with an
   * indefinite time, which is no answer and reads as null.
   */
  val platformDuration: Duration?
    get() {
      val seconds = item?.let { CMTimeGetSeconds(it.duration) } ?: return null
      if (seconds.isNaN() || seconds.isInfinite() || seconds <= 0.0) return null
      return seconds.seconds
    }

  /**
   * The player the engine drives.
   *
   * A test stages a rate change by posting the notification the system posts, and the system scopes
   * that one to the player it came from, so a stage that used any other object would reach nothing.
   */
  val platformPlayer: AVPlayer get() = player

  override val id: String = "filmstrip.avfoundation"

  // HdrPreview is left unclaimed: whether a grade survives to the panel is the display's answer,
  // and nothing here has measured it. A claimed feature that is not there reads as a frozen UI.
  override val features: PlayerFeatures =
    PlayerFeatures(
      setOf(
        PlayerFeature.FrameReadback,
        PlayerFeature.LiveParameterRedraw,
        PlayerFeature.FrameStepping,
      ),
    )

  override val readback: PreviewFrameReadback =
    AvFrameReadback(
      scope = scope,
      composition = { lowered },
      policy = { policy },
      renderScale = { plan?.info?.renderScale ?: 1f },
    )

  override val nativePlayer: Any get() = player

  init {
    engines[player] = this
    observer.observePlayer(player)
    positionToken =
      player.addPeriodicTimeObserverForInterval(POSITION_INTERVAL.toCMTime(), callbackQueue) { time ->
        // Also fires when playback stops, with where the item came to rest. That is the only reading kept from here.
        try {
          val position = time.toDuration()
          scope.launch { onStopReading(position) }
        } catch (
          @Suppress("SwallowedException", "TooGenericExceptionCaught") broken: Exception,
        ) {
          // An exception leaving here would cross back into AVFoundation and end the process.
        }
      }
  }

  override fun setComposition(
    request: SetCompositionRequest,
    callback: SetCompositionCallback,
  ): Cancellable {
    supersedePending()

    val change = diff(loaded, request.composition)
    if (change == CompositionDiff.Equal && lowered != null) {
      // Nothing at the platform level, not even a status move. A listener must not see this load.
      loaded = request.composition
      callback.onResult(SetCompositionResult.Success(plan?.resolved?.duration ?: Duration.ZERO))
      return Cancellable { }
    }

    val job =
      scope.launch {
        when (val result = planner.plan(request.composition, policy)) {
          is AvPlanResult.Refused -> {
            fail(result.error)
            settle(SetCompositionResult.Failure(result.error))
          }
          is AvPlanResult.Ready -> {
            apply(change, result.plan, request)
            settle(SetCompositionResult.Success(result.plan.resolved.duration))
          }
        }
      }

    pending = PendingLoad(job, callback)
    return Cancellable {
      pending?.takeIf { it.job === job }?.let { it.settled = true }
      job.cancel()
    }
  }

  // While playing this is the reading the wrap tick last checked, so the ticker never reports one past the loop's wrap
  // before the wrap is taken. Otherwise it is where the last seek, pause or load left the item.
  override fun readPosition(): Duration = playhead

  override fun isSeekReady(): Boolean = item?.status == AVPlayerItemStatusReadyToPlay

  override fun performSeek(
    position: Duration,
    accuracy: SeekAccuracy,
    onComplete: (landed: Duration) -> Unit,
  ) {
    val target = item
    val resolved = plan?.resolved
    if (target == null || resolved == null) {
      landSeek(position, onComplete)
      return
    }

    reachedEnd = false
    seekInFlight = true
    stopReadingDue = false
    val exact = accuracy == SeekAccuracy.Exact
    val landing = resolved.seekLandingOf(position)
    val tolerance = if (exact) Duration.ZERO else NEAREST_TOLERANCE
    target.seekToTime(
      time = (if (exact) landing else position).toCMTime(),
      toleranceBefore = tolerance.toCMTime(),
      toleranceAfter = tolerance.toCMTime(),
    ) { finished ->
      // Completed either way. AVFoundation answers a superseded seek immediately with false, and a
      // caller counting completions against requests must not be left waiting on the one it threw
      // away. The chase issues one seek at a time, so nothing here double-resolves.
      try {
        // An exact landing is handed back as the slot's own time, since currentTime() is a fraction of a tick off it
        // at a rate the timescale does not divide.
        val landed =
          when {
            !finished -> position
            exact -> landing
            else -> resolved.seekLandingOf(target.currentTime().toDuration())
          }
        scope.launch { landSeek(landed, onComplete) }
      } catch (
        @Suppress("SwallowedException", "TooGenericExceptionCaught") broken: Exception,
      ) {
        // Never let this reach the Objective-C frame that called it. The seek still has to complete, or a landing
        // that throws freezes the playhead and leaves the chase waiting for good.
        scope.launch { landSeek(position, onComplete) }
      }
    }
  }

  override fun onPlay() {
    reachedEnd = false
    playing = true
    outsidePlayback = false
    stopReadingDue = false
    player.play()
    wrapTick.start()
  }

  override fun onPause() {
    player.pause()
    stopPlaying()
  }

  override fun onRelease() {
    engines.remove(player)
    supersedePending()
    playing = false
    outsidePlayback = false
    wrapTick.stop()
    positionToken?.let { player.removeTimeObserver(it) }
    positionToken = null
    loopToken?.let { player.removeTimeObserver(it) }
    loopToken = null
    interruptions.dispose()
    detachItem()
    surfaceLayer = null
    observer.dispose()
    player.pause()
    player.replaceCurrentItemWithPlayerItem(null)
  }

  override fun stepFrames(frames: Int) {
    if (frames == 0 || item == null) return
    val landing = plan?.resolved?.frameStepLanding(readPosition(), frames) ?: return
    // Not stepByCount. Measured on the macOS host, one step from 300 ms at 30 fps put the item at 550 ms rather than on
    // the next output frame.
    seekTo(landing, SeekAccuracy.Exact)
  }

  override fun setVolume(volume: Float) {
    player.setVolume(volume.coerceIn(0f, 1f))
  }

  override fun setLoopRange(range: TimeRange?) {
    loopRange = range
    armLoopBoundary()
  }

  override fun setPlaybackRange(range: TimeRange?) {
    val target = item ?: return
    target.setForwardPlaybackEndTime((range?.endExclusive ?: Duration.ZERO).toCMTime())
    range?.start?.takeIf { readPosition() < it }?.let { seekTo(it, SeekAccuracy.Exact) }
  }

  override fun setQualityPolicy(policy: PreviewQualityPolicy) {
    if (this.policy == policy) return
    this.policy = policy

    // The cap moves the rendered frame, which moves the output format, and a chain cannot swap that
    // in place. The graph is rebuilt against the edit already loaded.
    val composition = loaded ?: return
    loaded = null
    setComposition(SetCompositionRequest(composition), { })
  }

  /**
   * Watches [layer] for the moment it starts showing this player's pixels.
   *
   * A surface calls this with the layer it draws into, and [PlaybackEvent.FirstFrameRendered] is
   * emitted off it.
   */
  fun attachSurfaceLayer(layer: AVPlayerLayer) {
    scope.launch { observeSurfaceLayer(layer) }
  }

  /**
   * Stops watching [layer], leaving a layer another surface has since attached alone.
   */
  fun detachSurfaceLayer(layer: AVPlayerLayer) {
    scope.launch { if (surfaceLayer == layer) observeSurfaceLayer(null) }
  }

  private fun observeSurfaceLayer(layer: AVPlayerLayer?) {
    surfaceLayer = layer
    layerReady = false
    observer.observeLayer(layer)
    onPlatformChanged()
  }

  /**
   * Applies one lowered plan, rebuilding only what the change actually costs.
   */
  private suspend fun apply(
    change: CompositionDiff,
    next: AvPreviewPlan,
    request: SetCompositionRequest,
  ) {
    val swappable = change == CompositionDiff.ParametersOnly && lowered?.chain != null
    if (swappable) {
      // Reassigning videoComposition on a playing item stalls the render, so the parameters reach
      // the frames through the chain the item already holds. The require() calls inside stand as
      // the assertion that a parameters-only diff really did move nothing structural.
      lowered?.chain?.updateParameters(next.resolved)
    } else {
      rebuild(next)
    }

    loaded = request.composition
    plan = next
    armLoopBoundary()
    setDuration(next.resolved.duration)
    emitPreviewInfo(next.info)
    val start = request.startAt?.let(next.resolved::seekLandingOf)
    start?.let { item?.seekToTime(it.toCMTime(), Duration.ZERO.toCMTime(), Duration.ZERO.toCMTime()) }
    // Nothing completes this seek or reports a fresh item's start, so the jump is reported here.
    if (start != null || !swappable) {
      stopReadingDue = false
      playhead = start ?: Duration.ZERO
      emitPosition(playhead)
    }
    onPlatformChanged()
    if (request.playWhenReady) play()
  }

  /**
   * Builds a fresh item over a fresh composition and hands it to the player.
   *
   * Never a mutation of the `AVMutableComposition` the player is already holding. Inserting a time
   * range into one under a live item is undefined.
   */
  private fun rebuild(next: AvPreviewPlan) {
    platformLoads++
    setStatus(PlaybackStatus.Preparing)
    detachItem()

    val av = next.resolved.toAvComposition()
    val fresh =
      AVPlayerItem(asset = av.composition).apply {
        videoComposition = av.videoComposition
        audioMix = av.audioMix
      }

    lowered = av
    item = fresh
    reachedEnd = false
    layerReady = false
    firstFrameEmitted = false
    observer.observeItem(fresh)
    itemNotifications =
      listOf(
        AvNotificationObserver(AVPlayerItemDidPlayToEndTimeNotification, fresh) { onReachedEnd() },
        AvNotificationObserver(AVPlayerItemFailedToPlayToEndTimeNotification, fresh) { onFailedToPlayToEnd() },
        AvNotificationObserver(AVPlayerItemPlaybackStalledNotification, fresh) { onStalled() },
      )
    player.replaceCurrentItemWithPlayerItem(fresh)
  }

  /**
   * Re-reads every platform property the snapshot is built from.
   *
   * Nothing is decoded from a notification, so the same read answers a status change, a buffer
   * change and a transport change alike, and two notifications landing together read as one.
   */
  private fun onPlatformChanged() {
    val target = item
    if (target == null) {
      setStatus(PlaybackStatus.Idle)
      return
    }

    setStalled(target.isStalled() || player.isWaitingOnData())
    when (target.status) {
      AVPlayerItemStatusFailed -> {
        val error = target.error
        fail(
          PlaybackError.Underlying(
            platformCode = error?.code?.toInt() ?: PlaybackError.Underlying.NO_PLATFORM_CODE,
            message = error?.localizedDescription ?: "AVFoundation could not play this composition.",
          ),
        )
      }
      AVPlayerItemStatusReadyToPlay -> {
        setStatus(if (reachedEnd) PlaybackStatus.Ended else PlaybackStatus.Ready)
      }
      else -> {
        setStatus(PlaybackStatus.Preparing)
      }
    }

    followTransport(target)
    reportFirstFrame(target)
  }

  /**
   * Starts or stops the wrap tick for playback the engine did not start, following the player's own transport.
   *
   * The app playing [nativePlayer] itself, the system's transport controls and AVFoundation resuming on its own all move
   * the player without [onPlay]. That playback moves the playhead but never wraps the loop range, and where it stops is
   * reported the way a pause is.
   */
  private fun followTransport(target: AVPlayerItem) {
    if (playing) return
    val advancing =
      target.status != AVPlayerItemStatusFailed && player.timeControlStatus != AVPlayerTimeControlStatusPaused
    if (advancing == outsidePlayback) return
    if (!advancing) {
      stopPlaying()
      return
    }
    outsidePlayback = true
    stopReadingDue = false
    wrapTick.start()
  }

  /**
   * Emits [PlaybackEvent.FirstFrameRendered] on the edge where an attached layer starts showing
   * [target]'s pixels, once per loaded composition.
   *
   * Readiness is held against the item's own status, so a layer still showing the previous
   * composition while a fresh item prepares does not count as the new one arriving.
   */
  private fun reportFirstFrame(target: AVPlayerItem) {
    val showing = surfaceLayer?.readyForDisplay == true && target.status == AVPlayerItemStatusReadyToPlay
    val became = showing && !layerReady
    layerReady = showing
    if (!became || firstFrameEmitted) return

    firstFrameEmitted = true
    emitEvent(PlaybackEvent.FirstFrameRendered)
  }

  private fun onReachedEnd() {
    scope.launch {
      if (wrapsAtEnd()) return@launch
      reachedEnd = true
      stopPlaying()
      val duration = plan?.resolved?.duration ?: Duration.ZERO
      setStatus(PlaybackStatus.Ended)
      setPlayWhenReady(false)
      emitEvent(PlaybackEvent.Ended(readPosition(), duration))
    }
  }

  private fun onFailedToPlayToEnd() {
    scope.launch {
      halt()
      val failure =
        PlaybackError.Underlying(
          PlaybackError.Underlying.NO_PLATFORM_CODE,
          "AVFoundation stopped before the composition ended.",
        )
      emitEvent(PlaybackEvent.Failed(failure))
    }
  }

  private fun onStalled() {
    scope.launch { setStalled(true) }
  }

  /**
   * Reacts to an occasion outside filmstrip that took playback away.
   *
   * The transport is stopped first and the change reported second, so a listener that reads the
   * player back on the event sees it already paused.
   */
  private fun onInterrupted() {
    player.pause()
    stopPlaying()
    reportExternalPlayWhenReady(false)
  }

  /**
   * Stops the wrap tick and reports where playback stopped, when it was playing, whoever started it.
   *
   * A wrap already due is taken first, so playback never stops past the loop range without having wrapped. The item
   * can run on for a moment after it is paused, so the reading taken here is followed by the one the periodic observer
   * makes once it has stopped. A seek on its way reports where it lands once it completes, so nothing is reported here
   * while one is in flight.
   */
  private fun stopPlaying() {
    if (!playing && !outsidePlayback) return
    onPlayhead(itemTime())
    playing = false
    outsidePlayback = false
    wrapTick.stop()
    if (seekInFlight) return
    stopReadingDue = true
    onStopReading(itemTime())
  }

  /**
   * Reports where the item stopped, until the next seek or play.
   *
   * A seek's completion reports where it landed, so a reading the observer made on the way to a seek never follows it.
   * Neither does one it made before the item stopped, which is behind the playhead already reported.
   */
  private fun onStopReading(position: Duration) {
    if (!stopReadingDue || position < playhead) return
    playhead = position
    emitPosition(position)
  }

  /**
   * Reports [error] as the reason playback stopped.
   *
   * The platform answers no seek it was holding after this, so none is left in flight.
   */
  private fun fail(error: PlaybackError) {
    seekInFlight = false
    halt()
    setStatus(PlaybackStatus.Error(error))
    emitEvent(PlaybackEvent.Failed(error))
  }

  /**
   * Stops the player and the wrap tick after a failure, reporting nothing.
   *
   * A failed item's clock is not somewhere playback stopped, so the playhead keeps the last reading the tick took.
   */
  private fun halt() {
    player.pause()
    playing = false
    outsidePlayback = false
    stopReadingDue = false
    wrapTick.stop()
  }

  private fun detachItem() {
    itemNotifications.forEach { it.dispose() }
    itemNotifications = emptyList()
    observer.observeItem(null)
    item = null
    lowered = null
    seekInFlight = false
    stopReadingDue = false
  }

  private fun itemTime(): Duration = item?.currentTime()?.toDuration() ?: Duration.ZERO

  private fun landSeek(
    landed: Duration,
    onComplete: (landed: Duration) -> Unit,
  ) {
    seekInFlight = false
    playhead = landed
    onComplete(landed)
  }

  /**
   * Takes one reading of the wrap tick.
   *
   * Playback the engine did not start has no other ticker behind it, so each reading it makes is reported here.
   */
  private fun onTick(position: Duration) {
    onPlayhead(position)
    if (outsidePlayback) emitPosition(playhead)
  }

  /**
   * Checks one reading of the item's clock against the loop range, and takes the wrap once playback reaches it.
   *
   * A reading that does not wrap becomes the playhead. While a seek is in flight the playhead holds, and the seek's
   * completion moves it to where it landed.
   */
  private fun onPlayhead(position: Duration) {
    // A seek waiting on the item is dispatched first, so a wrap never supersedes it.
    onSeekReady()
    if (seekInFlight) return
    if (wrapDue(position)) wrapLoop() else playhead = position
  }

  private fun loopWrap(): LoopWrap? {
    val range = loopRange ?: return null
    val resolved = plan?.resolved ?: return null
    return loopWrapOf(range, resolved.duration, resolved.frameRate)
  }

  /**
   * Whether playback reading [position] has reached the loop range's wrap.
   *
   * A paused player never wraps, so a seek or a frame step past the range stays where it was put. Neither does an item
   * that cannot seek yet.
   */
  private fun wrapDue(position: Duration): Boolean {
    if (!playing || !isSeekReady()) return false
    val wrap = loopWrap() ?: return false
    // The item's clock reads back through a Double of seconds, so a reading taken on the wrap can come back a hair
    // short of it.
    return position + CLOCK_SLACK >= wrap.at
  }

  /**
   * Takes playback back to the slot the loop range starts on and reports the lap.
   *
   * The playhead reports that slot from here on, rather than whatever the item reads, until the seek lands.
   */
  private fun wrapLoop() {
    val range = loopRange ?: return
    val wrap = loopWrap() ?: return
    val landing = outputSlotTime(wrap.firstSlot, plan?.resolved?.frameRate ?: return)
    playhead = landing
    seekTo(landing, SeekAccuracy.Exact)
    emitEvent(PlaybackEvent.RangeLooped(range))
    emitPosition(landing)
  }

  /**
   * Watches for playback crossing the loop range's wrap, for a range the composition runs on past.
   *
   * A range the composition ends inside wraps off the item's end instead, since the item stops there. Called again for
   * each composition, whose duration and frame rate move the wrap.
   */
  private fun armLoopBoundary() {
    loopToken?.let { player.removeTimeObserver(it) }
    loopToken = null
    val wrap = loopWrap()?.takeUnless { it.reachesEnd } ?: return

    loopToken =
      player.addBoundaryTimeObserverForTimes(listOf(NSValue.valueWithCMTime(wrap.at.toCMTime())), callbackQueue) {
        try {
          // Read again on the engine's dispatcher, so a wrap the playhead already took is not taken twice.
          scope.launch { onPlayhead(itemTime()) }
        } catch (
          @Suppress("SwallowedException", "TooGenericExceptionCaught") broken: Exception,
        ) {
          // Never let this reach the Objective-C frame that called it.
        }
      }
  }

  /**
   * Answers the item reaching its end while a loop range has something to loop, which is a wrap rather than an ending.
   *
   * AVFoundation pauses the player at the end, so playing again is what carries playback on from the landing. An end
   * a wrap already answered, still seeking or landed, only needs that.
   *
   * @return whether the end was a wrap, and so is not reported as an ending.
   */
  private fun wrapsAtEnd(): Boolean {
    if (loopWrap() == null) return false
    if (!playing) return true
    onPlayhead(itemTime())
    player.play()
    return true
  }

  private fun settle(result: SetCompositionResult) {
    val load = pending ?: return
    if (load.settled) return
    load.settled = true
    pending = null
    load.callback.onResult(result)
  }

  private fun supersedePending() {
    val load = pending ?: return
    pending = null
    if (load.settled) return
    load.settled = true
    load.job.cancel()
    load.callback.onResult(SetCompositionResult.Superseded)
  }

  /**
   * One outstanding [setComposition], so a later request can supersede it exactly once.
   */
  private class PendingLoad(
    val job: Job,
    val callback: SetCompositionCallback,
  ) {
    var settled: Boolean = false
  }

  private companion object {
    // The rate BasePlayerEngine's ticker reads at, so the playhead it reports is at most one check behind the item.
    val POSITION_INTERVAL = 16.milliseconds

    // Generous on purpose. A relaxed seek exists to keep up with a finger, and a tolerance tight
    // enough to matter would decode from the preceding sync sample anyway.
    val NEAREST_TOLERANCE = 250.milliseconds

    val CLOCK_SLACK = 1.microseconds
  }
}

/**
 * The engine driving [player], or null when filmstrip did not build it.
 *
 * Engines register on construction and drop out on release, so a surface holding only the platform
 * player from [dev.jordond.filmstrip.player.VideoPlayer.nativePlayer] can find the engine behind
 * it.
 */
internal fun engineFor(player: AVPlayer): AvPlayerEngine? = engines[player]

private val engines = mutableMapOf<AVPlayer, AvPlayerEngine>()

/**
 * Whether this item cannot advance at the position it is on.
 */
private fun AVPlayerItem.isStalled(): Boolean = !playbackLikelyToKeepUp || playbackBufferEmpty

/**
 * Whether the player is holding for data rather than for an instruction.
 */
private fun AVPlayer.isWaitingOnData(): Boolean =
  timeControlStatus == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate &&
    reasonForWaitingToPlay == AVPlayerWaitingToMinimizeStallsReason

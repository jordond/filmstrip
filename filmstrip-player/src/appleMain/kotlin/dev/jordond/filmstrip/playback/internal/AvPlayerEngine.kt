package dev.jordond.filmstrip.playback.internal

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.avfoundation.internal.AvComposition
import dev.jordond.filmstrip.avfoundation.internal.toAvComposition
import dev.jordond.filmstrip.avfoundation.internal.toCMTime
import dev.jordond.filmstrip.avfoundation.internal.toDuration
import dev.jordond.filmstrip.edit.CompositionDiff
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.diff
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
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.AVPlayerWaitingToMinimizeStallsReason
import platform.AVFoundation.addBoundaryTimeObserverForTimes
import platform.AVFoundation.addPeriodicTimeObserverForInterval
import platform.AVFoundation.audioMix
import platform.AVFoundation.canStepBackward
import platform.AVFoundation.canStepForward
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
import platform.AVFoundation.stepByCount
import platform.AVFoundation.timeControlStatus
import platform.AVFoundation.valueWithCMTime
import platform.AVFoundation.videoComposition
import platform.CoreMedia.CMTimeGetSeconds
import platform.Foundation.NSValue
import platform.darwin.dispatch_queue_create
import kotlin.time.Duration
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
@OptIn(InternalFilmstripApi::class, ExperimentalForeignApi::class)
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
        // Also fires on a time jump, on play and on pause, which is what reports a frame step while
        // the ticker is stopped.
        try {
          val position = time.toDuration()
          scope.launch { emitPosition(position) }
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
            setStatus(PlaybackStatus.Error(result.error))
            emitEvent(PlaybackEvent.Failed(result.error))
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

  override fun readPosition(): Duration = item?.currentTime()?.toDuration() ?: Duration.ZERO

  override fun isSeekReady(): Boolean = item?.status == AVPlayerItemStatusReadyToPlay

  override fun performSeek(
    position: Duration,
    accuracy: SeekAccuracy,
    onComplete: () -> Unit,
  ) {
    val target = item
    if (target == null) {
      onComplete()
      return
    }

    reachedEnd = false
    val tolerance = if (accuracy == SeekAccuracy.Exact) Duration.ZERO else NEAREST_TOLERANCE
    target.seekToTime(
      time = position.toCMTime(),
      toleranceBefore = tolerance.toCMTime(),
      toleranceAfter = tolerance.toCMTime(),
    ) { _ ->
      // Completed either way. AVFoundation answers a superseded seek immediately with false, and a
      // caller counting completions against requests must not be left waiting on the one it threw
      // away. The chase issues one seek at a time, so nothing here double-resolves.
      try {
        scope.launch { onComplete() }
      } catch (
        @Suppress("SwallowedException", "TooGenericExceptionCaught") broken: Exception,
      ) {
        // Never let this reach the Objective-C frame that called it.
      }
    }
  }

  override fun onPlay() {
    reachedEnd = false
    player.play()
  }

  override fun onPause() {
    player.pause()
  }

  override fun onRelease() {
    engines.remove(player)
    supersedePending()
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
    if (frames == 0) return
    val target = item ?: return
    val rate =
      plan
        ?.resolved
        ?.output
        ?.frameRate
        ?.takeIf { it > 0 } ?: return
    val duration = plan?.resolved?.duration ?: return

    // canStepForward stays true at the end of an item, so the boundary is decided by where the
    // step would land rather than by the flag alone.
    val landing = readPosition() + (1.seconds / rate) * frames
    if (landing < Duration.ZERO || landing > duration) return
    if (frames > 0 && !target.canStepForward) return
    if (frames < 0 && !target.canStepBackward) return

    reachedEnd = false
    // Asynchronous and without a completion, so currentTime() here would still read the old frame.
    // The periodic observer reports the jump once it lands.
    target.stepByCount(frames.toLong())
  }

  override fun setVolume(volume: Float) {
    player.setVolume(volume.coerceIn(0f, 1f))
  }

  override fun setLoopRange(range: TimeRange?) {
    loopRange = range
    loopToken?.let { player.removeTimeObserver(it) }
    loopToken = null
    val wrapAt = range?.endExclusive ?: return

    loopToken =
      player.addBoundaryTimeObserverForTimes(listOf(NSValue.valueWithCMTime(wrapAt.toCMTime())), callbackQueue) {
        try {
          scope.launch {
            if (loopRange == range) {
              seekTo(range.start, SeekAccuracy.Exact)
              emitEvent(PlaybackEvent.RangeLooped(range))
            }
          }
        } catch (
          @Suppress("SwallowedException", "TooGenericExceptionCaught") broken: Exception,
        ) {
          // Never let this reach the Objective-C frame that called it.
        }
      }
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
    setDuration(next.resolved.duration)
    emitPreviewInfo(next.info)
    request.startAt?.let { start ->
      item?.seekToTime(start.toCMTime(), Duration.ZERO.toCMTime(), Duration.ZERO.toCMTime())
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
        val failure =
          PlaybackError.Underlying(
            platformCode = error?.code?.toInt() ?: PlaybackError.Underlying.NO_PLATFORM_CODE,
            message = error?.localizedDescription ?: "AVFoundation could not play this composition.",
          )
        setStatus(PlaybackStatus.Error(failure))
        emitEvent(PlaybackEvent.Failed(failure))
      }
      AVPlayerItemStatusReadyToPlay -> {
        setStatus(if (reachedEnd) PlaybackStatus.Ended else PlaybackStatus.Ready)
      }
      else -> {
        setStatus(PlaybackStatus.Preparing)
      }
    }

    reportFirstFrame(target)
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
      reachedEnd = true
      val duration = plan?.resolved?.duration ?: Duration.ZERO
      setStatus(PlaybackStatus.Ended)
      setPlayWhenReady(false)
      emitEvent(PlaybackEvent.Ended(readPosition(), duration))
    }
  }

  private fun onFailedToPlayToEnd() {
    scope.launch {
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
    reportExternalPlayWhenReady(false)
  }

  private fun detachItem() {
    itemNotifications.forEach { it.dispose() }
    itemNotifications = emptyList()
    observer.observeItem(null)
    item = null
    lowered = null
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
    // Roughly display rate, which is what a jump report is worth. The ticker in BasePlayerEngine
    // carries the steady playhead.
    val POSITION_INTERVAL = 16.milliseconds

    // Generous on purpose. A relaxed seek exists to keep up with a finger, and a tolerance tight
    // enough to matter would decode from the preceding sync sample anyway.
    val NEAREST_TOLERANCE = 250.milliseconds
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

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
import kotlinx.coroutines.launch
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.AVPlayerRateDidChangeNotification
import platform.AVFoundation.AVPlayerRateDidChangeReasonKey
import platform.AVFoundation.audioMix
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.rate
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.setForwardPlaybackEndTime
import platform.AVFoundation.setVolume
import platform.AVFoundation.videoComposition
import platform.CoreMedia.CMTimeGetSeconds
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
 * Seeking lives in [AvSeekCoordinator], the loop range in [AvLoopRangeController], the playhead and the transport in
 * [AvTransport], and what AVFoundation reports about the item in [AvItemObservers]. This class loads compositions and
 * wires those together.
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
  private val callbackQueue = dispatch_queue_create("dev.jordond.filmstrip.player", null)
  private val observers: AvItemObservers =
    AvItemObservers(
      scope = scope,
      player = player,
      onChanged = ::onPlatformChanged,
      onReachedEnd = ::onReachedEnd,
      onFailedToPlayToEnd = ::onFailedToPlayToEnd,
      onStalled = { setStalled(true) },
      onFirstFrame = { emitEvent(PlaybackEvent.FirstFrameRendered) },
    )
  private val interruptions = AvInterruptions(player) { scope.launch { onInterrupted() } }
  private val rateChanges =
    AvNotificationObserver(AVPlayerRateDidChangeNotification, player) { notification ->
      val stopped = player.rate == 0f
      val reason = notification.userInfo?.get(AVPlayerRateDidChangeReasonKey) as? String
      scope.launch { transport.onRateChanged(stopped && reason == null) }
    }

  private var loaded: EditComposition? = null
  private var plan: AvPreviewPlan? = null
  private var lowered: AvComposition? = null
  private var item: AVPlayerItem? = null
  private val pending = PendingLoad()
  private var policy: PreviewQualityPolicy = config.qualityPolicy
  private var reachedEnd = false
  private val wrapGuard = WrapGuard()

  private val seeks: AvSeekCoordinator =
    AvSeekCoordinator(
      scope = scope,
      item = { item },
      resolved = { plan?.resolved },
      guard = wrapGuard,
      loopWrap = { loop.wrap() },
      playhead = { transport.playhead },
      onIssued = {
        reachedEnd = false
        transport.cancelStopReading()
      },
      onLanded = { transport.onSeekLanded(it) },
    )

  private val loop: AvLoopRangeController =
    AvLoopRangeController(
      scope = scope,
      player = player,
      callbackQueue = callbackQueue,
      resolved = { plan?.resolved },
      guard = wrapGuard,
      onBoundary = { transport.onPlayhead(itemTime()) },
    )

  private val transport: AvTransport =
    AvTransport(
      scope = scope,
      player = player,
      callbackQueue = callbackQueue,
      interval = POSITION_INTERVAL,
      resolved = { plan?.resolved },
      seeks = seeks,
      loop = loop,
      guard = wrapGuard,
      itemTime = ::itemTime,
      itemEnd = { platformDuration },
      isSeekReady = ::isSeekReady,
      onSeekReady = { onSeekReady() },
      seekToWrap = { seekToWrap(it) },
      emitEvent = { emitEvent(it) },
      emitPosition = { emitPosition(it) },
    )

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
    observers.observePlayer()
    transport.observeStops()
  }

  override fun setComposition(
    request: SetCompositionRequest,
    callback: SetCompositionCallback,
  ): Cancellable {
    pending.supersede()

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
            pending.settle(SetCompositionResult.Failure(result.error))
          }
          is AvPlanResult.Ready -> {
            apply(change, result.plan, request)
            pending.settle(SetCompositionResult.Success(result.plan.resolved.duration))
          }
        }
      }

    return pending.track(job, callback)
  }

  override fun readPosition(): Duration = transport.playhead

  override fun isSeekReady(): Boolean = item?.status == AVPlayerItemStatusReadyToPlay

  override fun performSeek(
    position: Duration,
    accuracy: SeekAccuracy,
    onComplete: (landed: Duration) -> Unit,
  ) {
    seeks.seek(position, accuracy, isWrapSeek, onComplete)
  }

  override fun onPlay() {
    reachedEnd = false
    transport.play()
  }

  override fun onPause() {
    player.pause()
    transport.stop()
  }

  override fun onRelease() {
    engines.remove(player)
    pending.supersede()
    transport.release()
    loop.dispose()
    interruptions.dispose()
    rateChanges.dispose()
    detachItem()
    observers.dispose()
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
    loop.set(range)
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
    scope.launch { if (observers.surfaceLayer == layer) observeSurfaceLayer(null) }
  }

  private fun observeSurfaceLayer(layer: AVPlayerLayer?) {
    observers.observeLayer(layer)
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
    wrapGuard.reset()
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
    loop.arm()
    setDuration(next.resolved.duration)
    emitPreviewInfo(next.info)
    val start = request.startAt?.let(next.resolved::seekLandingOf)
    val target = item
    if (start != null && target != null) {
      seeks.start(target, next.resolved, start, SeekAccuracy.Exact, wrap = false) { }
    }
    // The load's seek reports nothing when it lands and nothing reports a fresh item's start, so the jump is reported
    // here.
    if (start != null || !swappable) transport.jumpTo(start ?: Duration.ZERO)
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
    observers.observeItem(fresh)
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
        fail(target.playbackError())
      }
      AVPlayerItemStatusReadyToPlay -> {
        setStatus(if (reachedEnd) PlaybackStatus.Ended else PlaybackStatus.Ready)
      }
      else -> {
        setStatus(PlaybackStatus.Preparing)
      }
    }

    transport.follow(target)
    observers.reportFirstFrame(target)
  }

  private fun onReachedEnd() {
    if (transport.wrapsAtEnd()) return
    reachedEnd = true
    transport.stop()
    val duration = plan?.resolved?.duration ?: Duration.ZERO
    setStatus(PlaybackStatus.Ended)
    setPlayWhenReady(false)
    emitEvent(PlaybackEvent.Ended(readPosition(), duration))
  }

  private fun onFailedToPlayToEnd() {
    transport.halt()
    val failure =
      PlaybackError.Underlying(
        PlaybackError.Underlying.NO_PLATFORM_CODE,
        "AVFoundation stopped before the composition ended.",
      )
    emitEvent(PlaybackEvent.Failed(failure))
  }

  /**
   * Reacts to an occasion outside filmstrip that took playback away.
   *
   * The transport is stopped first and the change reported second, so a listener that reads the
   * player back on the event sees it already paused.
   */
  private fun onInterrupted() {
    player.pause()
    transport.stop()
    reportExternalPlayWhenReady(false)
  }

  /**
   * Reports [error] as the reason playback stopped.
   *
   * The platform answers no seek it was holding after this, so none is left in flight.
   */
  private fun fail(error: PlaybackError) {
    seeks.abandon()
    wrapGuard.reset()
    transport.halt()
    setStatus(PlaybackStatus.Error(error))
    emitEvent(PlaybackEvent.Failed(error))
  }

  private fun detachItem() {
    observers.detachItem()
    item = null
    lowered = null
    seeks.abandon()
    transport.cancelStopReading()
  }

  private fun itemTime(): Duration = item?.currentTime()?.toDuration() ?: Duration.ZERO

  private companion object {
    // The rate BasePlayerEngine's ticker reads at, so the playhead it reports is at most one check behind the item.
    val POSITION_INTERVAL = 16.milliseconds
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

package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.avfoundation.internal.time.toCMTime
import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerTimeControlStatusPaused
import platform.AVFoundation.addPeriodicTimeObserverForInterval
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.removeTimeObserver
import platform.AVFoundation.timeControlStatus
import platform.darwin.dispatch_queue_t
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/**
 * Tracks whether the player is playing, whoever started it, and moves the playhead with it.
 *
 * A wrap tick reads the item's clock while playback runs. Each reading becomes the playhead, or takes the loop range's
 * wrap once playback reaches it. Where playback stops is reported once the item has come to rest, read off a periodic
 * observer AVFoundation calls on the callback queue, which hops back onto the scope before it touches anything.
 *
 * Engine's dispatcher only, apart from that observer.
 *
 * @param scope The engine's scope, on the dispatcher everything is confined to.
 * @param player The player driven and followed.
 * @param callbackQueue The queue AVFoundation calls the periodic observer on.
 * @param interval How long the wrap tick and the periodic observer wait between readings.
 * @param resolved The composition loaded now, or null before one is.
 * @param seeks The seeks in flight, which hold the playhead until they land.
 * @param loop The loop range standing now.
 * @param guard Decides whether a reading has reached the wrap.
 * @param itemTime Reads the item's clock, or zero with no item.
 * @param itemEnd The duration the item reports, or null while it has settled none.
 * @param isSeekReady Whether the item can take a seek yet.
 * @param onSeekReady Dispatches a seek waiting on the item.
 * @param seekToWrap Hands a wrap's landing to the chase, marked as a wrap.
 * @param emitEvent Reports a playback event.
 * @param emitPosition Reports a playhead position.
 */
@OptIn(ExperimentalForeignApi::class)
internal class AvTransport(
  private val scope: CoroutineScope,
  private val player: AVPlayer,
  private val callbackQueue: dispatch_queue_t,
  private val interval: Duration,
  private val resolved: () -> ResolvedComposition?,
  private val seeks: AvSeekCoordinator,
  private val loop: AvLoopRangeController,
  private val guard: WrapGuard,
  private val itemTime: () -> Duration,
  private val itemEnd: () -> Duration?,
  private val isSeekReady: () -> Boolean,
  private val onSeekReady: () -> Unit,
  private val seekToWrap: (Duration) -> Unit,
  private val emitEvent: (PlaybackEvent) -> Unit,
  private val emitPosition: (Duration) -> Unit,
) {
  private var playing = false
  private var outsidePlayback = false

  // Whether the player's latest stop was AVFoundation's own. Its stop at the item's end posts a rate change with no
  // reason, and a pause anyone asked for posts one saying setRate was called.
  private var platformStopped = false
  private var stopReadingDue = false
  private val wrapTick = PositionTicker(scope, interval, read = itemTime, emit = ::onTick)
  private var stopToken: Any? = null

  /**
   * Whether playback the engine started is running, which [stop] and [halt] both end.
   */
  val isPlaying: Boolean get() = playing

  /**
   * Where playback is.
   *
   * While playing this is the reading the wrap tick last checked, so the ticker never reports one past the loop's wrap
   * before the wrap is taken. Otherwise it is where the last seek, pause or load left the item.
   */
  var playhead: Duration = Duration.ZERO
    private set

  /**
   * Starts the periodic observer that reads where the item came to rest once playback stops.
   *
   * The item can run on for a moment after a pause, past the reading [stop] took. The observer also fires once it has
   * settled, and that reading moves the playhead onto where playback really stopped.
   */
  fun observeStops() {
    stopToken =
      player.addPeriodicTimeObserverForInterval(interval.toCMTime(), callbackQueue) { time ->
        // Also fires when playback stops, with where the item came to rest. That is the only reading kept from here.
        insideObjcFrame {
          val position = time.toDuration()
          scope.launch { onStopReading(position) }
        }
      }
  }

  /**
   * Plays the player and starts the wrap tick for playback the engine started.
   */
  fun play() {
    playing = true
    outsidePlayback = false
    stopReadingDue = false
    player.play()
    wrapTick.start()
  }

  /**
   * Records whether the player's latest stop was AVFoundation's own, read off a rate change.
   */
  fun onRateChanged(platformStopped: Boolean) {
    this.platformStopped = platformStopped
  }

  /**
   * Moves the playhead to [position] and reports it, dropping any stop reading still due.
   */
  fun jumpTo(position: Duration) {
    stopReadingDue = false
    playhead = position
    emitPosition(position)
  }

  /**
   * Moves the playhead to where a seek landed, which reports it itself.
   */
  fun onSeekLanded(landed: Duration) {
    playhead = landed
  }

  /**
   * Drops the stop reading still due, for a seek or a detached item that makes it stale.
   */
  fun cancelStopReading() {
    stopReadingDue = false
  }

  /**
   * Starts or stops the wrap tick for playback the engine did not start, following the player's own transport.
   *
   * The app playing the native player itself, the system's transport controls and AVFoundation resuming on its own all
   * move the player without [play]. That playback moves the playhead and wraps the loop range the way the engine's own
   * does, and where it stops is reported the way a pause is. AVFoundation pausing the player at the item's end inside
   * a loop range is a wrap rather than a stop, whichever of the pause and the end notification arrives first. A pause
   * anyone asked for is a stop, even at the item's end.
   */
  fun follow(target: AVPlayerItem) {
    if (playing) return
    val advancing =
      target.status != AVPlayerItemStatusFailed && player.timeControlStatus != AVPlayerTimeControlStatusPaused
    if (advancing == outsidePlayback) return
    if (!advancing) {
      if (!platformStopped || !atItemEnd() || !wrapsAtEnd()) stop()
      return
    }
    outsidePlayback = true
    stopReadingDue = false
    wrapTick.start()
  }

  /**
   * Stops the wrap tick and reports where playback stopped, when it was playing, whoever started it.
   *
   * A wrap already due is taken first, so playback never stops past the loop range without having wrapped. The item
   * can run on for a moment after it is paused, so the reading taken here is followed by the one the periodic observer
   * makes once it has stopped. A seek on its way reports where it lands once it completes, so nothing is reported here
   * while one is in flight.
   */
  fun stop() {
    if (!playing && !outsidePlayback) return
    onPlayhead(itemTime())
    playing = false
    outsidePlayback = false
    wrapTick.stop()
    if (seeks.inFlight) return
    stopReadingDue = true
    onStopReading(itemTime())
  }

  /**
   * Stops the player and the wrap tick after a failure, reporting nothing.
   *
   * A failed item's clock is not somewhere playback stopped, so the playhead keeps the last reading the tick took.
   */
  fun halt() {
    player.pause()
    playing = false
    outsidePlayback = false
    stopReadingDue = false
    wrapTick.stop()
  }

  /**
   * Stops the wrap tick and the periodic observer for good, leaving the player to the engine.
   */
  fun release() {
    playing = false
    outsidePlayback = false
    wrapTick.stop()
    stopToken?.let { player.removeTimeObserver(it) }
    stopToken = null
  }

  /**
   * Reports where the item stopped, until the next seek or play.
   *
   * A seek's completion reports where it landed, so a reading the observer made on the way to a seek never follows it.
   * Neither does one it made before the item stopped, which is behind the playhead already reported.
   */
  fun onStopReading(position: Duration) {
    if (!stopReadingDue || position < playhead) return
    playhead = position
    emitPosition(position)
  }

  /**
   * Checks one reading of the item's clock against the loop range, and takes the wrap once playback reaches it.
   *
   * A reading that does not wrap becomes the playhead. While a seek is in flight the playhead holds, and the seek's
   * completion moves it to where it landed.
   */
  fun onPlayhead(position: Duration) {
    // A seek waiting on the item is dispatched first, so a wrap never supersedes it.
    onSeekReady()
    if (seeks.inFlight) return
    if (wrapDue(position)) wrapLoop() else playhead = position
  }

  /**
   * Answers the item reaching its end while a loop range has something to loop, which is a wrap rather than an ending.
   *
   * AVFoundation pauses the player at the end, so playing again is what carries playback on from the landing. An end
   * a wrap already answered, still seeking or landed, only needs that. This is the one place a wrap plays the player,
   * since the end notification follows AVFoundation's stop at every end, including one the wrap tick reached first.
   *
   * Playback the engine started wraps at every end. Playback it did not start wraps only when AVFoundation stopped the
   * player itself, so a pause made on the native player is never played on. A pause made on the native player after
   * AVFoundation has already stopped it at the end cannot be told apart from that stop, so it still wraps. That limit
   * is AVFoundation's. With the transport stopped the end is left where it is.
   *
   * @return whether the end was a wrap, and so is not reported as an ending.
   */
  fun wrapsAtEnd(): Boolean {
    if (loop.wrap() == null) return false
    if (!playing && !(outsidePlayback && platformStopped)) return true
    onPlayhead(itemTime())
    player.play()
    return true
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

  private fun atItemEnd(): Boolean {
    val end = itemEnd() ?: return false
    return itemTime() + CLOCK_SLACK >= end
  }

  /**
   * Whether playback reading [position] has reached the loop range's wrap.
   *
   * Once the reading is there, [guard] decides whether it may wrap, reading the transport as running whoever started
   * it. A paused player never wraps, so a seek or a frame step past the range stays where it was put. Neither does an
   * item that cannot seek yet.
   */
  private fun wrapDue(position: Duration): Boolean {
    if (!isSeekReady()) return false
    val wrap = loop.wrap() ?: return false
    // AVFoundation's limit. The item's clock reads back through a Double of seconds, so a reading taken on the wrap
    // can come back a hair short of it.
    return position + CLOCK_SLACK >= wrap.at && guard.mayWrap(running = playing || outsidePlayback)
  }

  /**
   * Takes playback back to the slot the loop range starts on and reports the lap.
   *
   * The playhead reports that slot from here on, rather than whatever the item reads, until the seek lands. The seek is
   * marked as a wrap for as long as the chase holds it. The player is left as it is. A wrap off the item's end is
   * played on by [wrapsAtEnd].
   */
  private fun wrapLoop() {
    val range = loop.range ?: return
    val wrap = loop.wrap() ?: return
    val landing = outputSlotTime(wrap.firstSlot, resolved()?.frameRate ?: return)
    playhead = landing
    seekToWrap(landing)
    emitEvent(PlaybackEvent.RangeLooped(range))
    emitPosition(landing)
  }

  private companion object {
    val CLOCK_SLACK = 1.microseconds
  }
}

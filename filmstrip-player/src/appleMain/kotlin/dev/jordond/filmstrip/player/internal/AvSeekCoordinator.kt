package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.avfoundation.internal.time.toCMTime
import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.currentTime
import platform.AVFoundation.seekToTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Hands seeks to the item and answers the chase once AVFoundation says where each one landed.
 *
 * Every seek issued here becomes the latest one, so a completion that arrives after a newer seek went out is known to
 * be superseded. Only the latest seek reaches [guard], and a landing it redirects is sent on from here.
 *
 * Every seek handed to the item is counted until its caller hears where it landed, so [awaitAnswered] can wait out the
 * seeks still on their way. A redirect counts as the seek it carries on, and a seek a newer one replaced counts until
 * AVFoundation answers it.
 *
 * Everything here runs on the engine's dispatcher, apart from the completion AVFoundation calls, which hops back onto
 * [scope] before it touches anything.
 *
 * @param scope The engine's scope, on the dispatcher everything is confined to.
 * @param item The item loaded now, or null before one is.
 * @param resolved The composition loaded now, or null before one is.
 * @param guard Hears every seek issued and landed, and decides whether a landing is sent on to a moved loop range.
 * @param loopWrap Where the loop range standing now wraps, or null when there is none.
 * @param playhead The playhead reported now, which a superseded seek answers with.
 * @param onIssued Called as each fresh seek is put in flight, before it is handed over.
 * @param onLanded Called with where the latest seek finally landed, before its caller hears it.
 */
@OptIn(ExperimentalForeignApi::class)
internal class AvSeekCoordinator(
  private val scope: CoroutineScope,
  private val item: () -> AVPlayerItem?,
  private val resolved: () -> ResolvedComposition?,
  private val guard: WrapGuard,
  private val loopWrap: () -> LoopWrap?,
  private val playhead: () -> Duration,
  private val onIssued: () -> Unit,
  private val onLanded: (Duration) -> Unit,
) {
  private var latestSeek = 0
  private var ledger = 0
  private val unanswered = MutableStateFlow(0)

  /**
   * Whether a seek is on its way and has not landed yet.
   */
  var inFlight: Boolean = false
    private set

  /**
   * How many seeks handed to the item have not been answered yet.
   */
  val unansweredSeeks: Int get() = unanswered.value

  /**
   * How many seeks have been handed to the item since this was built, each counted once however often it is redirected.
   */
  var countedSeeks: Int = 0
    private set

  /**
   * Seeks the item loaded now to [position], or lands straight away where nothing is loaded yet.
   *
   * @param wrap Whether the chase marked this seek as a loop wrap.
   */
  fun seek(
    position: Duration,
    accuracy: SeekAccuracy,
    wrap: Boolean,
    onComplete: (landed: Duration) -> Unit,
  ) {
    val target = item()
    val loaded = resolved()
    if (target == null || loaded == null) {
      finish(position, onComplete)
      return
    }
    start(target, loaded, position, accuracy, wrap, onComplete)
  }

  /**
   * Puts a fresh seek in flight and issues it, telling [guard] so no wrap fires until it lands.
   *
   * @param wrap Whether the seek is a loop wrap.
   */
  fun start(
    target: AVPlayerItem,
    resolved: ResolvedComposition,
    position: Duration,
    accuracy: SeekAccuracy,
    wrap: Boolean,
    onComplete: (landed: Duration) -> Unit,
  ) {
    onIssued()
    inFlight = true
    guard.seekIssued(wrap)
    issue(target, resolved, position, accuracy, counted(onComplete))
  }

  /**
   * Forgets the seeks in flight once no item is loaded or the one loaded has failed.
   *
   * An item still loaded is left to answer its seeks, which stay in flight and counted until it does. A forgotten seek
   * answered later is treated as superseded, so it moves nothing and no longer counts.
   */
  fun abandon() {
    val target = item()
    if (target != null && target.status != AVPlayerItemStatusFailed) return
    inFlight = false
    latestSeek++
    ledger++
    unanswered.value = 0
  }

  /**
   * Suspends until every seek counted has been answered, or forgotten by [abandon].
   *
   * Never returns while AVFoundation holds a seek it does not answer, so a caller bounds the wait.
   */
  suspend fun awaitAnswered() {
    unanswered.first { it == 0 }
  }

  /**
   * Counts one seek until [onComplete] is called for it, unless [abandon] forgot it first.
   */
  private fun counted(onComplete: (landed: Duration) -> Unit): (landed: Duration) -> Unit {
    val entry = ledger
    countedSeeks++
    unanswered.value += 1
    return { landed ->
      if (entry == ledger) unanswered.value -= 1
      onComplete(landed)
    }
  }

  /**
   * Seeks [target] to [position] and lands the seek through [land] once AVFoundation answers.
   */
  private fun issue(
    target: AVPlayerItem,
    resolved: ResolvedComposition,
    position: Duration,
    accuracy: SeekAccuracy,
    onComplete: (landed: Duration) -> Unit,
  ) {
    val seek = ++latestSeek
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
      val reported =
        insideObjcFrame {
          // An exact landing is handed back as the slot's own time, since currentTime() is a fraction of a tick off
          // it at a rate the timescale does not divide.
          val landed =
            when {
              !finished -> position
              exact -> landing
              else -> resolved.seekLandingOf(target.currentTime().toDuration())
            }
          scope.launch { land(seek, finished, landed, onComplete) }
        }
      // The seek still has to complete, or a landing that throws freezes the playhead and leaves the chase waiting
      // for good.
      if (!reported) scope.launch { land(seek, false, position, onComplete) }
    }
  }

  /**
   * Lands seek number [seek], or first sends it on to the range standing now when a wrap has to be redirected.
   *
   * Only the latest seek reaches [guard]. A redirect seeks exactly onto the slot the guard names and lands through
   * here again, so [onComplete] is called once, with where playback finally is. A seek a newer one replaced leaves the
   * playhead and the seek in flight to the newer one, and answers [onComplete] with the playhead.
   */
  private fun land(
    seek: Int,
    finished: Boolean,
    landed: Duration,
    onComplete: (landed: Duration) -> Unit,
  ) {
    if (seek != latestSeek) {
      onComplete(playhead())
      return
    }
    // A latest seek that did not finish was superseded by one the engine never issued, so it lands nowhere new.
    val redirect = guard.seekLanded(if (finished) loopWrap() else null)
    if (redirect != null) {
      val target = item()
      val loaded = resolved()
      if (target != null && loaded != null) {
        issue(target, loaded, outputSlotTime(redirect, loaded.frameRate), SeekAccuracy.Exact, onComplete)
        return
      }
      guard.reset()
    }
    finish(landed, onComplete)
  }

  private fun finish(
    landed: Duration,
    onComplete: (landed: Duration) -> Unit,
  ) {
    inFlight = false
    onLanded(landed)
    onComplete(landed)
  }

  private companion object {
    // A relaxed seek exists to keep up with a finger, and a tolerance tight enough to matter would
    // decode from the preceding sync sample anyway.
    val NEAREST_TOLERANCE = 250.milliseconds
  }
}

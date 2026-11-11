package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.webcodecs.internal.preview.BrowserAudioPreview
import dev.jordond.filmstrip.webcodecs.internal.preview.BrowserPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Starts, stops and watches the browser engine's playback, and fences the work it launches.
 *
 * A start runs the audio graph, the clock and both [BrowserPlaybackLoops]. The transport tick notices the end of the
 * composition, a loop point and a browser that will not start the audio graph. Every start and seek landing goes
 * through [fence], so [awaitQuiet] can wait on all of them.
 *
 * Not thread safe. Everything is confined to [scope], like the engine that owns it.
 *
 * @param scope The engine's scope.
 * @param guard The engine's wrap guard, shared with its seeks.
 * @param startAudio Starts the audio graph, when there is one, from a position.
 * @param preview The preview standing now, if any.
 * @param audio The audio graph standing now, if any.
 * @param clock The clock counting now.
 * @param resolved The composition loaded now, if any.
 * @param position Where the playhead reads now.
 * @param loopRange The loop range set now, if any.
 * @param playbackRange The playback range set now, if any.
 * @param wrapTo Seeks to the first slot of the loop range as a wrap.
 * @param setStatus Publishes a status.
 * @param setPlayWhenReady Publishes whether playback is asked to run.
 * @param reportExternalPlayWhenReady Publishes a transport change something outside filmstrip made.
 * @param emitEvent Sends an event to every listener.
 * @param fail Fails playback with an error.
 */
internal class BrowserTransport(
  private val scope: CoroutineScope,
  private val guard: WrapGuard,
  private val startAudio: suspend (graph: BrowserAudioPreview?, from: Duration) -> Unit,
  private val preview: () -> BrowserPreview?,
  private val audio: () -> BrowserAudioPreview?,
  private val clock: () -> PreviewClock,
  private val resolved: () -> ResolvedComposition?,
  private val position: () -> Duration,
  private val loopRange: () -> TimeRange?,
  private val playbackRange: () -> TimeRange?,
  private val wrapTo: (Duration) -> Unit,
  private val setStatus: (PlaybackStatus) -> Unit,
  private val setPlayWhenReady: (Boolean) -> Unit,
  private val reportExternalPlayWhenReady: (Boolean) -> Unit,
  private val emitEvent: (PlaybackEvent) -> Unit,
  private val fail: (PlaybackError) -> Unit,
) {
  private val fenced = mutableListOf<Job>()
  private var starting: Job? = null
  private var reachedEnd = false
  private var startedAt: Duration? = null

  /**
   * Whether playback is asked to run, which a pause, an ending or the platform takes back.
   */
  var wantsPlayback = false
    private set

  /**
   * Whether a start has reached the clock and nothing has stopped it since.
   */
  val started: Boolean get() = startedAt != null

  /**
   * Whether a start is still on its way to the clock.
   */
  val startPending: Boolean get() = starting?.isActive == true

  private val loops =
    BrowserPlaybackLoops(
      scope = scope,
      isPlaying = { wantsPlayback },
      onTick = ::onTick,
      onFrame = {
        val target = preview()
        target?.fillAhead(position())
        target != null
      },
    )

  /**
   * Asks for playback, and starts it from the playhead once there is a preview to start.
   *
   * With no preview yet the load that builds one starts it instead.
   */
  fun play() {
    reachedEnd = false
    wantsPlayback = true
    if (preview() != null) launchStart { position() }
  }

  /**
   * Stops asking for playback and stops the transport.
   */
  fun pause() {
    wantsPlayback = false
    stop()
  }

  /**
   * Forgets that the end was reached, so the next one is reported again.
   */
  fun clearEnd() {
    reachedEnd = false
  }

  /**
   * Stops a preview the platform took out of playing, and reports playWhenReady as false the way any other external
   * pause would be.
   */
  fun stopForPlatform() {
    if (!wantsPlayback) return
    wantsPlayback = false
    stop()
    reportExternalPlayWhenReady(false)
  }

  /**
   * Stops the transport the browser refused to keep running, when playback is still asked for.
   */
  fun onAudioStateChanged() {
    if (wantsPlayback && audio()?.isRunning == false) stopForPlatform()
  }

  /**
   * Keeps [job] until it completes, so [awaitQuiet] waits on it.
   */
  fun fence(job: Job) {
    fenced.removeAll { it.isCompleted }
    fenced += job
  }

  /**
   * Suspends until every seek landing and transport start this engine launched has finished.
   *
   * A relaxed landing waits on the preview's lock behind any readbacks queued first, then writes the playhead and
   * reports its completion, or finds itself stale and writes nothing. A transport start either starts the clock and
   * both loops, or is cancelled by whatever superseded it and does nothing more. Once this returns, all of them have
   * done one or the other. Work launched while this waits, the way a landing chains the seek queued behind it, is
   * waited on too.
   *
   * The transport and pump loops a start leaves running are not waited on, since they run for as long as playback
   * does. The engine is only quiet once this returns after a pause.
   */
  suspend fun awaitQuiet() {
    while (true) {
      fenced.removeAll { it.isCompleted }
      val next = fenced.firstOrNull() ?: return
      next.join()
    }
  }

  /**
   * Launches a transport start from wherever [from] reads when it runs, cancelling the start still in flight.
   *
   * Only the newest start is ever live. A pause, a stop, a seek landing or a rebuild cancels it too, so a superseded
   * start never schedules audio, moves the graph's anchor or starts a clock once something has replaced it.
   */
  fun launchStart(from: () -> Duration) {
    starting?.cancel()
    val job = scope.launch { start(from()) }
    starting = job
    fence(job)
  }

  /**
   * Stops the clock, the audio graph and both loops, and cancels any start in flight.
   */
  fun stop() {
    cancelStart()
    clock().stop()
    audio()?.stop()
    startedAt = null
    loops.stop()
    // A frame held across a pause is held for as long as the user leaves it there, and re-seating
    // the decoder on resume costs one seek.
    preview()?.flush()
  }

  private fun cancelStart() {
    starting?.cancel()
    starting = null
  }

  private suspend fun start(from: Duration) {
    loops.stop()
    if (!sounded { startAudio(audio(), from) }) return
    clock().start(from)
    startedAt = wallClock()
    loops.start()
  }

  /**
   * Everything transport has to notice while it runs, on a clock nothing in the page can throttle.
   */
  private suspend fun onTick() {
    if (!wantsPlayback) return
    val composition = resolved() ?: return
    val duration = composition.duration
    val at = position()

    if (refusedToStart()) {
      stopForPlatform()
      return
    }

    if (!sounded { audio()?.pump(at) }) return

    // A reading past the wrap point is never an ending, even while a seek in flight holds the wrap back.
    val range = loopRange()
    val rate = composition.frameRate
    val wrap = range?.let { loopWrapOf(it, duration, rate) }
    if (range != null && wrap != null && at >= wrap.at) {
      if (guard.mayWrap(running = wantsPlayback)) {
        wrapTo(outputSlotTime(wrap.firstSlot, rate))
        emitEvent(PlaybackEvent.RangeLooped(range))
      }
      return
    }

    val end = playbackRange()?.endExclusive ?: duration
    if (at >= end && !reachedEnd) {
      reachedEnd = true
      wantsPlayback = false
      stop()
      setStatus(PlaybackStatus.Ended)
      setPlayWhenReady(false)
      emitEvent(PlaybackEvent.Ended(at, duration))
    }
  }

  /**
   * Runs [work] on the audio graph and answers whether it finished.
   *
   * An audio effect's kernel runs in there and may throw. Playback stops and fails with what it threw, since an
   * exception that left the tick would end it with nothing reported.
   */
  private suspend fun sounded(work: suspend () -> Unit): Boolean =
    try {
      work()
      true
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (
      @Suppress("TooGenericExceptionCaught") broken: Throwable,
    ) {
      wantsPlayback = false
      stop()
      setPlayWhenReady(false)
      fail(PlaybackError.Underlying(PlaybackError.Underlying.NO_PLATFORM_CODE, broken.message ?: AUDIO_BROKE))
      false
    }

  /**
   * Whether the audio graph has been asked to run and has not started within the grace period.
   *
   * The Web Audio spec parks `resume`'s promise rather than rejecting it when a page is not allowed
   * to start, so there is nothing to await and no rejection to catch. The context's own state is
   * the only signal, read after giving it long enough to have changed.
   */
  private fun refusedToStart(): Boolean {
    val graph = audio() ?: return false
    if (graph.isRunning) return false
    val since = startedAt ?: return false
    return wallClock() - since >= AUTOPLAY_GRACE
  }

  private fun wallClock(): Duration = performance.now().milliseconds
}

/**
 * How long the audio graph gets to start before the refusal is reported.
 *
 * Long enough that a context resuming across a task boundary is never called a refusal, short enough that a play button
 * does not sit stuck for a noticeable moment.
 */
internal val AUTOPLAY_GRACE: Duration = 250.milliseconds

private const val AUDIO_BROKE = "The browser could not play this composition's audio."

package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.player.ShuttleEdge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Drives a shuttle by asking for one frame at a time.
 *
 * Each time the frame it asked for is on screen it reads [shuttlePlayheadAt] for the moment and asks for the frame
 * there, so a slow platform shows fewer frames and the playhead never falls behind the rate. One request is in flight
 * at a time. While the playhead sits on the frame already showing it waits [pollInterval] and reads again, and once
 * it holds an edge it asks for nothing until it is re-based.
 *
 * Not thread safe. Confine it to the engine's own dispatcher.
 *
 * @param scope Where the wait between reads runs.
 * @param pollInterval How long to wait before reading a playhead that has not left the frame showing.
 * @param timeSource The clock the playhead is measured on.
 * @param bounds The times of the first and last frames the shuttle may show, or null when nothing is loaded.
 * @param landingOf The slot a frame asked for at a position lands on.
 * @param canPresent Whether the platform can take a request right now.
 * @param present Shows the frame for a position and answers with the slot of the frame that reached the screen.
 * @param onFrame Receives the slot of each frame shown while the shuttle runs.
 * @param onEdge Receives an edge once each time the shuttle arrives on it, with the slot it is holding.
 * @param onLateFrame Receives the slot of a frame that was asked for before the shuttle ended and shown after.
 */
internal class ShuttlePump(
  private val scope: CoroutineScope,
  private val pollInterval: Duration,
  private val timeSource: TimeSource,
  private val bounds: () -> ClosedRange<Duration>?,
  private val landingOf: (Duration) -> Duration,
  private val canPresent: () -> Boolean,
  private val present: (position: Duration, onPresented: (landed: Duration) -> Unit) -> Unit,
  private val onFrame: (landed: Duration) -> Unit,
  private val onEdge: (edge: ShuttleEdge, position: Duration) -> Unit,
  private val onLateFrame: (landed: Duration) -> Unit,
) {
  private class Run(
    val rate: Float,
    val origin: Duration,
    val mark: TimeMark,
  )

  private var run: Run? = null
  private var generation = 0
  private var heldEdge: ShuttleEdge? = null
  private var poll: Job? = null

  /**
   * The rate of the shuttle running, or null when none is.
   */
  val rate: Float? get() = run?.rate

  /**
   * Whether a frame has been asked for and has not shown yet.
   */
  var isInFlight: Boolean = false
    private set

  /**
   * The slot of the frame on screen, as of the last frame this pump showed or was started on.
   */
  var shown: Duration = Duration.ZERO
    private set

  /**
   * Starts a shuttle at [rate] from [origin], the position of the frame on screen.
   *
   * On a pump already running this changes the rate and carries on from the frame showing, and [origin] is not read.
   */
  fun begin(
    rate: Float,
    origin: Duration,
  ) {
    if (run == null) shown = landingOf(origin)
    rebase(rate, shown)
  }

  /**
   * Moves a running shuttle to [origin], keeping its rate. Does nothing when no shuttle is running.
   */
  fun moveTo(origin: Duration) {
    val rate = run?.rate ?: return
    rebase(rate, origin)
  }

  /**
   * Ends the shuttle. A frame still on its way is reported through the late frame callback when it arrives.
   */
  fun end() {
    if (run == null) return
    run = null
    generation++
    isInFlight = false
    heldEdge = null
    poll?.cancel()
    poll = null
  }

  /**
   * Reads the playhead again, for a platform that has just become able to take a request.
   */
  fun kick() {
    step()
  }

  private fun rebase(
    rate: Float,
    origin: Duration,
  ) {
    run = Run(rate, origin, timeSource.markNow())
    heldEdge = null
    step()
  }

  private fun step() {
    val run = run ?: return
    if (isInFlight) return
    poll?.cancel()
    poll = null

    val bounds = bounds()
    if (bounds == null || !canPresent()) {
      waitAndStep()
      return
    }

    val playhead = shuttlePlayheadAt(run.origin, run.rate, run.mark.elapsedNow(), bounds)
    if (playhead.edge != null && playhead.edge == heldEdge) return

    val landing = landingOf(playhead.position)
    if (landing == shown) {
      holdEdge(playhead.edge, landing)
      if (playhead.edge == null) waitAndStep()
      return
    }

    isInFlight = true
    val issuedIn = generation
    present(playhead.position) { landed ->
      if (issuedIn != generation) {
        if (this.run == null) onLateFrame(landed)
        return@present
      }
      isInFlight = false
      shown = landed
      onFrame(landed)
      holdEdge(playhead.edge, landed)
      step()
    }
  }

  private fun holdEdge(
    edge: ShuttleEdge?,
    position: Duration,
  ) {
    if (edge == heldEdge) return
    heldEdge = edge
    if (edge != null) onEdge(edge, position)
  }

  private fun waitAndStep() {
    poll =
      scope.launch {
        delay(pollInterval)
        poll = null
        step()
      }
  }
}

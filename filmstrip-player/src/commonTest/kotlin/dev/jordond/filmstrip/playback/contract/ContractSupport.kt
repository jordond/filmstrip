package dev.jordond.filmstrip.playback.contract

import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.player.EngineListener
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlayerEngine
import dev.jordond.filmstrip.player.PlayerState
import dev.jordond.filmstrip.player.PreviewFrameReadback
import dev.jordond.filmstrip.player.PreviewInfo
import dev.jordond.filmstrip.player.ReadbackFrame
import dev.jordond.filmstrip.player.ReadbackResult
import dev.jordond.filmstrip.player.SetCompositionRequest
import dev.jordond.filmstrip.player.SetCompositionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Records everything an engine publishes, from whichever dispatcher that engine runs on.
 *
 * Every field is replaced with a fresh immutable value rather than mutated in place, and read back
 * through a volatile, so a suite polling from the test's own coroutine sees a whole list rather
 * than one being appended to underneath it.
 *
 * Nothing counts state callbacks. A listener that changes an axis from inside `onStateChanged`
 * makes the engine redeliver, so the number of calls carries no meaning. Read [lastState], or ask
 * what the states since a [mark] were.
 */
class ContractRecorder : EngineListener {
  @Volatile
  private var recordedStates: List<PlayerState> = emptyList()

  @Volatile
  private var recordedEvents: List<PlaybackEvent> = emptyList()

  @Volatile
  private var recordedPreviewInfo: List<PreviewInfo> = emptyList()

  @Volatile
  private var recordedPosition: Duration? = null

  /**
   * Every snapshot delivered, in order, duplicates included.
   */
  val states: List<PlayerState> get() = recordedStates

  /**
   * Every event delivered, in order. Events are edge-triggered and delivered once, so these count.
   */
  val events: List<PlaybackEvent> get() = recordedEvents

  /**
   * What the preview reported it was delivering, in order.
   */
  val previewInfo: List<PreviewInfo> get() = recordedPreviewInfo

  /**
   * The most recent playhead reading, or null before one arrives.
   */
  val playhead: Duration? get() = recordedPosition

  /**
   * The current snapshot, which registering a listener delivers straight away.
   */
  val lastState: PlayerState
    get() = recordedStates.lastOrNull() ?: fail("The engine published no state at all.")

  val seekCompletions: List<PlaybackEvent.SeekCompleted>
    get() = recordedEvents.filterIsInstance<PlaybackEvent.SeekCompleted>()

  val externalChanges: List<PlaybackEvent.ExternalPlayWhenReadyChanged>
    get() = recordedEvents.filterIsInstance<PlaybackEvent.ExternalPlayWhenReadyChanged>()

  override fun onStateChanged(state: PlayerState) {
    recordedStates = recordedStates + state
  }

  override fun onEvent(event: PlaybackEvent) {
    recordedEvents = recordedEvents + event
  }

  override fun onPosition(position: Duration) {
    recordedPosition = position
  }

  override fun onPreviewInfo(info: PreviewInfo) {
    recordedPreviewInfo = recordedPreviewInfo + info
  }

  /**
   * Takes a point in the record, so a later assertion can talk about what happened after it.
   */
  fun mark(): ContractMark = ContractMark(recordedStates.size)

  /**
   * The snapshots delivered since [mark].
   */
  fun statesSince(mark: ContractMark): List<PlayerState> = recordedStates.drop(mark.states)
}

/**
 * A point in a [ContractRecorder]'s record.
 *
 * @property states How many snapshots had been delivered.
 */
class ContractMark(
  val states: Int,
)

/**
 * Runs one contract test body on a single serialised dispatcher, in real time.
 *
 * [PlayerEngine] implementations are confined to one dispatcher, so the suite drives the engine
 * from the same one its callbacks arrive on. Real time rather than virtual: a device backend
 * settles on the platform's own clock, which no test scheduler can advance.
 *
 * @param timeout How long the whole body may take. The default covers a backend that decodes on the
 *   host. A backend that has to encode something to compare against needs longer and says so.
 * @param body Receives the scope engines should be built on. It is cancelled when the test ends.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun contractTest(
  timeout: Duration = CONTRACT_BODY_TIMEOUT,
  body: suspend (CoroutineScope) -> Unit,
): TestResult =
  runTest(timeout = timeout) {
    val dispatcher = Dispatchers.Default.limitedParallelism(1)
    val engineScope = CoroutineScope(dispatcher + Job())
    try {
      when (val pump = contractPump) {
        null -> {
          withContext(dispatcher) { body(engineScope) }
        }
        else -> {
          val work = engineScope.async { body(engineScope) }
          val deadline = TimeSource.Monotonic.markNow() + PUMP_BUDGET
          while (!work.isCompleted) {
            if (deadline.hasPassedNow()) fail("The contract body did not finish within $PUMP_BUDGET of pumping.")
            pump()
          }
          work.await()
        }
      }
    } finally {
      engineScope.cancel()
    }
  }

/**
 * Runs the platform's own event loop for a moment, or null where a backend needs none.
 *
 * A backend whose callbacks are delivered on the process's main thread installs one of these, and
 * the suite then drives that loop from the main thread instead of parking it. AVFoundation is the
 * case that needs it: an `AVPlayerItem` stays in its unknown status forever unless the main run
 * loop is being run, and a Kotlin test parks the main thread inside a coroutine event loop that
 * services nothing else. It is set once per test class and read once per test, so a backend that
 * leaves it null gets exactly the harness it had before.
 */
internal var contractPump: (() -> Unit)? = null

/**
 * Registers a recorder on [engine], runs [body], and disposes the engine afterwards.
 */
internal suspend fun withEngine(
  engine: PlayerEngine,
  body: suspend (ContractRecorder) -> Unit,
) {
  val recorder = ContractRecorder()
  val registration = engine.addListener(recorder)
  try {
    body(recorder)
  } finally {
    registration.cancel()
    engine.dispose()
  }
}

/**
 * Polls [condition] until it holds, failing with [description] once the budget is spent.
 */
internal suspend fun awaitContract(
  description: String,
  timeout: Duration = CONTRACT_TIMEOUT,
  condition: () -> Boolean,
) {
  val deadline = TimeSource.Monotonic.markNow() + timeout
  while (!condition()) {
    if (deadline.hasPassedNow()) fail("Timed out after $timeout waiting for $description.")
    delay(POLL_INTERVAL)
  }
}

/**
 * Waits out a short real interval, giving an event that should never arrive the chance to.
 */
internal suspend fun settle() {
  delay(SETTLE_INTERVAL)
}

/**
 * Waits out many [settle] intervals, before an assertion that something never arrived.
 *
 * One settle only says the thing was not there yet, so a suite using it to claim absence is
 * asserting that it won a race. This waits long enough that a delivery which was going to happen
 * has had several chances, which is what makes the absence a finding rather than a timing win.
 */
internal suspend fun settleForAbsence() {
  repeat(ABSENCE_SETTLES) { settle() }
}

/**
 * Loads [composition] and suspends until the engine reports the outcome.
 */
internal suspend fun PlayerEngine.awaitComposition(
  composition: EditComposition,
  startAt: Duration? = null,
  playWhenReady: Boolean = false,
): SetCompositionResult =
  suspendCancellableCoroutine { continuation ->
    val request = SetCompositionRequest(composition, startAt, playWhenReady)
    val handle =
      setComposition(request) { result ->
        if (continuation.isActive) continuation.resume(result)
      }
    continuation.invokeOnCancellation { handle.cancel() }
  }

/**
 * Reads one rendered preview frame back, failing the test when the pipeline cannot produce it.
 */
internal suspend fun PreviewFrameReadback.awaitFrame(position: Duration): ReadbackFrame =
  suspendCancellableCoroutine { continuation ->
    val handle =
      requestFrame(position) { result ->
        if (continuation.isActive) {
          when (result) {
            is ReadbackResult.Success -> {
              continuation.resume(result.frame)
            }
            is ReadbackResult.Failure -> {
              continuation.resumeWithException(
                AssertionError("Readback at $position failed: ${result.error.message}"),
              )
            }
          }
        }
      }
    continuation.invokeOnCancellation { handle.cancel() }
  }

/**
 * How long any single contract wait may take before it is called a failure.
 *
 * Generous enough for a cold decoder on a mid-range device, and well inside the wall-clock limit
 * `runTest` applies to the whole test.
 */
internal val CONTRACT_TIMEOUT: Duration = 20.seconds

private val POLL_INTERVAL: Duration = 5.milliseconds

private val SETTLE_INTERVAL: Duration = 250.milliseconds

/**
 * How many settles an absence is claimed against.
 *
 * Twenty of them is five seconds, which covers a cold decoder answering late on a device and still
 * leaves room inside the per-test budget for the loads either side of the wait.
 */
private const val ABSENCE_SETTLES = 20

/**
 * How long a pumped test body may run before the suite calls it a hang.
 *
 * `runTest`'s own timeout cannot fire while the main thread is pumping a platform loop rather than
 * running the test scheduler, so the budget is enforced here instead. Generous against the per-wait
 * budget, since one test spends several of those.
 */
private val PUMP_BUDGET: Duration = 3.minutes

/**
 * How long a contract body gets by default, which is `runTest`'s own budget.
 */
private val CONTRACT_BODY_TIMEOUT: Duration = 60.seconds

package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.awaitStep
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.AvCallbackFence
import dev.jordond.filmstrip.player.internal.AvPlayerEngine
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import platform.darwin.dispatch_get_main_queue
import kotlin.test.Test
import kotlin.time.Duration

/**
 * What [AvCallbackFence.awaitDrained] waits for, each staged so it is the last thing to reach the engine.
 *
 * Each test holds the event behind a gate it opens once the drain is running, checks the drain has not returned while
 * the event is held, and then that the drain let it land. A drain missing the step a test stages returns before that
 * event lands.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleCallbackFenceMediaTest {
  init {
    pumpMainRunLoopDuringContracts()
  }

  /**
   * AVFoundation took about half a millisecond to answer each seek measured on the macOS host and the simulator, and
   * the echoes alone come back sooner, so each of several seeks has to be waited for on its own.
   */
  @Test
  fun `a seek still on its way is answered before the drain returns`() =
    withReadyEngine { _, engine, seekCompletions ->
      for (position in SEEK_POSITIONS) {
        val before = seekCompletions()
        engine.seekTo(position, SeekAccuracy.Exact)
        seekCompletions() shouldBe before

        engine.drainCallbacks()
        seekCompletions() shouldBe before + 1
      }
    }

  @Test
  fun `a callback the callback queue posts last lands before the drain returns`(): TestResult =
    stagedLast(CALLBACK_QUEUE)

  @Test
  fun `a callback the main queue posts last lands before the drain returns`(): TestResult = stagedLast(MAIN_QUEUE)

  /**
   * The seek goes out from the main queue while the drain is echoing through it, after the drain has already waited for
   * the seeks it found, so only going round again covers it.
   */
  @Test
  fun `a seek a callback issues while the drain runs is waited for`() =
    withReadyEngine { scope, engine, seekCompletions ->
      val before = seekCompletions()
      val gate = QueueGate()
      gate.hold(dispatch_get_main_queue()) {
        scope.launch { engine.seekTo(PROBE_POSITIONS.first(), SeekAccuracy.Exact) }
      }
      try {
        val drained = scope.async(start = CoroutineStart.UNDISPATCHED) { engine.drainCallbacks() }
        seekCompletions() shouldBe before
        drained.isActive shouldBe true

        gate.open()
        drained.await()
      } finally {
        gate.open()
      }
      seekCompletions() shouldBe before + 1
      engine.fence.unansweredSeeks shouldBe 0
    }

  /**
   * Posts one held callback to each platform queue and starts the drain, then lets the one not named [last] land and
   * the one named [last] after it.
   *
   * The drain has queued its echo behind the held callbacks by the time it is checked, so a drain that waits for both
   * is still running until the last gate opens.
   */
  private fun stagedLast(last: String): TestResult =
    contractTest { scope ->
      val engine = AppleEngineUnderTest(scope) { _, _ -> }.engine
      withEngine(engine) {
        // Only touched on the engine's dispatcher.
        val landed = mutableListOf<String>()
        val queues = listOf(CALLBACK_QUEUE to engine.callbackQueue, MAIN_QUEUE to dispatch_get_main_queue())
        val gates = queues.associate { (name, _) -> name to QueueGate() }
        val arrivals = queues.associate { (name, _) -> name to CompletableDeferred<Unit>() }
        queues.forEach { (name, queue) ->
          gates.getValue(name).hold(queue) {
            scope.launch {
              landed += name
              arrivals.getValue(name).complete(Unit)
            }
          }
        }
        try {
          landed.shouldBeEmpty()
          val drained = scope.async(start = CoroutineStart.UNDISPATCHED) { engine.drainCallbacks() }
          val first = queues.first { it.first != last }.first
          gates.getValue(first).open()
          arrivals.getValue(first).await()
          drained.isActive shouldBe true

          gates.getValue(last).open()
          drained.await()
        } finally {
          gates.values.forEach(QueueGate::open)
        }
        landed shouldBe queues.map { it.first }.sortedBy { it == last }
      }
    }

  private fun withReadyEngine(body: suspend (CoroutineScope, AvPlayerEngine, () -> Int) -> Unit): TestResult =
    contractTest { scope ->
      val engine = AppleEngineUnderTest(scope) { _, _ -> }.engine
      withEngine(engine) { recorder ->
        engine.awaitComposition(appleFixtureComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the item to be ready to seek") { recorder.lastState.status == PlaybackStatus.Ready }
        body(scope, engine) { recorder.seekCompletions.size }
      }
    }

  private companion object {
    const val CALLBACK_QUEUE = "the callback queue"
    const val MAIN_QUEUE = "the main queue"

    val SEEK_POSITIONS: List<Duration> = (1..5).map { FIXTURE_FRAME_STEP * (it * 7) + FIXTURE_FRAME_STEP / 3 }
  }
}

/**
 * Waits for [AvCallbackFence.awaitDrained] as a named step, before asserting that something never arrived.
 *
 * Everything the engine has handed to the item or AVFoundation has already posted to it has landed once this returns,
 * so an absence checked afterwards is not a race the check happened to win.
 */
internal suspend fun AvPlayerEngine.drainCallbacks() {
  awaitStep("AVFoundation's callbacks to reach the engine") { fence.awaitDrained() }
}

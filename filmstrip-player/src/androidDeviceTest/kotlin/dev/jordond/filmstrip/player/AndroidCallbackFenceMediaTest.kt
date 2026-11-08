package dev.jordond.filmstrip.player

import android.os.Handler
import android.os.HandlerThread
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.awaitStep
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.settle
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.Media3CallbackFence
import dev.jordond.filmstrip.player.internal.Media3LoopRangeController.Companion.MIRROR_INTERVAL_MS
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test

/**
 * What [Media3CallbackFence.awaitDrained] waits for, staged so the event arrives after any fixed wait would have ended.
 *
 * The test names are camel case because this class is compiled into an Android instrumented APK.
 */
class AndroidCallbackFenceMediaTest {
  /**
   * The player's thread is held shut behind a gate, so the seek media3 answers sits in its queue through a settle and
   * is only handed over once the fence is already waiting. A check after the settle sees no completion, which is the
   * absence a contract would wrongly have claimed, and the fenced check sees it land.
   */
  @Test
  fun aSeekHeldOnTheBusyPlayerThreadLandsBeforeTheFenceReturns(): TestResult =
    contractTest { scope ->
      val engine = AndroidEngineUnderTest(scope).engine
      withEngine(engine) { recorder ->
        engine.awaitComposition(androidFixtureComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the player to be ready to seek") { recorder.lastState.status == PlaybackStatus.Ready }

        val gate = CountDownLatch(1)
        engine.fence.handler.post { gate.await(GATE_SECONDS, TimeUnit.SECONDS) }
        try {
          val before = recorder.seekCompletions.size
          engine.seekTo(PROBE_POSITIONS.last(), SeekAccuracy.Exact)
          settle()
          recorder.seekCompletions.size shouldBe before

          val drained = scope.async(start = CoroutineStart.UNDISPATCHED) { engine.fence.awaitDrained() }
          drained.isCompleted shouldBe false
          gate.countDown()
          awaitStep("the fence to return") { drained.await() }
          recorder.seekCompletions.size shouldBe before + 1
        } finally {
          gate.countDown()
        }
      }
    }

  /**
   * media3's playback thread is held shut behind a gate with a report queued behind it, the way a state change waits
   * there before it is handed back to the player's thread. The fence is still waiting once a settle has passed, and
   * the report has landed by the time it returns.
   */
  @Test
  fun aReportHeldOnThePlaybackThreadLandsBeforeTheFenceReturns(): TestResult =
    contractTest { scope ->
      val engine = AndroidEngineUnderTest(scope).engine
      withEngine(engine) { recorder ->
        engine.awaitComposition(androidFixtureComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the player to be ready") { recorder.lastState.status == PlaybackStatus.Ready }

        val playback = Handler(checkNotNull(engine.fence.playbackLooper()) { "No player is standing." })
        val gate = CountDownLatch(1)
        var landed = false
        playback.post {
          gate.await(GATE_SECONDS, TimeUnit.SECONDS)
          engine.fence.handler.post { scope.launch { landed = true } }
        }
        try {
          val drained =
            scope.async(start = CoroutineStart.UNDISPATCHED) {
              engine.fence.awaitDrained()
              landed
            }
          settle()
          drained.isCompleted shouldBe false
          gate.countDown()
          awaitStep("the fence to return") { drained.await() } shouldBe true
        } finally {
          gate.countDown()
        }
      }
    }

  /**
   * A callback already scheduled on the player's thread one mirror interval out, the way the next playhead reading is,
   * launches onto the engine. The fence returns only after that launch has run.
   */
  @Test
  fun aCallbackScheduledOneMirrorIntervalOutLandsBeforeTheFenceReturns(): TestResult =
    contractTest { scope ->
      withFence(scope) { fence, engine ->
        var landed = false
        fence.handler.postDelayed({ engine.launch { landed = true } }, MIRROR_INTERVAL_MS)

        awaitStep("the fence to return") {
          fence.awaitDrained()
          landed
        } shouldBe true
      }
    }

  /**
   * A callback the fence drains issues a seek, and media3 answers it well after the pass that found the callback has
   * ended. The fence goes round again until the answer has landed.
   */
  @Test
  fun aSeekIssuedWhileTheFenceDrainsLandsBeforeItReturns(): TestResult =
    contractTest { scope ->
      var inFlight = false
      var landed = false
      withFence(scope, seekInFlight = { inFlight }) { fence, engine ->
        val answer =
          Runnable {
            engine.launch {
              inFlight = false
              landed = true
            }
          }
        fence.handler.postDelayed(
          {
            engine.launch {
              inFlight = true
              fence.handler.postDelayed(answer, LATE_ANSWER_MS)
            }
          },
          MIRROR_INTERVAL_MS,
        )

        awaitStep("the fence to return") {
          fence.awaitDrained()
          landed
        } shouldBe true
      }
    }

  /**
   * Work on a thread that is neither the player's nor media3's playback thread, the way a codec's callback or the video
   * graph's work is, has not reached either looper and is not waited for. The thread is held until the fence has
   * returned, so its report cannot have landed.
   */
  @Test
  fun workOnAThreadOutsideBothLoopersIsNotWaitedFor(): TestResult =
    contractTest { scope ->
      withFence(scope) { fence, engine ->
        val gate = CountDownLatch(1)
        var landed = false
        thread(name = "fence-outside") {
          gate.await(GATE_SECONDS, TimeUnit.SECONDS)
          fence.handler.post { engine.launch { landed = true } }
        }
        try {
          awaitStep("the fence to return") {
            fence.awaitDrained()
            landed
          } shouldBe false
        } finally {
          gate.countDown()
        }
      }
    }

  /**
   * Builds a fence over a player's thread and a playback thread of its own, on an engine scope that is a child of
   * [scope], and hands both to [body].
   */
  private suspend fun withFence(
    scope: CoroutineScope,
    seekInFlight: () -> Boolean = { false },
    body: suspend (Media3CallbackFence, CoroutineScope) -> Unit,
  ) {
    val player = HandlerThread("fence-player").apply { start() }
    val playback = HandlerThread("fence-playback").apply { start() }
    val engine = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    try {
      body(Media3CallbackFence(engine, Handler(player.looper), { playback.looper }, seekInFlight), engine)
    } finally {
      engine.cancel()
      player.quitSafely()
      playback.quitSafely()
    }
  }

  private companion object {
    // A ceiling on how long the gate can hold a thread should the test fail before opening it.
    const val GATE_SECONDS = 30L

    // Far past one pass of the fence, so a fence that stopped after one pass returns before the answer.
    const val LATE_ANSWER_MS = MIRROR_INTERVAL_MS * 10
  }
}

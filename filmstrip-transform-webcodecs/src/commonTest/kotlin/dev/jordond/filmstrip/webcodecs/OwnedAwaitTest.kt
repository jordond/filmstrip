@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.webcodecs.internal.interop.awaitOwned
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.await
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsName
import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.js.toJsString
import kotlin.js.unsafeCast
import kotlin.test.Test

/**
 * Who ends up holding a promise's value when its awaiter is cancelled, on real promises.
 */
class OwnedAwaitTest {
  @Test
  fun aValueResolvedAfterTheCancelIsReleasedOnce() =
    runTest {
      val pending = Pending()
      val released = mutableListOf<String>()
      val job = launch(start = CoroutineStart.UNDISPATCHED) { pending.promise.awaitOwned { released += it.toString() } }

      job.cancelAndJoin()
      released.shouldBeEmpty()
      pending.resolve(VALUE.toJsString())
      nextTask()

      released shouldContainExactly listOf(VALUE)
    }

  // The awaiter is still suspended when the promise settles and the cancel lands in the same turn, so
  // the promise's callback finds a continuation that has already gone.
  @Test
  fun aValueResolvedInTheSameTurnAsTheCancelIsReleasedOnce() =
    runTest {
      val pending = Pending()
      val released = mutableListOf<String>()
      val job = launch(start = CoroutineStart.UNDISPATCHED) { pending.promise.awaitOwned { released += it.toString() } }

      pending.resolve(VALUE.toJsString())
      job.cancelAndJoin()
      nextTask()

      released shouldContainExactly listOf(VALUE)
    }

  // The promise has handed its value to the continuation and the resume sits queued on the dispatcher
  // when the cancel lands, so the value exists but the awaiter never runs to take it.
  @Test
  fun aValueWaitingOnTheDispatcherWhenTheCancelLandsIsReleasedOnce() =
    runTest {
      val pending = Pending()
      val released = mutableListOf<String>()
      val held = HeldDispatcher()
      var returned: String? = null
      val job =
        launch(held, start = CoroutineStart.UNDISPATCHED) {
          returned = pending.promise.awaitOwned { released += it.toString() }.toString()
        }

      pending.resolve(VALUE.toJsString())
      nextTask()
      held.queued shouldBe 1
      job.cancel()
      held.runAll()
      job.join()
      nextTask()

      returned shouldBe null
      released shouldContainExactly listOf(VALUE)
    }

  @Test
  fun aValueNobodyCancelledIsReturnedAndNotReleased() =
    runTest {
      val pending = Pending()
      val released = mutableListOf<String>()
      var returned: String? = null
      val job =
        launch(start = CoroutineStart.UNDISPATCHED) {
          returned = pending.promise.awaitOwned { released += it.toString() }.toString()
        }

      pending.resolve(VALUE.toJsString())
      job.join()
      nextTask()

      returned shouldBe VALUE
      released.shouldBeEmpty()
    }

  @Test
  fun aRejectionAfterTheCancelReleasesNothingAndGoesUnreported() =
    runTest {
      val pending = Pending()
      val released = mutableListOf<String>()
      val job = launch(start = CoroutineStart.UNDISPATCHED) { pending.promise.awaitOwned { released += it.toString() } }

      job.cancelAndJoin()
      val before = UnhandledRejections.seen
      pending.reject(BrowserError(MESSAGE))
      unhandledRejectionDelivered()

      released.shouldBeEmpty()
      UnhandledRejections.seen shouldBe before
    }

  @Test
  fun aRejectionNobodyCancelledThrowsWhatAPlainAwaitThrows() =
    runTest {
      val plain = Pending()
      val owned = Pending()
      val released = mutableListOf<String>()
      plain.reject(BrowserError(MESSAGE))
      owned.reject(BrowserError(MESSAGE))

      val fromAwait = runCatching { plain.promise.await() }.exceptionOrNull().shouldNotBeNull()
      val fromOwned = runCatching { owned.promise.awaitOwned { released += it.toString() } }.exceptionOrNull()

      fromOwned.shouldNotBeNull()
      fromOwned::class shouldBe fromAwait::class
      fromOwned.message shouldBe fromAwait.message
      released.shouldBeEmpty()
    }

  private class Pending {
    lateinit var resolve: (JsString) -> Unit
    lateinit var reject: (JsAny?) -> Unit
    val promise =
      BrowserPromise { onResolve, onReject ->
        resolve = onResolve
        reject = onReject
      }.unsafeCast<Promise<JsString>>()
  }

  /**
   * Holds what it is handed until the test says to run it, so a resumed continuation can be caught
   * waiting for its turn.
   */
  private class HeldDispatcher : CoroutineDispatcher() {
    private val tasks = ArrayDeque<Runnable>()

    val queued: Int get() = tasks.size

    override fun dispatch(
      context: CoroutineContext,
      block: Runnable,
    ) {
      tasks.addLast(block)
    }

    fun runAll() {
      while (tasks.isNotEmpty()) tasks.removeFirst().run()
    }
  }

  private object UnhandledRejections {
    var seen = 0

    init {
      addEventListener("unhandledrejection") { seen++ }
    }
  }

  private suspend fun nextTask() {
    Promise<JsAny?> { resolve, _ -> setTimeout({ resolve(null) }, 0) }.await()
  }

  // A browser reports an unhandled rejection in a task of its own after the microtasks drain, so this
  // waits past that task rather than for the next one.
  private suspend fun unhandledRejectionDelivered() {
    Promise<JsAny?> { resolve, _ -> setTimeout({ resolve(null) }, REJECTION_REPORT_MS) }.await()
  }

  private companion object {
    const val VALUE = "the value"
    const val MESSAGE = "the promise refused"
    const val REJECTION_REPORT_MS = 50
  }
}

// The stdlib's Promise types its reject callback per target, so this spells the constructor once for both.
@JsName("Promise")
private external class BrowserPromise(
  executor: (resolve: (JsString) -> Unit, reject: (JsAny?) -> Unit) -> Unit,
) : JsAny

@JsName("Error")
private external class BrowserError(
  message: String,
) : JsAny

private external fun setTimeout(
  handler: () -> Unit,
  delayMs: Int,
): Int

private external fun addEventListener(
  type: String,
  listener: (JsAny) -> Unit,
)

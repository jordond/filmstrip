@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.sample.tryit

import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.sample.loadPreset
import dev.jordond.filmstrip.sample.samplePresets
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.await
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.js.Promise
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Drives the browser's loader against a stand-in `fetch` the test answers by hand.
 */
class PresetLoaderAbortTest {
  private val preset = samplePresets.first()

  @BeforeTest
  fun install(): Unit = installFetchStand()

  @AfterTest
  fun remove(): Unit = removeFetchStand()

  @Test
  fun `cancelling a load aborts its fetch`() =
    runTest {
      val load = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) { loadPreset(preset) }
      standFetches() shouldBe 1

      load.cancel()

      standAborted() shouldBe true
      standMinted() shouldBe 0
    }

  @Test
  fun `a response the abort came too late for mints no url`() =
    runTest {
      val load = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) { loadPreset(preset) }

      load.cancel()
      standRespond(200)
      settlePromises()

      standMinted() shouldBe 0
    }

  @Test
  fun `a load that lands hands back the url it minted`() =
    runTest {
      val load = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) { loadPreset(preset) }

      standRespond(200)

      load.await() shouldBe MediaSource.ofUri("blob:stand")
      standMinted() shouldBe 1
    }

  @Test
  fun `a refused download keeps its reason`() =
    runTest {
      val load = async(Dispatchers.Default, CoroutineStart.UNDISPATCHED) { runCatching { loadPreset(preset) } }

      standRespond(503)

      assertFailsWith<Throwable> { load.await().getOrThrow() }.message shouldBe "The download answered HTTP 503."
    }

  @Test
  fun `a url minted just before the cancel is revoked`() =
    runTest {
      // A scheduler that only advances when told, so the load has its answer and has not yet been resumed with it.
      val scheduler = TestCoroutineScheduler()
      val held = CoroutineScope(StandardTestDispatcher(scheduler))
      val load = held.async(start = CoroutineStart.UNDISPATCHED) { loadPreset(preset) }
      standRespond(200)
      settlePromises()
      standMinted() shouldBe 1

      load.cancel()
      scheduler.runCurrent()

      standRevoked() shouldBe 1
    }

  private suspend fun settlePromises() {
    withContext(Dispatchers.Default) { nextTask().await<JsAny?>() }
  }
}

private fun installFetchStand(): Unit =
  js(
    """
    {
      const stand = { fetches: 0, signal: null, minted: 0, revoked: 0, answer: null,
        realFetch: globalThis.fetch, realMint: URL.createObjectURL, realRevoke: URL.revokeObjectURL }
      globalThis.fetchStand = stand
      globalThis.fetch = (url, init) => {
        stand.fetches++
        stand.signal = init.signal
        return new Promise(resolve => {
          stand.answer = status => resolve({ ok: status < 400, status, blob: () => Promise.resolve(new Blob()) })
        })
      }
      URL.createObjectURL = () => {
        stand.minted++
        return 'blob:stand'
      }
      URL.revokeObjectURL = () => {
        stand.revoked++
      }
    }
    """,
  )

private fun removeFetchStand(): Unit =
  js(
    """
    {
      globalThis.fetch = globalThis.fetchStand.realFetch
      URL.createObjectURL = globalThis.fetchStand.realMint
      URL.revokeObjectURL = globalThis.fetchStand.realRevoke
    }
    """,
  )

private fun standFetches(): Int = js("globalThis.fetchStand.fetches")

private fun standMinted(): Int = js("globalThis.fetchStand.minted")

private fun standRevoked(): Int = js("globalThis.fetchStand.revoked")

private fun standAborted(): Boolean = js("globalThis.fetchStand.signal.aborted")

private fun standRespond(status: Int): Unit = js("globalThis.fetchStand.answer(status)")

private fun nextTask(): Promise<JsAny?> = js("new Promise(resolve => setTimeout(() => resolve(null), 0))")

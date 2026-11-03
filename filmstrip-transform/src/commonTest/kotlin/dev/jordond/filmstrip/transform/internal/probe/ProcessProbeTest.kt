package dev.jordond.filmstrip.transform.internal.probe

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class ProcessProbeTest {
  @Test
  fun `racing first reads probe once and every read gets the one answer`() =
    runTest {
      var probes = 0
      val release = CompletableDeferred<Unit>()
      val probe =
        ProcessProbe {
          probes++
          release.await()
          ANSWER
        }

      val reads = List(3) { async { probe.read() } }
      release.complete(Unit)

      reads.awaitAll() shouldBe List(3) { ANSWER }
      probe.read() shouldBe ANSWER
      probes shouldBe 1
    }

  @Test
  fun `a probe that finished is kept when the caller that ran it is cancelled`() =
    runTest {
      var probes = 0
      val finished = CompletableDeferred<Unit>()
      val probe =
        ProcessProbe {
          probes++
          finished.complete(Unit)
          ANSWER
        }

      val first = launch { probe.read() }
      finished.await()
      first.cancel()

      probe.read() shouldBe ANSWER
      probes shouldBe 1
    }

  private companion object {
    const val ANSWER = "the device's encoders"
  }
}

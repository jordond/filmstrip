@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.media.FormatHint
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.test.UnreadableCase
import dev.jordond.filmstrip.transform.internal.ProbeOperation
import dev.jordond.filmstrip.webcodecs.internal.BrowserProber
import dev.jordond.filmstrip.webcodecs.internal.interop.DecoderStart
import dev.jordond.filmstrip.webcodecs.internal.interop.asString
import dev.jordond.filmstrip.webcodecs.internal.interop.await
import dev.jordond.filmstrip.webcodecs.internal.interop.settleWithin
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsName
import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.js.toJsString
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * How the browser prober stops a read that has not settled at its deadline, with injected reads so nothing real decodes
 * inside the window.
 */
class BrowserProbeDeadlineTest {
  @Test
  fun aPromiseThatNeverSettlesExpiresOnceAtTheDeadline() =
    runTest {
      var expired = 0

      val settled = settleWithin(neverSettles(), SHORT.inWholeMilliseconds.toDouble()) { expired++ }.await()

      settled.shouldBeNull()
      expired shouldBe 1
    }

  @Test
  fun aPromiseThatSettlesFirstGivesItsValueAndNeverExpires() =
    runTest {
      var expired = 0

      val settled =
        settleWithin(Promise.resolve(VALUE.toJsString()), LONG.inWholeMilliseconds.toDouble()) {
          expired++
        }.await()

      settled.shouldNotBeNull().unsafeCast<JsString>().asString() shouldBe VALUE
      expired shouldBe 0
    }

  @Test
  fun aDecoderStartThatExpiresRefusesAsTimedOut() =
    runTest {
      val garbled = UnreadableCase.GarbledMdat.bytesFrom(makeClip()).shouldNotBeNull()
      val source = MediaSource.ofBytes(garbled, FormatHint.Mp4)
      val prober = BrowserProber(deadline = SHORT, decoderStarts = { _, _ -> DecoderStart.Expired })

      val failure = prober.probe(source).shouldBeInstanceOf<ProbeResult.Failure>()

      val timedOut = failure.error.shouldBeInstanceOf<FilmstripError.TimedOut>()
      timedOut.operation shouldBe ProbeOperation.DECODER_START
      timedOut.deadline shouldBe SHORT
    }

  @Test
  fun aFirstFrameReadThatNeverSettlesRefusesAsTimedOutBeforeAnyDecoderStart() =
    runTest {
      val source = MediaSource.ofBytes(makeClip(), FormatHint.Mp4)
      var starts = 0
      val prober =
        BrowserProber(
          deadline = SHORT,
          firstSample = { awaitCancellation() },
          decoderStarts = { _, _ ->
            starts++
            DecoderStart.Started
          },
        )

      val failure = prober.probe(source).shouldBeInstanceOf<ProbeResult.Failure>()

      val timedOut = failure.error.shouldBeInstanceOf<FilmstripError.TimedOut>()
      timedOut.operation shouldBe ProbeOperation.FIRST_FRAME_DECODE
      timedOut.deadline shouldBe SHORT
      starts shouldBe 0
    }

  @Test
  fun aRealClipStillProbesUnderTheDefaultDeadline() =
    runTest {
      val source = MediaSource.ofBytes(makeClip(), FormatHint.Mp4)

      BrowserProber().probe(source).shouldBeInstanceOf<ProbeResult.Success>()
    }

  private fun neverSettles(): Promise<JsAny?> = PendingPromise { _, _ -> }.unsafeCast<Promise<JsAny?>>()

  private companion object {
    const val VALUE = "the value"
    val SHORT = 20.milliseconds
    val LONG = Duration.parse("30s")
  }
}

@JsName("Promise")
private external class PendingPromise(
  executor: (resolve: (JsString) -> Unit, reject: (JsAny?) -> Unit) -> Unit,
) : JsAny

@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.webcodecs.internal.SourceReader
import dev.jordond.filmstrip.webcodecs.internal.audio.BrowserAudioMix
import dev.jordond.filmstrip.webcodecs.internal.interop.AudioBuffer
import dev.jordond.filmstrip.webcodecs.internal.interop.AudioBufferSink
import dev.jordond.filmstrip.webcodecs.internal.interop.InputAudioTrack
import dev.jordond.filmstrip.webcodecs.internal.interop.MediabunnyIterator
import dev.jordond.filmstrip.webcodecs.internal.interop.OfflineAudioContext
import dev.jordond.filmstrip.webcodecs.internal.interop.WrappedAudioBuffer
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * What a decode leaves behind in the sink it was reading when it stops short of the span's end, read off
 * a generated clip. A stopped decode has to close the sink's iterator, or the sink stays parked on a full
 * queue of open samples with its decoder alive.
 */
class AudioDecodeCloseMediaTest {
  @Test
  fun aDecodeCancelledBeforeItsFirstBufferClosesTheSink() =
    runTest {
      closesAfterCancelling(answered = 0) shouldBe 1
    }

  @Test
  fun aDecodeCancelledBetweenBuffersClosesTheSink() =
    runTest {
      closesAfterCancelling(answered = 2) shouldBe 1
    }

  @Test
  fun aDecodeWhoseSinkFailsClosesTheSink() =
    runTest {
      val reader = opened()
      val track = reader.firstAudioTrack().shouldNotBeNull()
      val real = AudioBufferSink(track).buffers(0.0, SPAN_SECONDS)
      var closes = 0
      val failing =
        stubIterator(
          next = { rejectedStep("the decoder gave up") },
          close = {
            closes++
            real.`return`(null)
          },
        )

      assertFailsWith<Throwable> { decode(track) { failing } }

      closes shouldBe 1
      reader.close()
    }

  /**
   * Runs a decode the sink answers [answered] pulls for and then leaves waiting, cancels it there, and
   * answers with how many times the sink was closed.
   */
  private suspend fun TestScope.closesAfterCancelling(answered: Int): Int {
    val reader = opened()
    val track = reader.firstAudioTrack().shouldNotBeNull()
    val real = AudioBufferSink(track).buffers(0.0, SPAN_SECONDS)
    val waiting = CompletableDeferred<Unit>()
    var pulls = 0
    var closes = 0
    val watched =
      stubIterator<WrappedAudioBuffer>(
        next = {
          if (pulls++ < answered) {
            real.next()
          } else {
            waiting.complete(Unit)
            pendingStep()
          }
        },
        close = {
          closes++
          real.`return`(null)
        },
      )

    val job = launch { decode(track) { watched } }
    waiting.await()
    job.cancelAndJoin()
    reader.close()
    return closes
  }

  private suspend fun decode(
    track: InputAudioTrack,
    open: () -> MediabunnyIterator<WrappedAudioBuffer>,
  ): AudioBuffer? = BrowserAudioMix.decode(track, 0.0, SPAN_SECONDS, OfflineAudioContext(1, FRAMES, RATE), open)

  private suspend fun opened(): SourceReader = SourceReader.of(MediaSource.Bytes(makeClipWithAudio())).shouldNotBeNull()

  private companion object {
    const val SPAN_SECONDS = 0.2
    const val FRAMES = 48_000
    const val RATE = 48_000f
  }
}

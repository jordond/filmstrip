@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.webcodecs.internal.FrameSampler
import dev.jordond.filmstrip.webcodecs.internal.FrameStream
import dev.jordond.filmstrip.webcodecs.internal.SourceReader
import dev.jordond.filmstrip.webcodecs.internal.interop.MediabunnyIterator
import dev.jordond.filmstrip.webcodecs.internal.interop.VideoSample
import dev.jordond.filmstrip.webcodecs.internal.interop.VideoSampleSink
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.await
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlin.math.abs
import kotlin.test.Test

/**
 * What happens to a decoded frame whose reader was cancelled while the decoder was still producing it,
 * read off a generated clip.
 *
 * mediabunny has no way to call a decode off, so the frame still arrives and has nobody to hand it to.
 */
class SampleReleaseTest {
  @Test
  fun aCancelledSampleAtReleasesTheFrameOnce() =
    runTest {
      val reader = opened()
      val released = Released()
      val sampler = FrameSampler(sink(reader), released::take)

      var returned: VideoSample? = null
      val job = launch(start = CoroutineStart.UNDISPATCHED) { returned = sampler.sampleAt(frameUs(MIDDLE)) }
      job.cancelAndJoin()
      released.first.await()
      // A second decode behind this one proves the first has nothing further to deliver.
      sampler.sampleAt(frameUs(MIDDLE)).shouldNotBeNull().close()

      returned.shouldBeNull()
      released.timestamps.single().shouldBeFrame(MIDDLE)
      reader.close()
    }

  // Before the first frame is the one place the sink answers with no frame at all.
  @Test
  fun aCancelledSampleAtThatFindsNoFrameReleasesNothing() =
    runTest {
      val reader = opened()
      val released = Released()
      val sink = sink(reader)
      val sampler = FrameSampler(sink, released::take)

      val job = launch(start = CoroutineStart.UNDISPATCHED) { sampler.sampleAt(BEFORE_FIRST_US) }
      job.cancelAndJoin()
      sink.getSample(BEFORE_FIRST_US / MICROS).await().shouldBeNull()
      nextTurn()

      released.timestamps.shouldBeEmpty()
      reader.close()
    }

  @Test
  fun aSampleAtNobodyCancelledReleasesNothing() =
    runTest {
      val reader = opened()
      val released = Released()

      val sample = FrameSampler(sink(reader), released::take).sampleAt(frameUs(MIDDLE)).shouldNotBeNull()

      sample.microsecondTimestamp.shouldBeFrame(MIDDLE)
      sample.close()
      released.timestamps.shouldBeEmpty()
      reader.close()
    }

  // The frames before the cancelled one were read normally, so a release that fired for those shows up
  // as a wrong timestamp or a second entry.
  @Test
  fun aCancelledNextInTheMiddleOfAStreamReleasesThatFrameOnce() =
    runTest {
      val reader = opened()
      val released = Released()
      val iterator = iterator(reader)
      val stream = FrameStream(iterator, released::take)
      repeat(MIDDLE) { stream.next().shouldNotBeNull().close() }

      var returned: VideoSample? = null
      val job = launch(start = CoroutineStart.UNDISPATCHED) { returned = stream.next() }
      job.cancelAndJoin()
      released.first.await()
      // The next step arrives after the cancelled one, so anything still owed has been delivered.
      iterator
        .next()
        .await()
        .value
        .shouldNotBeNull()
        .close()
      nextTurn()

      returned.shouldBeNull()
      released.timestamps.single().shouldBeFrame(MIDDLE)
      stream.close()
      released.timestamps.single().shouldBeFrame(MIDDLE)
      stream.next().shouldBeNull()
      reader.close()
    }

  // A window that clears closes its stream in the same turn it cancels the pull. The close ends the
  // iteration, so the pull answers done and the sink closes what it was holding.
  @Test
  fun aStreamClosedInTheSameTurnAsTheCancelLeavesNothingToRelease() =
    runTest {
      val reader = opened()
      val released = Released()
      val iterator = iterator(reader)
      val stream = FrameStream(iterator, released::take)
      repeat(MIDDLE) { stream.next().shouldNotBeNull().close() }

      val job = launch(start = CoroutineStart.UNDISPATCHED) { stream.next() }
      job.cancel()
      stream.close()
      job.join()
      iterator.next().await().done shouldBe true
      nextTurn()

      released.timestamps.shouldBeEmpty()
      reader.close()
    }

  @Test
  fun aCancelledNextOnTheDoneStepReleasesNothing() =
    runTest {
      val reader = opened()
      val released = Released()
      val iterator = iterator(reader)
      val stream = FrameStream(iterator, released::take)
      repeat(FRAMES) { stream.next().shouldNotBeNull().close() }

      val job = launch(start = CoroutineStart.UNDISPATCHED) { stream.next() }
      job.cancelAndJoin()
      iterator.next().await().done shouldBe true
      nextTurn()

      released.timestamps.shouldBeEmpty()
      stream.close()
      reader.close()
    }

  // mediabunny's own done step carries no value, so the guard only shows on a step that does.
  @Test
  fun aCancelledNextOnADoneStepCarryingAValueReleasesNothing() =
    runTest {
      val reader = opened()
      val released = Released()
      val sample = sink(reader).getSample(frameUs(MIDDLE) / MICROS).await().shouldNotBeNull()
      val stream = FrameStream(answering(done = true, sample), released::take)

      val job = launch(start = CoroutineStart.UNDISPATCHED) { stream.next() }
      job.cancelAndJoin()
      nextTurn()

      released.timestamps.shouldBeEmpty()
      sample.close()
      reader.close()
    }

  @Test
  fun aCancelledNextOnAStepThatIsNotDoneReleasesItsValue() =
    runTest {
      val reader = opened()
      val released = Released()
      val sample = sink(reader).getSample(frameUs(MIDDLE) / MICROS).await().shouldNotBeNull()
      val stream = FrameStream(answering(done = false, sample), released::take)

      val job = launch(start = CoroutineStart.UNDISPATCHED) { stream.next() }
      job.cancelAndJoin()
      nextTurn()

      released.timestamps.single().shouldBeFrame(MIDDLE)
      reader.close()
    }

  // A stream from the sampler frees a late frame the same way the sampler's own read does.
  @Test
  fun aCancelledNextOnASamplerStreamUsesTheSamplersRelease() =
    runTest {
      val reader = opened()
      val released = Released()
      val stream = FrameSampler(sink(reader), released::take).stream(0.0, Double.POSITIVE_INFINITY)
      repeat(MIDDLE) { stream.next().shouldNotBeNull().close() }

      val job = launch(start = CoroutineStart.UNDISPATCHED) { stream.next() }
      job.cancelAndJoin()
      stream.next().shouldNotBeNull().close()
      nextTurn()

      released.timestamps.single().shouldBeFrame(MIDDLE)
      stream.close()
      reader.close()
    }

  // The audio stream's release is its own lambda, and the only way to watch it is on the class, since
  // the late sample never reaches the test.
  @Test
  fun aCancelledNextOnAnAudioStreamClosesThatSampleOnce() =
    runTest {
      val reader = SourceReader.of(MediaSource.Bytes(makeClipWithAudio())).shouldNotBeNull()
      val timestamps = audioTimestamps(reader)
      (timestamps.size > MIDDLE + 1) shouldBe true
      val stream = reader.samples(0.0, Double.POSITIVE_INFINITY).shouldNotBeNull()
      val first = stream.next().shouldNotBeNull()
      val closed = mutableListOf<Double>()
      val restore = spyOnClose(first) { closed += it.microsecondTimestamp }
      try {
        first.close()
        repeat(MIDDLE - 1) { stream.next().shouldNotBeNull().close() }
        closed.clear()

        val job = launch(start = CoroutineStart.UNDISPATCHED) { stream.next() }
        job.cancelAndJoin()
        // The next pull answers after the cancelled one, so the late sample has landed by then.
        val following = stream.next().shouldNotBeNull()
        nextTurn()

        closed.count { it == timestamps[MIDDLE] } shouldBe 1
        closed.size shouldBe 1
        following.close()
      } finally {
        restore()
      }
      stream.close()
      reader.close()
    }

  // The reader's own stream has its own release lambda, so it is watched on the class like the audio one.
  @Test
  fun aCancelledNextOnAReaderFrameStreamClosesThatFrameOnce() =
    runTest {
      val reader = opened()
      val stream = reader.frames(0.0, Double.POSITIVE_INFINITY).shouldNotBeNull()
      val first = stream.next().shouldNotBeNull()
      val closed = mutableListOf<Double>()
      val restore = spyOnClose(first) { closed += it.microsecondTimestamp }
      try {
        first.close()
        repeat(MIDDLE - 1) { stream.next().shouldNotBeNull().close() }
        closed.clear()

        val job = launch(start = CoroutineStart.UNDISPATCHED) { stream.next() }
        job.cancelAndJoin()
        // The next pull answers after the cancelled one, so the late frame has landed by then.
        val following = stream.next().shouldNotBeNull()
        nextTurn()

        closed.single().shouldBeFrame(MIDDLE)
        following.close()
      } finally {
        restore()
      }
      stream.close()
      reader.close()
    }

  @Test
  fun aStreamNobodyCancelledReleasesNothing() =
    runTest {
      val reader = opened()
      val released = Released()
      val stream = FrameStream(iterator(reader), released::take)

      val seen = mutableListOf<Double>()
      while (true) {
        val sample = stream.next() ?: break
        seen += sample.microsecondTimestamp
        sample.close()
      }

      seen.size shouldBe FRAMES
      released.timestamps.shouldBeEmpty()
      stream.close()
      reader.close()
    }

  /**
   * Closes what it is handed, as production does, and remembers when each one was for.
   */
  private class Released {
    val timestamps = mutableListOf<Double>()
    val first = CompletableDeferred<Unit>()

    fun take(sample: VideoSample) {
      timestamps += sample.microsecondTimestamp
      sample.close()
      first.complete(Unit)
    }
  }

  private suspend fun opened(): SourceReader = SourceReader.of(MediaSource.Bytes(clip())).shouldNotBeNull()

  private suspend fun sink(reader: SourceReader): VideoSampleSink =
    VideoSampleSink(reader.videoTrack().shouldNotBeNull())

  private suspend fun iterator(reader: SourceReader): MediabunnyIterator<VideoSample> =
    sink(reader).samples(0.0, Double.POSITIVE_INFINITY)

  private fun answering(
    done: Boolean,
    sample: VideoSample,
  ): MediabunnyIterator<VideoSample> =
    stubIterator(
      next = { resolvedStep(done, sample) },
      close = { resolvedStep(true, null) },
    )

  private suspend fun audioTimestamps(reader: SourceReader): List<Double> {
    val stream = reader.samples(0.0, Double.POSITIVE_INFINITY).shouldNotBeNull()
    val timestamps = mutableListOf<Double>()
    while (true) {
      val sample = stream.next() ?: return timestamps
      timestamps += sample.microsecondTimestamp
      sample.close()
    }
  }

  private suspend fun clip(): ByteArray = bytes ?: makeClip(frames = FRAMES, frameRate = RATE).also { bytes = it }

  private fun Double.shouldBeFrame(index: Int) {
    (abs(this - frameUs(index)) < FRAME_US / 4) shouldBe true
  }

  private fun frameUs(index: Int): Double = index * FRAME_US

  private suspend fun nextTurn() {
    Promise.resolve<JsAny?>(null).await()
  }

  private companion object {
    const val FRAMES = 12
    const val MIDDLE = 5
    const val RATE = 30
    const val FRAME_US = 1_000_000.0 / RATE
    const val MICROS = 1_000_000.0
    const val BEFORE_FIRST_US = -1_000_000.0
    var bytes: ByteArray? = null
  }
}

package dev.jordond.filmstrip.player.internal.thumbnail

import android.graphics.Bitmap
import android.os.HandlerThread
import androidx.media3.inspector.frame.FrameExtractor
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.PlatformImage
import dev.jordond.filmstrip.thumbnail.ThumbnailBatchCallback
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.thumbnail.ThumbnailResult
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * How one thumbnail run settles each entry once between a cancellation and a completion, and who brings the reader
 * thread and the extractor down.
 *
 * The thread and the extractor are stand-ins, since media3's extractor cannot be loaded on a host JVM. What the run
 * does with them is the part asserted here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Media3ThumbnailRunTest {
  private val delivered = mutableListOf<Pair<Int, ThumbnailResult>>()
  private val run = Media3ThumbnailRun(ThumbnailBatchCallback { index, result -> delivered += index to result })
  private val thread = mockk<HandlerThread>(relaxed = true)
  private val extractor = mockk<FrameExtractor>(relaxed = true)

  @Test
  fun `a live run takes the reader thread and leaves it up`() {
    run.attach(thread) shouldBe true

    verify(exactly = 0) { thread.quitSafely() }
  }

  @Test
  fun `a run cancelled before its thread arrives quits the thread and refuses it`() {
    run.cancel()

    run.attach(thread) shouldBe false
    verify(exactly = 1) { thread.quitSafely() }
  }

  // Cancelling leaves the thread for the unwind to post its close to, and only release takes it down.
  @Test
  fun `cancelling leaves the thread up until the run is released`() {
    run.attach(thread)

    run.cancel()
    verify(exactly = 0) { thread.quitSafely() }

    run.release()
    run.release()
    verify(exactly = 1) { thread.quitSafely() }
  }

  @Test
  fun `a live run accepts the extractor it built`() =
    runTest {
      run.accepts(extractor, StandardTestDispatcher(testScheduler)) shouldBe true

      verify(exactly = 0) { extractor.close() }
    }

  @Test
  fun `a run cancelled while its extractor was built closes it on the reader`() =
    runTest {
      run.cancel()

      run.accepts(extractor, StandardTestDispatcher(testScheduler)) shouldBe false
      verify(exactly = 1) { extractor.close() }
    }

  // A run cancelled mid-decode unwinds through close, so the close has to outlast the cancellation that
  // started the unwind.
  @Test
  fun `closing the extractor outlasts the caller's own cancellation`() =
    runTest {
      val decoding =
        launch {
          try {
            awaitCancellation()
          } finally {
            run.close(extractor, StandardTestDispatcher(testScheduler))
          }
        }
      runCurrent()

      decoding.cancel()
      advanceUntilIdle()

      verify(exactly = 1) { extractor.close() }
    }

  @Test
  fun `a live run hands each result on at its own index`() {
    val image = PlatformImage(mockk<Bitmap>(relaxed = true))

    run.deliver(3, ThumbnailResult.Success(image, 2.seconds))

    delivered.single().first shouldBe 3
    image.asBitmap().shouldNotBeNull()
  }

  // The caller that would have owned the frame has gone, so the run closes it rather than leaking it.
  @Test
  fun `a frame that lands after cancelling is closed and never handed on`() {
    val image = PlatformImage(mockk<Bitmap>(relaxed = true))
    run.cancel()

    run.deliver(0, ThumbnailResult.Success(image, 1.seconds))
    run.deliver(1, ThumbnailResult.Failure(FAILURE))

    delivered shouldBe emptyList()
    image.asBitmap().shouldBeNull()
  }

  @Test
  fun `failing a group fails every entry at its offset in the batch`() {
    val composition = EditComposition(listOf(Track(listOf(Clip(MediaSource.of("/clips/strip.mp4"))))))
    val group = List(3) { ThumbnailRequest(composition, it.seconds, 90, 0L, precise = false) }

    run.failAll(group, offset = 4, error = FAILURE)

    delivered.map { it.first } shouldBe listOf(4, 5, 6)
    delivered.forEach { (_, result) -> (result as ThumbnailResult.Failure).error shouldBe FAILURE }
  }

  private companion object {
    val FAILURE = FilmstripError.InvalidComposition("the group could not be read")
  }
}

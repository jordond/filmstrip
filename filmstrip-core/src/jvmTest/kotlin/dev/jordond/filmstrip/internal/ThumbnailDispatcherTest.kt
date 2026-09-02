package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.internal.HeightAwareThumbnailSource.Companion.NATURAL_HEIGHT
import dev.jordond.filmstrip.media.FrameResult
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.PlatformImage
import dev.jordond.filmstrip.thumbnail.ThumbnailBatchCallback
import dev.jordond.filmstrip.thumbnail.ThumbnailCallback
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.thumbnail.ThumbnailResult
import dev.jordond.filmstrip.thumbnail.ThumbnailSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Who owns a frame the strip asked for, once nobody is left to receive it.
 *
 * A frame handed to a collector belongs to that collector. A frame produced after the collector has
 * gone away belongs to nobody, and the dispatcher is the last code that can still see it.
 */
class ThumbnailDispatcherTest {
  @Test
  fun `a frame produced for a cancelled collector is closed`() =
    runTest {
      val source = RecordingThumbnailSource()
      val filmstrip = Filmstrip { addThumbnailSourceFactory { _, _ -> source } }
      val received = mutableListOf<FrameResult>()

      val collector =
        launch {
          filmstrip.frames(COMPOSITION, POSITIONS, HEIGHT_PX).collect { result ->
            received += result
            cancel()
          }
        }
      collector.join()

      // Pinned first. Without it the count below reads zero against zero on a run that stopped
      // after the frame the collector took, which is the leak this is here to catch.
      source.images.size shouldBe POSITIONS.size

      // The first frame reached the collector and owns itself from there. Every other one was
      // rendered into a flow with nobody on the end of it.
      received.size shouldBe 1
      source.images[0].isClosed shouldBe false
      source.images.drop(1).count { it.isClosed } shouldBe POSITIONS.size - 1
    }

  @Test
  fun `a frame that lands releases the handle it was asked for through`() =
    runTest {
      val source = CountingHandleSource()
      val filmstrip = Filmstrip { addThumbnailSourceFactory { _, _ -> source } }

      val frame = launch { filmstrip.frame(COMPOSITION, Duration.ZERO) }
      runCurrent()
      source.answerAll()
      frame.join()

      source.issued shouldBe 1
      source.cancelled shouldBe 1
    }

  @Test
  fun `a source answering on the calling thread still has its handle released`() =
    runTest {
      val source = CountingHandleSource(answerInline = true)
      val filmstrip = Filmstrip { addThumbnailSourceFactory { _, _ -> source } }

      filmstrip.frame(COMPOSITION, Duration.ZERO)

      source.issued shouldBe 1
      source.cancelled shouldBe 1
    }

  @Test
  fun `frame asks its source for the height it was given`() =
    runTest {
      val source = HeightAwareThumbnailSource()
      val filmstrip = Filmstrip { addThumbnailSourceFactory { _, _ -> source } }

      val result = filmstrip.frame(COMPOSITION, Duration.ZERO, heightPx = 240)

      (result as FrameResult.Success).image.heightPx shouldBe 240
    }

  @Test
  fun `frame with no height asked for renders at the source's natural height`() =
    runTest {
      val source = HeightAwareThumbnailSource()
      val filmstrip = Filmstrip { addThumbnailSourceFactory { _, _ -> source } }

      val result = filmstrip.frame(COMPOSITION, Duration.ZERO)

      (result as FrameResult.Success).image.heightPx shouldBe HeightAwareThumbnailSource.NATURAL_HEIGHT
    }

  @Test
  fun `a serial run releases every request's handle, not only the last`() =
    runTest {
      val source = CountingHandleSource(answerInline = true)
      val filmstrip = Filmstrip { addThumbnailSourceFactory { _, _ -> source } }

      filmstrip.frames(COMPOSITION, POSITIONS, HEIGHT_PX).toList()

      source.issued shouldBe POSITIONS.size
      source.cancelled shouldBe POSITIONS.size
    }

  @Test
  fun `a source that throws part way through a run closes what it already handed over`() =
    runTest {
      val source = FailingThumbnailSource(deliverBefore = 2)
      val filmstrip = Filmstrip { addThumbnailSourceFactory { _, _ -> source } }

      shouldThrow<IllegalStateException> {
        filmstrip.frames(COMPOSITION, POSITIONS, HEIGHT_PX).toList()
      }

      source.images.size shouldBe 2
      source.images.count { it.isClosed } shouldBe 2
    }

  @Test
  fun `a strip asks its source for the whole run at once`() =
    runTest {
      val source = RecordingThumbnailSource()
      val filmstrip = Filmstrip { addThumbnailSourceFactory { _, _ -> source } }

      val received = filmstrip.frames(COMPOSITION, POSITIONS, HEIGHT_PX).toList()

      received.size shouldBe POSITIONS.size
      source.runs shouldBe listOf(POSITIONS)
    }

  @Test
  fun `a source that batches nothing still answers every position in order`() =
    runTest {
      val source = SerialOnlyThumbnailSource()
      val filmstrip = Filmstrip { addThumbnailSourceFactory { _, _ -> source } }

      val received = filmstrip.frames(COMPOSITION, POSITIONS, HEIGHT_PX).toList()

      received.map { (it as FrameResult.Success).presentationTime } shouldBe POSITIONS
      source.asked shouldBe POSITIONS
    }

  @Test
  fun `a frame the collector receives is left open`() =
    runTest {
      val source = RecordingThumbnailSource()
      val filmstrip = Filmstrip { addThumbnailSourceFactory { _, _ -> source } }

      val received = filmstrip.frames(COMPOSITION, POSITIONS, HEIGHT_PX).toList()

      received.size shouldBe POSITIONS.size
      source.images.size shouldBe POSITIONS.size
      source.images.count { it.isClosed } shouldBe 0
    }

  private companion object {
    const val HEIGHT_PX = 90

    val POSITIONS: List<Duration> = List(3) { (it * 500).milliseconds }

    val COMPOSITION: EditComposition =
      EditComposition(
        tracks = listOf(Track(listOf(Clip(MediaSource.of("strip.mp4"), TimeRange.of(Duration.ZERO, 4.seconds))))),
      )
  }
}

/**
 * A source that records how many of the handles it handed out were cancelled.
 *
 * [ThumbnailSource] tells an implementor that cancel() is where what one request held is freed, so
 * a source that frees anything there is entitled to see every handle cancelled however its request
 * ended. Answering on a later turn rather than inside [requestThumbnail] is what a real backend
 * does, and it is the path where the handle has been returned before the frame lands.
 */
private class CountingHandleSource(
  private val answerInline: Boolean = false,
) : ThumbnailSource {
  var issued: Int = 0
    private set
  var cancelled: Int = 0
    private set

  private val pending: MutableList<() -> Unit> = mutableListOf()

  override fun requestThumbnail(
    request: ThumbnailRequest,
    callback: ThumbnailCallback,
  ): Cancellable {
    issued++
    val answer = {
      val image = PlatformImage(BufferedImage(FRAME_WIDTH, FRAME_HEIGHT, BufferedImage.TYPE_INT_ARGB))
      callback.onThumbnail(ThumbnailResult.Success(image, request.position))
    }
    if (answerInline) answer() else pending += answer
    return Cancellable { cancelled++ }
  }

  /**
   * Answers everything asked for so far, in the order it was asked.
   */
  fun answerAll() {
    while (pending.isNotEmpty()) {
      pending.removeAt(0)()
    }
  }

  private companion object {
    const val FRAME_WIDTH = 16
    const val FRAME_HEIGHT = 9
  }
}

/**
 * A source with no decoder under it, answering every request where it stands.
 *
 * Answering inside [requestThumbnail] is what puts the frame in the dispatcher's hands before the
 * flow gets a chance to notice it has nobody to emit to, which is the case under test.
 */
private class RecordingThumbnailSource : ThumbnailSource {
  val images: MutableList<PlatformImage> = mutableListOf()
  val runs: MutableList<List<Duration>> = mutableListOf()

  override fun requestThumbnail(
    request: ThumbnailRequest,
    callback: ThumbnailCallback,
  ): Cancellable {
    callback.onThumbnail(ThumbnailResult.Success(frame(), request.position))
    return Cancellable { }
  }

  override fun requestThumbnails(
    requests: List<ThumbnailRequest>,
    callback: ThumbnailBatchCallback,
  ): Cancellable {
    runs += requests.map { it.position }
    requests.forEachIndexed { index, request ->
      callback.onThumbnail(index, ThumbnailResult.Success(frame(), request.position))
    }
    return Cancellable { }
  }

  private fun frame(): PlatformImage =
    PlatformImage(BufferedImage(FRAME_WIDTH, FRAME_HEIGHT, BufferedImage.TYPE_INT_ARGB))
      .also { images += it }

  private companion object {
    const val FRAME_WIDTH = 16
    const val FRAME_HEIGHT = 9
  }
}

/**
 * A source that hands over [deliverBefore] frames and then gives up on the run.
 *
 * The frames go into the dispatcher before the throw does, which is what leaves them queued behind
 * a collector loop that never starts.
 */
private class FailingThumbnailSource(
  private val deliverBefore: Int,
) : ThumbnailSource {
  val images: MutableList<PlatformImage> = mutableListOf()

  override fun requestThumbnail(
    request: ThumbnailRequest,
    callback: ThumbnailCallback,
  ): Cancellable = error("this source only fails a whole run")

  override fun requestThumbnails(
    requests: List<ThumbnailRequest>,
    callback: ThumbnailBatchCallback,
  ): Cancellable {
    requests.take(deliverBefore).forEachIndexed { index, request ->
      val image =
        PlatformImage(BufferedImage(FRAME_WIDTH, FRAME_HEIGHT, BufferedImage.TYPE_INT_ARGB))
          .also { images += it }
      callback.onThumbnail(index, ThumbnailResult.Success(image, request.position))
    }
    error("the decoder went away part way through the run")
  }

  private companion object {
    const val FRAME_WIDTH = 16
    const val FRAME_HEIGHT = 9
  }
}

/**
 * A source that overrides nothing, so the run reaches it one request at a time.
 *
 * What the default [ThumbnailSource.requestThumbnails] does is the thing under test, since it is
 * what every backend but Android's is served by.
 */
private class SerialOnlyThumbnailSource : ThumbnailSource {
  val asked: MutableList<Duration> = mutableListOf()

  override fun requestThumbnail(
    request: ThumbnailRequest,
    callback: ThumbnailCallback,
  ): Cancellable {
    asked += request.position
    val image = PlatformImage(BufferedImage(FRAME_WIDTH, FRAME_HEIGHT, BufferedImage.TYPE_INT_ARGB))
    callback.onThumbnail(ThumbnailResult.Success(image, request.position))
    return Cancellable { }
  }

  private companion object {
    const val FRAME_WIDTH = 16
    const val FRAME_HEIGHT = 9
  }
}

/**
 * A source that renders at the height it is asked for, the way a real backend does.
 *
 * Zero stands for no cap, so it renders at [NATURAL_HEIGHT] instead, proving the request's height
 * reached the source rather than being dropped along the way.
 */
private class HeightAwareThumbnailSource : ThumbnailSource {
  override fun requestThumbnail(
    request: ThumbnailRequest,
    callback: ThumbnailCallback,
  ): Cancellable {
    val height = request.heightPx.takeIf { it > 0 } ?: NATURAL_HEIGHT
    val image = PlatformImage(BufferedImage(FRAME_WIDTH, height, BufferedImage.TYPE_INT_ARGB))
    callback.onThumbnail(ThumbnailResult.Success(image, request.position))
    return Cancellable { }
  }

  companion object {
    const val NATURAL_HEIGHT = 1080
    private const val FRAME_WIDTH = 16
  }
}

/**
 * Whether this image's pixels have been released.
 */
private val PlatformImage.isClosed: Boolean get() = widthPx == 0

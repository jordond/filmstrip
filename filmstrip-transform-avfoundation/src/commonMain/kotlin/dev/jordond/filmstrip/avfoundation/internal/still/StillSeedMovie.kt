package dev.jordond.filmstrip.avfoundation.internal.still

import dev.jordond.filmstrip.avfoundation.internal.interop.PIXEL_BUFFER_HEIGHT_KEY
import dev.jordond.filmstrip.avfoundation.internal.interop.PIXEL_BUFFER_WIDTH_KEY
import dev.jordond.filmstrip.avfoundation.internal.interop.PIXEL_FORMAT_KEY
import dev.jordond.filmstrip.avfoundation.internal.time.MEDIA_TIMESCALE
import dev.jordond.filmstrip.avfoundation.internal.time.toCMTime
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.AVFoundation.AVAssetWriter
import platform.AVFoundation.AVAssetWriterInput
import platform.AVFoundation.AVAssetWriterInputPixelBufferAdaptor
import platform.AVFoundation.AVAssetWriterStatus
import platform.AVFoundation.AVAssetWriterStatusCompleted
import platform.AVFoundation.AVAssetWriterStatusWriting
import platform.AVFoundation.AVFileTypeQuickTimeMovie
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVVideoCodecKey
import platform.AVFoundation.AVVideoCodecTypeH264
import platform.AVFoundation.AVVideoHeightKey
import platform.AVFoundation.AVVideoWidthKey
import platform.AVFoundation.mediaTimeScale
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetDataSize
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferPoolCreatePixelBuffer
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferRefVar
import platform.CoreVideo.CVPixelBufferRelease
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.CoreVideo.kCVReturnSuccess
import platform.Foundation.NSURL
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time
import platform.posix.memset
import platform.posix.usleep
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * How far a seed write got: onto disk, as far as an encoder that had not stopped, or no further.
 */
internal enum class SeedWrite {
  Written,
  Slow,
  Failed,
}

@OptIn(ExperimentalForeignApi::class)
internal fun writeSeedMovie(
  path: String,
  frameRate: Int,
): SeedWrite {
  val url = NSURL.fileURLWithPath(path)
  val writer =
    AVAssetWriter.assetWriterWithURL(url, fileType = AVFileTypeQuickTimeMovie, error = null)
      ?: return SeedWrite.Failed

  val input =
    AVAssetWriterInput(
      mediaType = AVMediaTypeVideo,
      outputSettings =
        mapOf(
          AVVideoCodecKey to AVVideoCodecTypeH264,
          AVVideoWidthKey to SEED_SIDE,
          AVVideoHeightKey to SEED_SIDE,
        ),
    )
  input.expectsMediaDataInRealTime = false
  // Each frame goes in on its own tick at the media timescale, and the track keeps it there.
  input.mediaTimeScale = MEDIA_TIMESCALE
  val adaptor =
    AVAssetWriterInputPixelBufferAdaptor(
      assetWriterInput = input,
      sourcePixelBufferAttributes = stillSeedBufferAttributes(),
    )

  if (!writer.canAddInput(input)) return SeedWrite.Failed
  writer.addInput(input)
  if (!writer.startWriting()) return SeedWrite.Failed
  writer.startSessionAtSourceTime(Duration.ZERO.toCMTime())

  val frames = seedFrames(frameRate)
  val deadline = TimeSource.Monotonic.markNow() + STILL_SEED_WRITE_BUDGET
  repeat(frames) { index ->
    val append = adaptor.appendBlackFrame(writer, STILL_SEED_LENGTH * index / frames, deadline)
    if (append != FrameAppend.Appended) {
      writer.cancelWriting()
      return seedWriteFor(append)
    }
  }

  input.markAsFinished()
  // The last frame runs until here, which is what makes the track exactly as long as it claims.
  writer.endSessionAtSourceTime(STILL_SEED_LENGTH.toCMTime())
  return writer.finishAndWait(deadline)
}

/**
 * The buffers the seed writer's adaptor hands out: BGRA at the seed's own size.
 */
internal fun stillSeedBufferAttributes(): Map<Any?, Any?> =
  mapOf(
    PIXEL_FORMAT_KEY to kCVPixelFormatType_32BGRA.toInt(),
    PIXEL_BUFFER_WIDTH_KEY to SEED_SIDE,
    PIXEL_BUFFER_HEIGHT_KEY to SEED_SIDE,
  )

/**
 * How many frames a seed at [frameRate] carries, which is one for every frame the output holds over
 * [STILL_SEED_LENGTH].
 */
internal fun seedFrames(frameRate: Int): Int =
  (STILL_SEED_LENGTH.inWholeMilliseconds * frameRate / MILLIS_PER_SECOND).toInt().coerceAtLeast(1)

/**
 * Appends one black frame at [presentationTime], waiting on [writer] until [deadline] for somewhere
 * to put it.
 *
 * Only the wait running out answers [FrameAppend.TimedOut]. Everything else the writer refused, and
 * it refuses the same thing again next time.
 */
@OptIn(ExperimentalForeignApi::class)
private fun AVAssetWriterInputPixelBufferAdaptor.appendBlackFrame(
  writer: AVAssetWriter,
  presentationTime: Duration,
  deadline: TimeMark,
): FrameAppend {
  assetWriterInput.awaitReady(writer, deadline)?.let { return it }
  val appended =
    memScoped {
      val pool = pixelBufferPool ?: return@memScoped false
      val holder = alloc<CVPixelBufferRefVar>()
      if (CVPixelBufferPoolCreatePixelBuffer(null, pool, holder.ptr) != kCVReturnSuccess) return@memScoped false
      val buffer = holder.value ?: return@memScoped false

      try {
        buffer.blacken()
        appendPixelBuffer(buffer, withPresentationTime = presentationTime.toCMTime())
      } finally {
        CVPixelBufferRelease(buffer)
      }
    }
  return if (appended) FrameAppend.Appended else FrameAppend.Refused
}

/**
 * What came of putting one frame to a writer's input.
 */
internal enum class FrameAppend {
  Appended,
  TimedOut,
  Refused,
}

/**
 * How a seed write ends when one of its frames ended in [append].
 *
 * Only a frame that ran the wait out leaves the rate worth asking for again, since the encoder it
 * waited on is usually warm by the next lowering. Everything else the writer answered for itself,
 * and it answers the same way however often it is asked.
 */
internal fun seedWriteFor(append: FrameAppend): SeedWrite =
  when (append) {
    FrameAppend.Appended -> SeedWrite.Written
    FrameAppend.TimedOut -> SeedWrite.Slow
    FrameAppend.Refused -> SeedWrite.Failed
  }

/**
 * Whether an input reporting [ready] on a writer at [status] is worth another look.
 *
 * Only a writer that is still writing ever drains its input, so one in any other state is answered
 * on the poll that finds it rather than waited out.
 */
internal fun waitsForSeedInput(
  status: AVAssetWriterStatus,
  ready: Boolean,
): Boolean = !ready && status == AVAssetWriterStatusWriting

/**
 * Waits until [deadline] for this input to take another sample from [writer], and says what ended
 * the wait, or null once the input has room.
 *
 * An input stops accepting the moment its own queue is full, and says so only through this flag.
 * Appending anyway raises inside AVFoundation rather than answering false. Nothing here runs on the
 * queue that drains it, so sleeping between reads is what lets it empty.
 */
private fun AVAssetWriterInput.awaitReady(
  writer: AVAssetWriter,
  deadline: TimeMark,
): FrameAppend? {
  while (true) {
    val ready = readyForMoreMediaData
    if (!waitsForSeedInput(writer.status, ready)) return if (ready) null else FrameAppend.Refused
    if (deadline.hasPassedNow()) return FrameAppend.TimedOut
    usleep(READY_POLL_MICROS)
  }
}

/**
 * Zeroes the buffer, which a pool hands out with whatever the last user left in it.
 */
@OptIn(ExperimentalForeignApi::class)
private fun CVPixelBufferRef.blacken() {
  if (CVPixelBufferLockBaseAddress(this, 0uL) != kCVReturnSuccess) return
  try {
    CVPixelBufferGetBaseAddress(this)?.let { memset(it, 0, CVPixelBufferGetDataSize(this)) }
  } finally {
    CVPixelBufferUnlockBaseAddress(this, 0uL)
  }
}

/**
 * Finishes the write and waits until the file is closed or [deadline] passes, whichever lands first.
 *
 * AVFoundation only reports a finished write through a completion handler on a queue of its own,
 * and a lowering is not suspending, so the calling thread waits. It is never the queue that
 * answers, so nothing here can be waiting on itself. That wait is the only part [deadline] covers.
 * A wait that ran out cancels the writer still holding the file, and the cancel takes as long as
 * the encoder behind it takes to stop.
 *
 * The writer is read before it is cancelled, so a handler that fired as the budget ran out keeps
 * the movie it closed.
 */
private fun AVAssetWriter.finishAndWait(deadline: TimeMark): SeedWrite {
  val finished = dispatch_semaphore_create(0)
  finishWritingWithCompletionHandler { dispatch_semaphore_signal(finished) }
  val left = (-deadline.elapsedNow()).coerceAtLeast(Duration.ZERO)
  val timedOut = dispatch_semaphore_wait(finished, dispatch_time(DISPATCH_TIME_NOW, left.inWholeNanoseconds)) != 0L
  val write = seedFinishFor(timedOut, status)
  if (write == SeedWrite.Slow) cancelWriting()
  return write
}

/**
 * How a seed write ends when its finish [timedOut] or left the writer at [status].
 *
 * A writer that closed the file wrote a seed whatever the wait made of it, since a handler firing
 * as the budget runs out still leaves a movie there to cut from. A finish that ran out with nothing
 * closed is the same kind of slow as a frame that did, since the encoder it waited on is usually
 * warm by the next lowering. Every other end the writer answered for itself.
 */
internal fun seedFinishFor(
  timedOut: Boolean,
  status: AVAssetWriterStatus,
): SeedWrite =
  when {
    status == AVAssetWriterStatusCompleted -> SeedWrite.Written
    timedOut -> SeedWrite.Slow
    else -> SeedWrite.Failed
  }

private const val MILLIS_PER_SECOND = 1_000L

/**
 * How long one seed write has to reach the disk before the rate is given up for now.
 *
 * A frame this small is encoded in well under a millisecond once VideoToolbox is warm, but the first
 * session a process opens can take half a minute to come up on a cold simulator, and every frame of
 * the seed queues behind that one wait. The budget is the write's rather than each frame's, so a
 * writer that drains nothing costs this once and not once a frame. Closing the file comes out of the
 * same budget, since an encoder wedged at the finish holds the rate for just as long. Nothing waits
 * at all on a writer that stopped, since that is read off its status.
 */
internal val STILL_SEED_WRITE_BUDGET: Duration = 60.seconds

private const val READY_POLL_MICROS = 1_000u

/**
 * How much of a clip one cut from a seed covers.
 *
 * A still held for longer takes more than one cut and still lands on its exact length, since the
 * range a cut is asked for is the range it takes.
 */
internal val STILL_SEED_LENGTH: Duration = 1.seconds

// Nothing ever sees these pixels, so the frame is as small as an H.264 encoder will take. Every
// one of them accepts a whole number of macroblocks on both sides.
internal const val SEED_SIDE = 64

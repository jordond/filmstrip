package dev.jordond.filmstrip.avfoundation.internal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.useContents
import kotlinx.cinterop.value
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVAssetWriter
import platform.AVFoundation.AVAssetWriterInput
import platform.AVFoundation.AVAssetWriterInputPixelBufferAdaptor
import platform.AVFoundation.AVAssetWriterStatus
import platform.AVFoundation.AVAssetWriterStatusCompleted
import platform.AVFoundation.AVAssetWriterStatusWriting
import platform.AVFoundation.AVFileTypeQuickTimeMovie
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.AVVideoCodecKey
import platform.AVFoundation.AVVideoCodecTypeH264
import platform.AVFoundation.AVVideoHeightKey
import platform.AVFoundation.AVVideoWidthKey
import platform.AVFoundation.naturalSize
import platform.AVFoundation.nominalFrameRate
import platform.AVFoundation.tracksWithMediaType
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
import platform.Foundation.NSFileManager
import platform.Foundation.NSLock
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time
import platform.posix.memset
import platform.posix.rename
import platform.posix.usleep
import kotlin.math.abs
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * How much of a clip one cut from a seed covers.
 *
 * A still held for longer takes more than one cut and still lands on its exact length, since the
 * range a cut is asked for is the range it takes.
 */
internal val STILL_SEED_LENGTH: Duration = 1.seconds

/**
 * A seed track and how much of it there is to cut from.
 *
 * @property track The track a still's time is cut from.
 * @property length How long that track runs, which is the most one cut can take.
 */
internal class StillSeed(
  val track: AVAssetTrack,
  val length: Duration,
)

/**
 * What came of asking for the seed at one frame rate.
 *
 * [Slow] and [Failed] both open nothing. They are told apart because only one of them makes asking
 * again pointless: an encoder that was still working when the wait ran out is usually warm by the
 * next lowering, while a writer that stopped refuses every write after it.
 */
internal sealed interface SeedAttempt {
  class Opened(
    val asset: AVURLAsset,
  ) : SeedAttempt

  data object Slow : SeedAttempt

  data object Failed : SeedAttempt
}

/**
 * The seed an image clip's time is cut from at [frameRate], or null when none could be written.
 *
 * AVFoundation discards a composition track's trailing empty range and gives a track holding
 * nothing but empty ranges no duration at all, so a still taking its slot as empty time either
 * falls off the end of the timeline or leaves a composition that cannot be opened. Every image clip
 * occupies a real segment instead, cut from a movie of black frames written once per rate and read
 * back by every later lowering, whichever process wrote it. Those pixels never reach the output,
 * because [CoreImageChain] draws the still over the whole frame before anything measures or grades
 * it.
 *
 * The seed carries a frame for every one the output does, because the reader an export pulls
 * through composites what the source hands it rather than rendering on a grid of its own. A sparser
 * seed writes a sparser span.
 *
 * The asset is what is kept, not the track. A track holds no reference back to the asset it belongs
 * to, so one cached on its own outlives it and every insert from it is refused.
 *
 * Opened on whichever thread lowered first and read from every one after, so the open is behind a
 * lock rather than left to whichever lowering got there first. The lock is the rate's own, because a
 * write runs for as long as the encoder behind it takes and a lowering at any other rate has nothing
 * to gain by waiting that out. A rate whose writer refused the write is remembered as having no
 * seed, because the write blocks on VideoToolbox while holding that lock and retrying a refusal once
 * per lowering turns it into a stall on every later request. A write that only ran out of patience is
 * not remembered, so the next lowering asks for it again.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun stillSeed(frameRate: Int): StillSeed? {
  val lock = seedLockFor(frameRate)
  lock.lock()
  try {
    val asset = seedAsset(frameRate) ?: return null
    val track = asset.tracksWithMediaType(AVMediaTypeVideo).firstOrNull() as? AVAssetTrack ?: return null
    val length = asset.duration.toDuration()
    return if (length <= Duration.ZERO) null else StillSeed(track, length)
  } finally {
    lock.unlock()
  }
}

/**
 * The asset the seed at [frameRate] opened as, opening it when nothing has yet.
 *
 * Called with the rate's own lock held. A rate [seeds] already answers for is answered from there,
 * including one it holds no seed for.
 */
@OptIn(ExperimentalForeignApi::class)
private fun seedAsset(frameRate: Int): AVURLAsset? {
  withSeeds { if (seeds.containsKey(frameRate)) return seeds[frameRate] }
  return openSeed(frameRate)
}

/**
 * Opens the seed for [frameRate] and records in [seeds] what came back.
 *
 * Called with the lock [seedLockFor] hands out for that rate, so one writer at a rate runs at a time
 * and a second lowering at it reads what the first wrote.
 *
 * Only an attempt nothing will ever come of is recorded. A slow one leaves the map untouched, which
 * is what makes the rate an open question again rather than a refusal for the rest of the process.
 */
@OptIn(ExperimentalForeignApi::class)
private fun openSeed(frameRate: Int): AVURLAsset? {
  val asset =
    when (val attempt = stillSeedAsset(frameRate)) {
      is SeedAttempt.Opened -> attempt.asset
      SeedAttempt.Slow -> return null
      SeedAttempt.Failed -> null
    }
  withSeeds { seeds[frameRate] = asset }
  return asset
}

/**
 * Where the seed for [frameRate] is kept.
 *
 * One name per rate rather than a fresh one each time, so the temporary directory holds at most one
 * seed per rate no matter how often the library runs.
 */
internal fun stillSeedPath(frameRate: Int): String = NSTemporaryDirectory() + SEED_NAME + frameRate + SEED_EXTENSION

/**
 * The seed movie for [frameRate], read from [stillSeedPath] and written there first when what is
 * already there cannot be cut from.
 *
 * The file stays in the temporary directory for as long as anything might cut from the asset opened
 * over it.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun stillSeedAsset(frameRate: Int): SeedAttempt {
  val path = stillSeedPath(frameRate)
  return finishedSeed(path, frameRate)?.let(SeedAttempt::Opened) ?: writeStillSeed(path, frameRate)
}

/**
 * The lock the seed at [frameRate] is opened under, made on the first ask for that rate.
 *
 * One lock a rate rather than one over all of them, so a write spending its whole budget on a cold
 * encoder holds up nothing but later lowerings at that same rate.
 */
internal fun seedLockFor(frameRate: Int): NSLock = withSeeds { seedLocks.getOrPut(frameRate) { NSLock() } }

/**
 * Runs [block] holding the lock that every read and write of [seeds] and [seedLocks] goes through.
 *
 * Nothing but those two maps is touched under it, so it is only ever held for as long as a lookup
 * takes. The open it hands a lock out for runs under that lock instead.
 */
private inline fun <T> withSeeds(block: () -> T): T {
  tableLock.lock()
  try {
    return block()
  } finally {
    tableLock.unlock()
  }
}

private val tableLock = NSLock()

private val seeds = mutableMapOf<Int, AVURLAsset?>()

private val seedLocks = mutableMapOf<Int, NSLock>()

/**
 * The asset at [path] when it is a finished seed for [frameRate], or null when it is not.
 *
 * A movie only becomes readable once its writer has closed it, so one left by a process that died
 * part way through opens carrying no track at all. The shape, length and cadence are read as well,
 * so a file left by a build that cut stills differently is rewritten rather than cut from.
 */
@OptIn(ExperimentalForeignApi::class)
private fun finishedSeed(
  path: String,
  frameRate: Int,
): AVURLAsset? {
  if (!NSFileManager.defaultManager.fileExistsAtPath(path)) return null

  val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
  val track = asset.tracksWithMediaType(AVMediaTypeVideo).firstOrNull() as? AVAssetTrack ?: return null
  val frames = seedFrames(frameRate)

  val sized = track.naturalSize.useContents { width == SEED_SIDE.toDouble() && height == SEED_SIDE.toDouble() }
  val held = (asset.duration.toDuration() - STILL_SEED_LENGTH).absoluteValue <= STILL_SEED_LENGTH / frames
  val paced = abs(track.nominalFrameRate - frames / STILL_SEED_LENGTH.toDouble(DurationUnit.SECONDS)) < HALF_FRAME

  return asset.takeIf { sized && held && paced }
}

/**
 * Writes a seed movie carrying [frameRate] frames a second onto [path] and opens it, leaving
 * nothing behind where the write did not finish.
 *
 * The write lands on a name of its own and moves onto [path] in a single step, so nothing ever
 * reads a file still being written. Two processes racing each write the same frames, which leaves
 * whichever move landed last a seed they can both cut from.
 */
@OptIn(ExperimentalForeignApi::class)
private fun writeStillSeed(
  path: String,
  frameRate: Int,
): SeedAttempt {
  val staging = NSTemporaryDirectory() + STAGING_NAME + NSUUID().UUIDString() + SEED_EXTENSION
  val write = writeSeedMovie(staging, frameRate)
  if (write != SeedWrite.Written || rename(staging, path) != 0) {
    NSFileManager.defaultManager.removeItemAtPath(staging, error = null)
    return if (write == SeedWrite.Slow) SeedAttempt.Slow else SeedAttempt.Failed
  }
  return SeedAttempt.Opened(AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null))
}

/**
 * How far a seed write got: onto disk, as far as an encoder that had not stopped, or no further.
 */
internal enum class SeedWrite {
  Written,
  Slow,
  Failed,
}

@OptIn(ExperimentalForeignApi::class)
private fun writeSeedMovie(
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
private fun seedFrames(frameRate: Int): Int =
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

private const val SEED_NAME = "filmstrip-still-seed-"
private const val STAGING_NAME = SEED_NAME + "staging-"
private const val SEED_EXTENSION = ".mov"

// A rate is a whole number of frames a second, so a seed reading further than this from the cadence
// its name claims was cut at some other rate.
private const val HALF_FRAME = 0.5

// Nothing ever sees these pixels, so the frame is as small as an H.264 encoder will take. Every
// one of them accepts a whole number of macroblocks on both sides.
private const val SEED_SIDE = 64

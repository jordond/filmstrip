package dev.jordond.filmstrip.avfoundation.internal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVAssetWriter
import platform.AVFoundation.AVAssetWriterInput
import platform.AVFoundation.AVAssetWriterInputPixelBufferAdaptor
import platform.AVFoundation.AVAssetWriterStatusCompleted
import platform.AVFoundation.AVFileTypeQuickTimeMovie
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.AVVideoCodecKey
import platform.AVFoundation.AVVideoCodecTypeH264
import platform.AVFoundation.AVVideoHeightKey
import platform.AVFoundation.AVVideoWidthKey
import platform.AVFoundation.tracksWithMediaType
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetDataSize
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferPoolCreatePixelBuffer
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferRefVar
import platform.CoreVideo.CVPixelBufferRelease
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelBufferHeightKey
import platform.CoreVideo.kCVPixelBufferPixelFormatTypeKey
import platform.CoreVideo.kCVPixelBufferWidthKey
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.CoreVideo.kCVReturnSuccess
import platform.Foundation.NSFileManager
import platform.Foundation.NSLock
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.posix.memset
import platform.posix.usleep
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How much of a clip one cut from a seed covers.
 *
 * A still held for longer takes more than one cut and still lands on its exact length, since the
 * range a cut is asked for is the range it takes.
 */
internal val STILL_SEED_LENGTH: Duration = 1.seconds

/**
 * The track an image clip's time is cut from at [frameRate], or null when none could be written.
 *
 * AVFoundation discards a composition track's trailing empty range and gives a track holding
 * nothing but empty ranges no duration at all, so a still taking its slot as empty time either
 * falls off the end of the timeline or leaves a composition that cannot be opened. Every image clip
 * occupies a real segment instead, cut from a movie of black frames that this writes once per rate
 * and every later lowering reuses. Those pixels never reach the output, because [CoreImageChain]
 * draws the still over the whole frame before anything measures or grades it.
 *
 * The seed carries a frame for every one the output does, because the reader an export pulls
 * through composites what the source hands it rather than rendering on a grid of its own. A sparser
 * seed writes a sparser span.
 *
 * The asset is what is kept, not the track. A track holds no reference back to the asset it belongs
 * to, so one cached on its own outlives it and every insert from it is refused.
 *
 * Written on whichever thread lowered first and read from every one after, so the write is behind a
 * lock rather than left to whichever lowering got there first.
 */
internal fun stillSeedTrack(frameRate: Int): AVAssetTrack? {
  seedLock.lock()
  try {
    val asset = seeds.getOrPut(frameRate) { writeStillSeed(frameRate) } ?: return null
    return asset.tracksWithMediaType(AVMediaTypeVideo).firstOrNull() as? AVAssetTrack
  } finally {
    seedLock.unlock()
  }
}

private val seedLock = NSLock()

private val seeds = mutableMapOf<Int, AVURLAsset?>()

/**
 * Writes a seed movie carrying [frameRate] frames a second and opens it, leaving nothing behind
 * where the write did not finish.
 */
@OptIn(ExperimentalForeignApi::class)
private fun writeStillSeed(frameRate: Int): AVURLAsset? {
  val path = NSTemporaryDirectory() + SEED_NAME + NSUUID().UUIDString() + SEED_EXTENSION
  return writeSeedMovie(path, frameRate)
    ?: null.also { NSFileManager.defaultManager.removeItemAtPath(path, error = null) }
}

@OptIn(ExperimentalForeignApi::class)
private fun writeSeedMovie(
  path: String,
  frameRate: Int,
): AVURLAsset? {
  val url = NSURL.fileURLWithPath(path)
  val writer = AVAssetWriter.assetWriterWithURL(url, fileType = AVFileTypeQuickTimeMovie, error = null) ?: return null

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
      sourcePixelBufferAttributes =
        mapOf(
          kCVPixelBufferPixelFormatTypeKey to kCVPixelFormatType_32BGRA,
          kCVPixelBufferWidthKey to SEED_SIDE,
          kCVPixelBufferHeightKey to SEED_SIDE,
        ),
    )

  if (!writer.canAddInput(input)) return null
  writer.addInput(input)
  if (!writer.startWriting()) return null
  writer.startSessionAtSourceTime(Duration.ZERO.toCMTime())

  val frames = (STILL_SEED_LENGTH.inWholeMilliseconds * frameRate / MILLIS_PER_SECOND).toInt().coerceAtLeast(1)
  repeat(frames) { index ->
    if (!adaptor.appendBlackFrame(STILL_SEED_LENGTH * index / frames)) {
      writer.cancelWriting()
      return null
    }
  }

  input.markAsFinished()
  // The last frame runs until here, which is what makes the track exactly as long as it claims.
  writer.endSessionAtSourceTime(STILL_SEED_LENGTH.toCMTime())
  if (!writer.finishAndWait()) return null

  return AVURLAsset(uRL = url, options = null)
}

/**
 * Appends one black frame at [presentationTime].
 */
@OptIn(ExperimentalForeignApi::class)
private fun AVAssetWriterInputPixelBufferAdaptor.appendBlackFrame(presentationTime: Duration): Boolean {
  if (!assetWriterInput.awaitReady()) return false
  return memScoped {
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
}

/**
 * Waits until this input will take another sample, and says whether it ever did.
 *
 * An input stops accepting the moment its own queue is full, and says so only through this flag.
 * Appending anyway raises inside AVFoundation rather than answering false. Nothing here runs on the
 * queue that drains it, so sleeping between reads is what lets it empty.
 */
private fun AVAssetWriterInput.awaitReady(): Boolean {
  repeat(READY_POLLS) {
    if (readyForMoreMediaData) return true
    usleep(READY_POLL_MICROS)
  }
  return readyForMoreMediaData
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
 * Finishes the write and blocks until the file is closed.
 *
 * AVFoundation only reports a finished write through a completion handler on a queue of its own,
 * and a lowering is not suspending, so the calling thread waits. It is never the queue that
 * answers, so nothing here can be waiting on itself.
 */
private fun AVAssetWriter.finishAndWait(): Boolean {
  val finished = dispatch_semaphore_create(0)
  finishWritingWithCompletionHandler { dispatch_semaphore_signal(finished) }
  dispatch_semaphore_wait(finished, DISPATCH_TIME_FOREVER)
  return status == AVAssetWriterStatusCompleted
}

private const val MILLIS_PER_SECOND = 1_000L

// A tiny frame is encoded in well under a millisecond, so the queue only ever fills for as long as
// it takes VideoToolbox to drain one. A second of waiting is a writer that has stopped, not a slow
// one.
private const val READY_POLLS = 1_000
private const val READY_POLL_MICROS = 1_000u

private const val SEED_NAME = "filmstrip-still-seed-"
private const val SEED_EXTENSION = ".mov"

// Nothing ever sees these pixels, so the frame is as small as an H.264 encoder will take. Every
// one of them accepts a whole number of macroblocks on both sides.
private const val SEED_SIDE = 64

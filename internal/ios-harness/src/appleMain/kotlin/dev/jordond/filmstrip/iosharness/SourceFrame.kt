package dev.jordond.filmstrip.iosharness

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerItemVideoOutput
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.addOutput
import platform.AVFoundation.currentTime
import platform.AVFoundation.playable
import platform.AVFoundation.rate
import platform.AVFoundation.readable
import platform.AVFoundation.seekToTime
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.CoreVideo.CVPixelBufferCreate
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferRefVar
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.CoreVideo.kCVReturnSuccess
import platform.Foundation.NSDate
import platform.Foundation.NSRunLoop
import platform.Foundation.NSURL
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import platform.QuartzCore.CACurrentMediaTime
import platform.darwin.NSEC_PER_SEC

/**
 * Acquires the paused source frame through `AVPlayerItemVideoOutput`.
 *
 * `copyPixelBuffer(forItemTime:)` is the supported way to get decoded pixels out of a playing or
 * paused item, and it leaves `videoComposition` alone, which is the path still mode rules out.
 *
 * The output has to be attached before the item is ready, and the buffer only becomes available once
 * the decoder has produced the frame for that time, so the run loop is pumped until
 * `hasNewPixelBuffer(forItemTime:)` turns true rather than sleeping for a guessed interval.
 */
@OptIn(ExperimentalForeignApi::class)
class SourceFrameReader(
  url: NSURL,
  /**
   * The pixel format asked of the decoder.
   *
   * Null lets AVFoundation choose. Worth being able to vary: the format request is the part of this
   * path most likely to be refused, and a refusal surfaces as a decode failure rather than as an
   * unsupported-format error.
   */
  pixelFormat: UInt? = kCVPixelFormatType_32BGRA,
) {
  private val asset = AVURLAsset(uRL = url, options = null)
  private val item = AVPlayerItem(asset = asset)
  private val output =
    AVPlayerItemVideoOutput(
      pixelBufferAttributes =
        pixelFormat?.let { mapOf(platform.CoreVideo.kCVPixelBufferPixelFormatTypeKey to it) }
          ?: emptyMap<Any?, Any?>(),
    )
  private val player = AVPlayer(playerItem = item)

  init {
    item.addOutput(output)
  }

  /**
   * Why the last [frameAt] returned null, so a failure says which step failed.
   */
  var diagnostic: String = "not attempted"
    private set

  /**
   * @return the decoded frame at [seconds], or null if it never became available.
   */
  fun frameAt(
    seconds: Double,
    timeoutSeconds: Double = 20.0,
  ): CVPixelBufferRef? {
    if (!awaitReady(timeoutSeconds)) {
      diagnostic =
        buildString {
          append("item never reached readyToPlay: status=${item.status}")
          item.error?.let {
            append(" domain=${it.domain} code=${it.code} desc=${it.localizedDescription}")
            append(" underlying=${it.userInfo["NSUnderlyingError"]}")
          }
          append(" url=${asset.URL.absoluteString}")
          append(" readable=${asset.readable} playable=${asset.playable}")
        }
      return null
    }

    val target = CMTimeMakeWithSeconds(seconds, NSEC_PER_SEC.toInt())
    var seeked = false
    player.seekToTime(target) { seeked = true }
    pumpUntil(timeoutSeconds) { seeked }

    var buffer: CVPixelBufferRef? = null
    pumpUntil(timeoutSeconds) {
      val itemTime = output.itemTimeForHostTime(CACurrentMediaTime())
      val probe = if (output.hasNewPixelBufferForItemTime(itemTime)) itemTime else target
      if (output.hasNewPixelBufferForItemTime(probe)) {
        buffer = output.copyPixelBufferForItemTime(probe, itemTimeForDisplay = null)
      }
      buffer != null
    }
    return buffer
  }

  fun currentSeconds(): Double = CMTimeGetSeconds(player.currentTime())

  private fun awaitReady(timeoutSeconds: Double): Boolean =
    pumpUntil(timeoutSeconds) { item.status == AVPlayerItemStatusReadyToPlay }

  /**
   * Runs the current run loop in short slices until [condition] holds.
   *
   * `AVFoundation` delivers on the main run loop, so a blocking sleep would wait forever for work it
   * is itself preventing.
   */
  private fun pumpUntil(
    timeoutSeconds: Double,
    condition: () -> Boolean,
  ): Boolean {
    val deadline = CACurrentMediaTime() + timeoutSeconds
    while (CACurrentMediaTime() < deadline) {
      if (condition()) return true
      NSRunLoop.currentRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(PUMP_SLICE_SECONDS))
    }
    return condition()
  }

  private companion object {
    const val PUMP_SLICE_SECONDS = 0.005
  }
}

/**
 * Allocates a BGRA pixel buffer to render into, standing in for a `CAMetalLayer` drawable.
 */
@OptIn(ExperimentalForeignApi::class)
fun createPixelBuffer(
  width: Int,
  height: Int,
): CVPixelBufferRef? =
  memScoped {
    val holder = alloc<CVPixelBufferRefVar>()
    val status =
      CVPixelBufferCreate(
        allocator = null,
        width = width.toULong(),
        height = height.toULong(),
        pixelFormatType = kCVPixelFormatType_32BGRA,
        pixelBufferAttributes = null,
        pixelBufferOut = holder.ptr,
      )
    if (status == kCVReturnSuccess) holder.value else null
  }

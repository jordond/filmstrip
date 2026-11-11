@file:OptIn(ExperimentalForeignApi::class)

package dev.jordond.filmstrip.avfoundation.internal.writer

import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import dev.jordond.filmstrip.transform.internal.audio.timelineFrameOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFRangeMake
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreMedia.CMSampleBufferCopySampleBufferForRange
import platform.CoreMedia.CMSampleBufferGetNumSamples
import platform.CoreMedia.CMSampleBufferGetPresentationTimeStamp
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreMedia.CMSampleBufferRefVar
import kotlin.time.Duration

/**
 * Reads a mix up to where the composition ends and no further.
 *
 * A tap that read its source ahead has the reader hand that many frames of silence back once its tracks are over, on
 * some runs and not on others. That is AVFoundation's doing, so the mix is cut on the frame the timeline ends on,
 * which leaves it the same length every time.
 *
 * @param end How long the composition runs.
 * @param sampleRate The rate [read] hands its samples over at.
 * @param read Takes the next buffer of the mix, following the create rule, or null at the end.
 */
internal class MixEnd(
  end: Duration,
  private val sampleRate: Int,
  private val read: () -> CMSampleBufferRef?,
) {
  private val endFrame = timelineFrameOf(end, sampleRate)
  private var ended = false

  /**
   * The next buffer of the mix, following the create rule, cut short when it crosses the end of the composition, or
   * null once the mix or the composition is over.
   */
  fun next(): CMSampleBufferRef? {
    if (ended) return null
    val buffer = read() ?: return null
    val opens = timelineFrameOf(CMSampleBufferGetPresentationTimeStamp(buffer).toDuration(), sampleRate)
    val frames = CMSampleBufferGetNumSamples(buffer)
    if (opens + frames <= endFrame) return buffer

    ended = true
    val kept = if (opens < endFrame) head(buffer, endFrame - opens) else null
    CFRelease(buffer)
    // What the reader still holds is read out, so it ends having handed everything over.
    while (true) CFRelease(read() ?: break)
    return kept
  }

  private fun head(
    buffer: CMSampleBufferRef,
    frames: Long,
  ): CMSampleBufferRef? =
    memScoped {
      val out = alloc<CMSampleBufferRefVar>()
      val status = CMSampleBufferCopySampleBufferForRange(kCFAllocatorDefault, buffer, CFRangeMake(0, frames), out.ptr)
      check(status == 0) { "The end of the mixed audio could not be cut to the timeline, status $status." }
      out.value
    }
}

package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.audio.AudioKernel
import dev.jordond.filmstrip.export.AudioFormat

/**
 * A kernel that hands back what it was given [frames] frames late, and reports that as its latency.
 *
 * A host that takes the latency out again leaves the stream where it was, so a test reads the same audio with and
 * without it.
 */
internal class DelayKernel(
  format: AudioFormat,
  private val frames: Int,
) : AudioKernel() {
  private val channels = format.channelCount
  private val held = FloatArray(frames * channels)
  private var at = 0

  override val latencyFrames: Int get() = frames

  override fun process(
    buffer: FloatArray,
    frames: Int,
    position: Long,
  ) {
    for (index in 0 until frames * channels) {
      val delayed = held[at]
      held[at] = buffer[index]
      buffer[index] = delayed
      at = if (at + 1 == held.size) 0 else at + 1
    }
  }

  override fun reset(position: Long) {
    held.fill(0f)
    at = 0
  }
}

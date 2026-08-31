package dev.jordond.filmstrip.ffmpeg.internal

import dev.jordond.filmstrip.ffmpeg.PreviewStream
import dev.jordond.filmstrip.geometry.Size
import kotlin.time.Duration

/**
 * One ffmpeg process, handing over whole frames.
 *
 * The scratch directory holds whatever an overlay effect had to be written down for, so it lives
 * exactly as long as the process reading it.
 */
internal class FfmpegPreviewStream(
  private val frames: FrameStream,
  private val scratch: Scratch,
  override val size: Size,
  override val startPosition: Duration,
  private val frameBytes: Int,
) : PreviewStream {
  /**
   * The child's process id, so a test can prove the process is gone rather than trusting a flag.
   */
  val processId: Long? get() = frames.processId

  override suspend fun next(): ByteArray? {
    val frame = ByteArray(frameBytes)
    return if (frames.read(frame)) frame else null
  }

  override fun errors(): String = frames.errors()

  override fun close() {
    frames.close()
    scratch.delete()
  }
}

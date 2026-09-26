package dev.jordond.filmstrip.ffmpeg.internal

import dev.jordond.filmstrip.ffmpeg.PreviewStream
import dev.jordond.filmstrip.geometry.Size
import kotlin.time.Duration

/**
 * One ffmpeg process, handing over whole frames.
 *
 * Once the process has rendered the frames [split] takes from it, the last of them is handed over again for every held
 * frame [split] counts, and the stream ends with the process after that. The scratch directory holds whatever an
 * overlay effect had to be written down for, so it lives exactly as long as the process reading it.
 */
internal class FfmpegPreviewStream(
  private val frames: FrameStream,
  private val scratch: Scratch,
  override val size: Size,
  override val startPosition: Duration,
  private val frameBytes: Int,
  private val split: PreviewFrames = PreviewFrames(rendered = null, held = 0),
) : PreviewStream {
  private var rendered = 0L
  private var held = 0L
  private var last: ByteArray? = null

  /**
   * The child's process id, so a test can prove the process is gone rather than trusting a flag.
   */
  val processId: Long? get() = frames.processId

  override suspend fun next(): ByteArray? {
    if (rendered == split.rendered && held < split.held) {
      held++
      return checkNotNull(last) { NOTHING_TO_HOLD }.copyOf()
    }

    val frame = ByteArray(frameBytes)
    if (!frames.read(frame)) return null
    rendered++
    if (rendered == split.rendered && split.held > 0) last = frame.copyOf()
    return frame
  }

  override suspend fun exitStatus(): Int? = frames.exitStatus()

  override fun errors(): String = frames.errors()

  override fun close() {
    frames.close()
    scratch.delete()
  }
}

private const val NOTHING_TO_HOLD = "The preview holds a frame over the tail but the graph rendered none."

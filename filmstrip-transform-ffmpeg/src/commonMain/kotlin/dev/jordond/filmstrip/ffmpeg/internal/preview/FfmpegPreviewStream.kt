package dev.jordond.filmstrip.ffmpeg.internal.preview

import dev.jordond.filmstrip.ffmpeg.internal.lowering.PreviewFrames
import dev.jordond.filmstrip.ffmpeg.internal.process.FrameStream
import dev.jordond.filmstrip.ffmpeg.internal.process.LIVE_FEED_FAILED
import dev.jordond.filmstrip.ffmpeg.internal.process.LiveFeeds
import dev.jordond.filmstrip.ffmpeg.internal.process.Scratch
import dev.jordond.filmstrip.ffmpeg.internal.process.liveFeedFailure
import dev.jordond.filmstrip.geometry.Size
import kotlin.time.Duration

/**
 * One ffmpeg process, handing over whole frames.
 *
 * Once the process has rendered the frames [split] takes from it, the last of them is handed over again for every held
 * frame [split] counts, and the stream ends with the process after that. The scratch directory holds whatever an
 * overlay effect had to be written down for, so it lives exactly as long as the process reading it. So do [feeds], and
 * a feed that failed reads as a failed process, since ffmpeg takes its stream ending as a clean end of the graph.
 */
internal class FfmpegPreviewStream(
  private val frames: FrameStream,
  private val scratch: Scratch,
  override val size: Size,
  override val startPosition: Duration,
  private val frameBytes: Int,
  private val split: PreviewFrames = PreviewFrames(rendered = null, held = 0),
  private val feeds: LiveFeeds? = null,
) : PreviewStream {
  private var rendered = 0L
  private var held = 0L
  private var last: ByteArray? = null

  /**
   * The id of the child process the frames are read from, as [frames] reports it.
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

  override suspend fun exitStatus(): Int? {
    val status = frames.exitStatus()
    return if (feeds?.failure() != null) LIVE_FEED_FAILED else status
  }

  override fun errors(): String = feeds?.failure()?.let(::liveFeedFailure) ?: frames.errors()

  override fun close() {
    frames.close()
    feeds?.close()
    scratch.delete()
  }
}

private const val NOTHING_TO_HOLD = "The preview holds a frame over the tail but the graph rendered none."

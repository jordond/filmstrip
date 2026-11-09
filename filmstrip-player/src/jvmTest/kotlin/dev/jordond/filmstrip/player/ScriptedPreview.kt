package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.ffmpeg.internal.preview.PreviewStream
import dev.jordond.filmstrip.ffmpeg.internal.preview.PreviewStreamResult
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.player.internal.FfmpegPreviewPlanner
import dev.jordond.filmstrip.player.internal.PreviewPlan
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.time.Duration

/**
 * Lowers edits the way the real planner does, and opens a stream from [script] in place of spawning ffmpeg.
 */
internal class ScriptedPlanner(
  private val script: (at: Duration) -> ScriptedStream,
) : FfmpegPreviewPlanner(CONTRACT_COMPONENTS) {
  @Volatile
  var opened: List<ScriptedStream> = emptyList()
    private set

  override suspend fun open(
    plan: PreviewPlan,
    composition: Composition,
    at: Duration,
  ): PreviewStreamResult = PreviewStreamResult.Opened(script(at).also { opened = opened + it })
}

/**
 * A stream opened at [startPosition] that hands over [frames] blank frames and then ends with [status].
 *
 * Once closed it reads back as a process killed by a signal, which is what a caller that trusted the status alone
 * would report as a failure.
 *
 * @param readGate Holds the read after the last frame, closed or not, until it opens. The read then ends the stream.
 * @param exitGate Holds the status read of a stream that was not closed until it opens.
 * @param failure What the stream classifies a failed exit as, or null for one carrying the status and [stderr].
 */
internal class ScriptedStream(
  override val startPosition: Duration,
  private val frames: Long,
  private val status: Int,
  private val stderr: String = "",
  private val readGate: StreamGate? = null,
  private val exitGate: StreamGate? = null,
  private val failure: FilmstripError? = null,
) : PreviewStream {
  private var sent = 0L

  @Volatile
  var closed: Boolean = false
    private set

  override val size: Size = Size(1, 1)

  override suspend fun next(): ByteArray? {
    if (sent == frames) readGate?.pass()
    if (closed || sent == frames) return null
    sent++
    return ByteArray(BYTES_PER_PIXEL)
  }

  override suspend fun exitStatus(): Int {
    if (!closed) exitGate?.pass()
    return if (closed) KILLED else status
  }

  override fun errors(): String = stderr

  override fun exitError(status: Int): FilmstripError = failure ?: FilmstripError.Underlying(status, stderr)

  override fun close() {
    closed = true
  }

  private companion object {
    const val BYTES_PER_PIXEL = 4

    // What a JVM reads back for a child ended by SIGTERM.
    const val KILLED = 143
  }
}

/**
 * Holds a [ScriptedStream] at one point until a test opens it.
 *
 * The wait ignores cancellation, the way a read blocked on a pipe does, so a pump that was stopped while held here is
 * still running until the gate opens.
 */
internal class StreamGate {
  private val reached = CompletableDeferred<Unit>()
  private val opened = CompletableDeferred<Unit>()

  /**
   * Whether the stream is waiting here, or already went through.
   */
  val isReached: Boolean get() = reached.isCompleted

  /**
   * Whether the stream went through once the gate opened.
   */
  @Volatile
  var isPassed: Boolean = false
    private set

  /**
   * Lets the stream held here go on.
   */
  fun open() {
    opened.complete(Unit)
  }

  /**
   * Waits here until [open] is called.
   */
  suspend fun pass() {
    reached.complete(Unit)
    withContext(NonCancellable) { opened.await() }
    isPassed = true
  }
}

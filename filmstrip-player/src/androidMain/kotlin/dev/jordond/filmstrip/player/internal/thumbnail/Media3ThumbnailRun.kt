package dev.jordond.filmstrip.player.internal.thumbnail

import android.os.HandlerThread
import androidx.media3.inspector.frame.FrameExtractor
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.thumbnail.ThumbnailBatchCallback
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.thumbnail.ThumbnailResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile

/**
 * One outstanding run, so a cancellation and a completion settle each entry exactly once between
 * them, and the reader thread comes down either way.
 *
 * A frame that arrives for a run already cancelled is closed here rather than handed on, since
 * the caller that would have owned it has gone.
 */
internal class Media3ThumbnailRun(
  private val callback: ThumbnailBatchCallback,
) {
  @Volatile
  var isCancelled: Boolean = false
    private set

  @Volatile
  private var thread: HandlerThread? = null

  /**
   * Takes ownership of [running], or refuses because the run was already cancelled.
   *
   * @return whether the reader thread should go on to be used.
   */
  fun attach(running: HandlerThread): Boolean {
    thread = running
    if (!isCancelled) return true
    running.quitSafely()
    return false
  }

  /**
   * Whether [open] should go on to be used, closing it here when the run was cancelled while it
   * was being built.
   */
  suspend fun accepts(
    open: FrameExtractor,
    reader: CoroutineDispatcher,
  ): Boolean {
    if (!isCancelled) return true
    close(open, reader)
    return false
  }

  /**
   * Hands [open] back, which is what releases the player shared across the process once no run
   * is left holding one.
   *
   * A run cancelled mid-decode unwinds through here, so this outlasts the cancellation rather
   * than being skipped by it.
   */
  suspend fun close(
    open: FrameExtractor,
    reader: CoroutineDispatcher,
  ) {
    withContext(NonCancellable + reader) { open.close() }
  }

  fun deliver(
    index: Int,
    result: ThumbnailResult,
  ) {
    if (isCancelled) {
      (result as? ThumbnailResult.Success)?.image?.close()
      return
    }
    callback.onThumbnail(index, result)
  }

  fun failAll(
    group: List<ThumbnailRequest>,
    offset: Int,
    error: FilmstripError,
  ) {
    group.indices.forEach { deliver(offset + it, ThumbnailResult.Failure(error)) }
  }

  /**
   * Stops the run, leaving the reader thread up.
   *
   * The run is already unwinding towards [release] and closes on the way through, and every step
   * of that unwind is posted to the reader. Taking the thread down here would strand it.
   */
  fun cancel() {
    if (isCancelled) return
    isCancelled = true
  }

  fun release() {
    thread?.quitSafely()
    thread = null
  }
}

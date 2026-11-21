package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Asks the device about each clip as it is added, and drops the question when the clip goes first.
 *
 * @param onProbed Called once a clip has its answer, readable or not.
 */
internal class ClipProber(
  private val filmstrip: Filmstrip,
  private val scope: CoroutineScope,
  private val recorder: DiagnosticsRecorder,
  private val onProbed: () -> Unit,
) {
  private val jobs = mutableMapOf<ClipId, Job>()

  /**
   * Reads [source] and records what the device said on [clip]. A clip the device cannot read is marked and left
   * out of the edit, and the rest play on.
   */
  fun probe(
    clip: ClipEdit,
    source: MediaSource,
  ) {
    clip.probing = true
    jobs[clip.id] =
      scope.launch {
        try {
          val result = filmstrip.probe(source)
          ensureActive()
          clip.probed(result)
          when (result) {
            is ProbeResult.Success -> recorder.record("source.probed", "info" to result.info.toString())
            is ProbeResult.Failure -> recorder.record("source.unreadable", "error" to result.error.message)
          }
          onProbed()
        } finally {
          // A cancelled probe has already handed probing over to whoever cancelled it.
          if (isActive) clip.probing = false
        }
      }
  }

  /**
   * Stops asking about the clip named [id].
   */
  fun cancel(id: ClipId) {
    jobs.remove(id)?.cancel()
  }

  /**
   * Stops asking about every clip.
   */
  fun cancelAll() {
    jobs.values.forEach { it.cancel() }
    jobs.clear()
  }
}

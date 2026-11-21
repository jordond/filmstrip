package dev.jordond.filmstrip.sample

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jordond.filmstrip.media.MediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Downloads one built-in clip at a time and hands it to whoever asked.
 */
@Stable
internal class PresetLoader(
  private val scope: CoroutineScope,
  private val recorder: DiagnosticsRecorder,
  private val download: suspend (SamplePreset) -> MediaSource,
) {
  var loading: SamplePreset? by mutableStateOf(null)
    private set

  private var job: Job? = null

  /**
   * Downloads [preset] unless another download is running, then calls [onLoaded] with it or [onFailed] with why
   * it could not be had.
   */
  fun load(
    preset: SamplePreset,
    onFailed: (String) -> Unit,
    onLoaded: (MediaSource) -> Unit,
  ) {
    if (loading != null) return

    loading = preset
    job =
      scope.launch {
        try {
          recorder.record("preset.download", "clip" to preset.fileName)
          val source = download(preset)
          ensureActive()
          onLoaded(source)
        } catch (cancellation: CancellationException) {
          throw cancellation
        } catch (failure: Throwable) {
          ensureActive()
          val reason = failure.message ?: "The download gave no reason."
          onFailed(reason)
          recorder.record("preset.failed", "clip" to preset.fileName, "reason" to reason)
        } finally {
          // A cancelled download has already handed loading over to whoever cancelled it.
          if (isActive) loading = null
        }
      }
  }

  /**
   * Drops the download in flight, if there is one.
   */
  fun cancel() {
    job?.cancel()
    loading = null
  }
}

package dev.jordond.filmstrip.sample

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.still.FrameRenderer
import dev.jordond.filmstrip.still.StillFormat
import dev.jordond.filmstrip.still.StillResult
import dev.jordond.filmstrip.still.StillSpec
import dev.jordond.filmstrip.still.still
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * Saves the frame under the playhead as a picture, and holds how the last save went.
 *
 * The still is rendered from the edit the export runs, so it is the frame the export would write there. Where it
 * lands follows the target: a file on Android, Apple and the JVM, a download in a browser.
 */
@Stable
public class FrameGrab(
  private val renderer: FrameRenderer,
  private val scope: CoroutineScope,
  private val recorder: DiagnosticsRecorder,
) {
  public var format: StillFormat by mutableStateOf(StillFormat.Jpeg)

  public var saving: Boolean by mutableStateOf(false)
    private set

  public var saved: StillResult.Success? by mutableStateOf(null)
    private set

  public var failure: FilmstripError? by mutableStateOf(null)
    private set

  private var job: Job? = null
  private var generation = 0

  /**
   * Renders [composition] at [at] and writes it in [format]. A save still running is dropped for the new one.
   */
  public fun save(
    composition: Composition,
    at: Duration,
  ) {
    job?.cancel()
    saved = null
    failure = null
    // cancel() does not wait, so a dropped save's finally lands after the next one has started. Only the save that
    // still owns the flag clears it.
    val owner = ++generation
    saving = true
    job =
      scope.launch {
        try {
          when (val result = renderer.still(composition, at, MediaSink.temporary(), StillSpec(format = format))) {
            is StillResult.Success -> {
              saved = result
              recorder.record("frame.saved", "at" to at.toString(), "format" to result.format.name)
            }
            is StillResult.Failure -> {
              failure = result.error
              recorder.record("frame.failed", "at" to at.toString(), "message" to result.error.message)
            }
          }
        } finally {
          if (owner == generation) saving = false
        }
      }
  }

  /**
   * Forgets the last save, which a new source or a closed project does.
   */
  public fun clear() {
    job?.cancel()
    generation++
    saved = null
    failure = null
    saving = false
  }
}

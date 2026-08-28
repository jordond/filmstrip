package dev.jordond.filmstrip.sample

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import dev.jordond.filmstrip.diagnostics.DiagnosticListener
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * One thing that happened, and when.
 *
 * @property elapsed How long after the app started it happened.
 * @property label What happened, namespaced like `export.failed` or `ffmpeg.invocation`.
 * @property detail The fields worth carrying into a bug report.
 */
public class SessionEvent(
  public val elapsed: Duration,
  public val label: String,
  public val detail: Map<String, String>,
)

/**
 * Keeps the last [limit] things that happened in this session.
 *
 * A snapshot of the current state loses the run that went wrong the moment the edit is touched
 * again, because planning and exporting both clear what came before. The log outlives that, so the
 * failure is still in the report after the user has poked at the edit trying to reproduce it.
 *
 * @param limit How many events to keep. The oldest goes when a new one arrives at the limit.
 */
@Stable
public class DiagnosticsRecorder(
  private val limit: Int = 250,
) {
  private val started = TimeSource.Monotonic.markNow()
  private val entries = mutableStateListOf<SessionEvent>()

  public val events: List<SessionEvent> get() = entries

  /**
   * Appends an event.
   */
  public fun record(
    label: String,
    detail: Map<String, String> = emptyMap(),
  ) {
    if (entries.size >= limit) entries.removeAt(0)
    entries.add(SessionEvent(started.elapsedNow(), label, detail))
  }

  /**
   * Appends an event whose detail is built only when something is listening.
   */
  public fun record(
    label: String,
    vararg detail: Pair<String, String>,
  ) {
    record(label, detail.toMap())
  }

  /**
   * Drops everything recorded so far.
   */
  public fun clear() {
    entries.clear()
  }

  /**
   * The listener filmstrip's own components report through.
   */
  public fun asListener(): DiagnosticListener = DiagnosticListener { event ->
    record("${event.source}.${event.name}", event.detail)
  }
}

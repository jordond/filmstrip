package dev.jordond.filmstrip.diagnostics

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.InternalFilmstripApi

/**
 * What a backend says about itself when it registers.
 *
 * Registration is the only place the identity is known: an engine reaches a caller as an
 * [dev.jordond.filmstrip.export.ExportEngine] with no name on it, and the factory that built it is
 * usually a lambda.
 *
 * @property name The backend's short name, as the docs and the issue tracker spell it.
 * @property artifact The Maven coordinate that registers it.
 */
@Poko
public class BackendInfo(
  public val name: String,
  public val artifact: String,
)

/**
 * Something a component learned while running that no return value carries.
 *
 * The ffmpeg version banner and the command line filmstrip spawned are both here: neither belongs
 * in a [dev.jordond.filmstrip.export.Verdict] or an [dev.jordond.filmstrip.export.ExportStatus],
 * and both are the first thing a bug report is asked for.
 *
 * @property source Which component emitted it, matching a [BackendInfo.name] where a backend did.
 * @property name A stable key for what the event is, such as `toolchain` or `invocation`. Switch on
 *   this rather than on anything in [detail].
 * @property detail The event's payload, keyed by field.
 */
@Poko
public class DiagnosticEvent(
  public val source: String,
  public val name: String,
  public val detail: Map<String, String>,
)

/**
 * Receives every [DiagnosticEvent] the components emit.
 *
 * Register one with `FilmstripBuilder.addDiagnosticListener`. Events arrive on whichever thread the
 * work is running on, so an implementation that touches shared state does its own synchronising.
 */
public fun interface DiagnosticListener {
  /**
   * Called once per event.
   */
  public fun onEvent(event: DiagnosticEvent)
}

/**
 * Emits an event to every registered listener.
 *
 * A listener that throws is ignored: diagnostics are the least important thing in an export, and
 * failing one is not worth failing the render for.
 *
 * @param source Which component is emitting.
 * @param name The event's stable key.
 * @param detail The payload.
 */
@InternalFilmstripApi
public fun ComponentRegistry.report(
  source: String,
  name: String,
  detail: Map<String, String>,
) {
  if (diagnosticListeners.isEmpty()) return

  val event = DiagnosticEvent(source, name, detail)
  diagnosticListeners.forEach { listener ->
    runCatching { listener.onEvent(event) }
  }
}

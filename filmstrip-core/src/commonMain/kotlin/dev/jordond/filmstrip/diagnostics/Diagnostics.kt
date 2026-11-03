package dev.jordond.filmstrip.diagnostics

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.export.EngineComponents

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
public fun EngineComponents.report(
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

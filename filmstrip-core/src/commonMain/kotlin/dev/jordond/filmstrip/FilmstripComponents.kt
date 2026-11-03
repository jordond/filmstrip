package dev.jordond.filmstrip

import dev.jordond.filmstrip.export.EngineComponents
import dev.jordond.filmstrip.export.ExportEngineFactory

/**
 * The parts of a [Filmstrip]'s registry a player engine or thumbnail source is built against.
 *
 * Everything an export engine is built against, and the export engine factories themselves, so a preview can lower
 * through the same engine the instance exports with. Only filmstrip builds one, and [ComponentRegistry] is the one
 * every factory is handed.
 */
public abstract class FilmstripComponents internal constructor() : EngineComponents() {
  /**
   * Factories that build an export engine.
   *
   * Asked newest registration first.
   */
  @InternalFilmstripApi
  public abstract val exportEngineFactories: List<ExportEngineFactory>
}

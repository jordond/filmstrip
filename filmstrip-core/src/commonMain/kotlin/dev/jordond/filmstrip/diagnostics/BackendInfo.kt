package dev.jordond.filmstrip.diagnostics

import dev.drewhamilton.poko.Poko

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

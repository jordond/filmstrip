package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition

/**
 * What [ExportPlanner.negotiate] settled on: the verdict a caller sees, and the resolved
 * composition when it is capable.
 */
@InternalFilmstripApi
public class NegotiatedExport(
  public val verdict: Verdict,
  public val composition: ResolvedComposition?,
)

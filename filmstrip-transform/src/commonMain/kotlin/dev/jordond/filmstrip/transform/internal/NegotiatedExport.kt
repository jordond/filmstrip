package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effect.EffectId
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.transform.internal.copy.CopyPacketWindow
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition

/**
 * What [ExportPlanner.negotiate] settled on: the verdict a caller sees, and the resolved
 * composition when it is capable.
 *
 * @property fallbackCopyWindow The window the plan without this edit's unsupported effects copies, when the ask held
 * no packets for it. The verdict then carries no fallback yet, and [ExportPlanner.negotiateFallback] settles it once
 * the packets over this window are read.
 * @property unsupported The effects this device cannot run, each id paired with the error it is refused with, which
 * [ExportPlanner.negotiateFallback] leaves out.
 */
@InternalFilmstripApi
public class NegotiatedExport(
  public val verdict: Verdict,
  public val composition: ResolvedComposition?,
  public val fallbackCopyWindow: CopyPacketWindow? = null,
  internal val unsupported: Map<EffectId, FilmstripError> = emptyMap(),
)

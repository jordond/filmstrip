package dev.jordond.filmstrip.docs.guides

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict

internal fun hdrSpecs(): List<ExportSpec> {
  // region modes
  val keepIfPossible = ExportSpec()
  val mustKeep = ExportSpec(hdr = HdrMode.KeepHdr)
  val sdr = ExportSpec(hdr = HdrMode.ToneMapToSdr)
  // endregion
  return listOf(keepIfPossible, mustKeep, sdr)
}

internal suspend fun toneMapNotice(
  filmstrip: Filmstrip,
  composition: Composition,
): Boolean {
  // region adjustment
  val toneMapped =
    when (val verdict = filmstrip.plan(composition, ExportSpec())) {
      is Verdict.Degraded -> verdict.adjustments.any { it.kind == AdjustmentKind.HdrToneMapped }
      is Verdict.Capable, is Verdict.Incapable -> false
    }
  // endregion
  return toneMapped
}

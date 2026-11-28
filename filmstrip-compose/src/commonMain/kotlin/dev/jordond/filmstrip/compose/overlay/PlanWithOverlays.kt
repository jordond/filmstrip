package dev.jordond.filmstrip.compose.overlay

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.Verdict

/**
 * Plans [composition] with the overlays [overlays] bakes drawn over it.
 *
 * The composition is planned once to learn the output frame, the overlays are baked for that frame, and the
 * composition is planned again with them added after its own effects. The second verdict is the one returned, and
 * the first verdict comes back when the bake gives no overlays.
 *
 * A first plan that is [Verdict.Incapable] is baked for the frame of its [Verdict.Incapable.withoutUnsupported], so the
 * second verdict's fallback carries the overlays too. With no fallback nothing is baked and the first verdict comes
 * back as it is.
 *
 * Unlike [Filmstrip.plan] this draws and encodes pictures, so it is not cheap, and it throws whatever the baker
 * throws.
 *
 * ```
 * when (val verdict = filmstrip.planWithOverlays(composition, spec, overlays)) {
 *   is Verdict.Capable -> filmstrip.export(verdict.plan, sink)
 *   is Verdict.Degraded -> filmstrip.export(verdict.plan, sink)
 *   is Verdict.Incapable -> show(verdict.reasons)
 * }
 * ```
 *
 * @param composition The edit to resolve.
 * @param spec The output being asked for.
 * @param overlays What bakes the overlays, which is the stage's [ComposedOverlayState] in an app.
 * @return What this device will do with the edit and its overlays.
 */
public suspend fun Filmstrip.planWithOverlays(
  composition: Composition,
  spec: ExportSpec,
  overlays: OverlayBaker,
): Verdict {
  val first = plan(composition, spec)
  val plan =
    when (first) {
      is Verdict.Capable -> first.plan
      is Verdict.Degraded -> first.plan
      is Verdict.Incapable -> first.withoutUnsupported?.plan ?: return first
    }
  val baked = overlays.bake(plan.output.size)
  if (baked.isEmpty()) return first
  return plan(composition.withEffects(composition.effects + baked), spec)
}

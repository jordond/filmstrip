package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.transform.internal.negotiation.Ask
import dev.jordond.filmstrip.transform.internal.probe.PlanningReadResult
import dev.jordond.filmstrip.transform.internal.probe.PlanningReads
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition

/**
 * Drives an export from a platform's [ExportDriver], owning probing, caching and lowering so the
 * driver only has to answer what the platform can do.
 *
 * @param ladder The order this engine's driver tries video codecs in, most preferred first.
 * @param supportsCopy Whether this driver can copy a stream across without an encoder.
 * @param muxerAccepts Whether this driver's muxer will take a source's streams without re-encoding them.
 */
@InternalFilmstripApi
public class PlannedExportEngine(
  private val backend: ExportDriver,
  prober: MediaProber,
  resolvers: List<EffectResolver>,
  private val parity: Map<String, EffectParity>,
  ladder: List<VideoCodec>,
  supportsCopy: Boolean,
  muxerAccepts: (MediaInfo) -> Boolean,
) : LoweringExportEngine() {
  private val planner =
    ExportPlanner(
      resolvers = resolvers,
      renderCapabilities = backend::renderCapabilities,
      parityOf = parity::get,
      unclaimedMessage = backend::unclaimed,
      ladder = ladder,
      supportsCopy = supportsCopy,
      muxerAccepts = muxerAccepts,
      toneCurve = backend.toneCurve,
    )

  private val reads = PlanningReads(prober, backend::capabilities, backend::syncSampleAtOrBefore)
  override val lowering: EngineLowering<*> =
    EngineLowering(resolvers, ::lower) { resolved, _, to -> backend.export(resolved, to) }

  override suspend fun capabilities(): CapabilitiesResult = CapabilitiesResult.Success(reads.device())

  override fun parityOf(specId: String): EffectParity? = parity[specId]

  private suspend fun lower(
    composition: EditComposition,
    spec: ExportSpec,
    layoutSize: Size?,
  ): LoweredExport<ResolvedComposition> =
    when (val read = reads.read(composition)) {
      is PlanningReadResult.Failed -> {
        refusedLowering(read.error)
      }
      is PlanningReadResult.Read -> {
        val export = planner.negotiate(composition, Ask(spec, read.device, read.infos, read.openings, layoutSize))
        LoweredExport(export, export.composition)
      }
    }
}

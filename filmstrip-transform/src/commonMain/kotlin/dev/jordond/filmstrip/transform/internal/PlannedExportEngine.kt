package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.audio.AudioEffectResolver
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.export.CopyBlocker
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.transform.internal.copy.CopyAsk
import dev.jordond.filmstrip.transform.internal.copy.withCopyPackets
import dev.jordond.filmstrip.transform.internal.copy.withFallbackCopyPackets
import dev.jordond.filmstrip.transform.internal.negotiation.Ask
import dev.jordond.filmstrip.transform.internal.probe.PlanningReadResult
import dev.jordond.filmstrip.transform.internal.probe.PlanningReads
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr

/**
 * Drives an export from a platform's [ExportDriver], owning probing, caching and lowering so the
 * driver only has to answer what the platform can do.
 *
 * @param audioResolvers The resolvers that lower each audio effect spec to something this engine's driver can host.
 * @param ladder The order this engine's driver tries video codecs in, most preferred first.
 * @param supportsCopy Whether this driver can copy a stream across without an encoder.
 * @param muxerAccepts Whether this driver's muxer will take a source's streams without re-encoding them.
 * @param copyLimit The blocker a limit of this driver's own puts on copying a source, or null when it has none.
 */
@InternalFilmstripApi
public class PlannedExportEngine(
  private val backend: ExportDriver,
  prober: MediaProber,
  resolvers: List<EffectResolver>,
  audioResolvers: List<AudioEffectResolver>,
  private val parity: Map<String, EffectParity>,
  ladder: List<VideoCodec>,
  supportsCopy: Boolean,
  muxerAccepts: (MediaInfo) -> Boolean,
  copyLimit: (MediaInfo) -> CopyBlocker? = { null },
) : LoweringExportEngine() {
  private val planner =
    ExportPlanner(
      resolvers = resolvers,
      audioResolvers = audioResolvers,
      renderCapabilities = backend::renderCapabilities,
      parityOf = parity::get,
      unclaimedMessage = backend::unclaimed,
      ladder = ladder,
      supportsCopy = supportsCopy,
      muxerAccepts = muxerAccepts,
      copyLimit = copyLimit,
      toneMapLimit = backend::toneMapLimit,
      fillLimit = backend::fillLimit,
    )

  private val reads =
    PlanningReads(
      prober,
      { CapabilitiesResult.Success(backend.capabilities()) },
      backend::syncSampleAtOrBefore,
    )
  override val lowering: EngineLowering<*> =
    EngineLowering(resolvers, ::grade, ::lower) { resolved, _, to -> backend.export(resolved, to) }

  override suspend fun capabilities(): CapabilitiesResult = reads.device()

  override fun parityOf(specId: String): EffectParity? = parity[specId]

  private suspend fun lower(
    composition: Composition,
    spec: ExportSpec,
    layoutSize: Size?,
    grade: ResolvedHdr?,
  ): LoweredExport<ResolvedComposition> {
    val read =
      when (val result = reads.read(composition)) {
        is PlanningReadResult.Failed -> return refusedLowering(result.error)
        is PlanningReadResult.Read -> result
      }
    val asked = Ask(spec, read.device, read.infos, read.openings, layoutSize, grade)
    return when (val withPackets = askedWithPackets(composition, asked)) {
      is CopyAsk.Refused -> {
        refusedLowering(withPackets.error)
      }
      is CopyAsk.Asked -> {
        val negotiated = planner.negotiate(composition, withPackets.ask)
        val export = planner.withFallbackCopyPackets(composition, withPackets.ask, negotiated, backend::copyPackets)
        LoweredExport(export, export.composition)
      }
    }
  }

  private suspend fun grade(
    composition: Composition,
    spec: ExportSpec,
  ): ExportGrade {
    val read =
      when (val result = reads.read(composition)) {
        is PlanningReadResult.Failed -> return ExportGrade.Refused(result.error)
        is PlanningReadResult.Read -> result
      }
    return when (val withPackets = askedWithPackets(composition, Ask(spec, read.device, read.infos, read.openings))) {
      is CopyAsk.Refused -> ExportGrade.Refused(withPackets.error)
      is CopyAsk.Asked -> planner.exportGradeOf(composition, withPackets.ask)
    }
  }

  private suspend fun askedWithPackets(
    composition: Composition,
    ask: Ask,
  ): CopyAsk = ask.withCopyPackets(planner.copyPacketWindowOf(composition, ask), backend::copyPackets)
}

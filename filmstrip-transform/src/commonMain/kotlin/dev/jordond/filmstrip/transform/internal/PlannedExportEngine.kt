package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.export.ExportEngine
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/**
 * Drives an export from a platform's [ExportDriver], owning probing, caching and lowering so the
 * driver only has to answer what the platform can do.
 *
 * @param ladder The order this engine's driver tries video codecs in, most preferred first.
 * @param supportsPassthrough Whether this driver can copy a stream across without an encoder.
 * @param canCopy Whether this driver's muxer will take a source's streams without re-encoding them.
 */
@InternalFilmstripApi
public class PlannedExportEngine(
  private val backend: ExportDriver,
  private val prober: MediaProber,
  resolvers: List<EffectResolver>,
  private val parity: Map<String, EffectParity>,
  ladder: List<VideoCodec>,
  supportsPassthrough: Boolean,
  canCopy: (MediaInfo) -> Boolean,
) : ExportEngine {
  private val planner =
    ExportPlanner(
      resolvers = resolvers,
      renderCapabilities = backend::renderCapabilities,
      parityOf = parity::get,
      unclaimedMessage = backend::unclaimed,
      ladder = ladder,
      supportsPassthrough = supportsPassthrough,
      canCopy = canCopy,
    )

  private val probed = mutableMapOf<MediaSource, MediaInfo>()
  private var device: DeviceCapabilities? = null

  override suspend fun capabilities(): CapabilitiesResult = CapabilitiesResult.Success(deviceCapabilities())

  override suspend fun plan(
    composition: EditComposition,
    spec: ExportSpec,
  ): Verdict =
    when (val result = negotiate(composition, spec)) {
      is NegotiationResult.Failed -> Verdict.Incapable(listOf(result.error), null)
      is NegotiationResult.Done -> result.export.verdict
    }

  override fun export(
    plan: ExportPlan,
    to: MediaSink,
  ): Flow<ExportStatus> = flow { emitAll(statusesFor(plan, to)) }

  override fun parityOf(specId: String): EffectParity? = parity[specId]

  private suspend fun deviceCapabilities(): DeviceCapabilities = device ?: backend.capabilities().also { device = it }

  /**
   * Re-negotiates the plan and hands back whatever it will report.
   *
   * The plan carries the composition and the spec it was resolved from, so the graph is rebuilt
   * rather than carried across the call. Probes are cached, so this costs nothing.
   */
  private suspend fun statusesFor(
    plan: ExportPlan,
    to: MediaSink,
  ): Flow<ExportStatus> =
    when (val result = negotiate(plan.composition, plan.spec)) {
      is NegotiationResult.Failed -> {
        flowOf(ExportStatus.Failure(result.error))
      }
      is NegotiationResult.Done -> {
        val resolved = result.export.composition?.toResolvedComposition()
        if (resolved == null) {
          flowOf(ExportStatus.Failure(refusal(result.export)))
        } else {
          backend.export(resolved, to)
        }
      }
    }

  private fun refusal(export: NegotiatedExport): ExportError =
    (export.verdict as? Verdict.Incapable)?.reasons?.firstOrNull()
      ?: ExportError.InvalidComposition(NO_LONGER_RUNNABLE)

  private suspend fun negotiate(
    composition: EditComposition,
    spec: ExportSpec,
  ): NegotiationResult {
    val infos = mutableMapOf<MediaSource, MediaInfo>()
    for (clip in composition.tracks.flatMap { it.clips }) {
      val cached = probed[clip.source]
      if (cached != null) {
        infos[clip.source] = cached
        continue
      }
      when (val result = prober.probe(clip.source)) {
        is ProbeResult.Success -> {
          probed[clip.source] = result.info
          infos[clip.source] = result.info
        }
        is ProbeResult.Failure -> {
          return NegotiationResult.Failed(result.error)
        }
      }
    }

    return NegotiationResult.Done(planner.negotiate(composition, spec, deviceCapabilities(), infos))
  }

  private sealed interface NegotiationResult {
    class Done(
      val export: NegotiatedExport,
    ) : NegotiationResult

    class Failed(
      val error: ExportError,
    ) : NegotiationResult
  }

  private companion object {
    const val NO_LONGER_RUNNABLE = "The composition no longer plans to anything runnable."
  }
}

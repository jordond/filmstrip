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
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlin.time.Duration

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
  prober: MediaProber,
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

  private val probes = ProbeCache(prober)
  private var device: DeviceCapabilities? = null

  // Where a stream copy of each cut could open. An engine negotiates the same edit to plan it, to
  // resolve it for a preview and again to export it, and the answer cannot move between those.
  private val openings = mutableMapOf<Pair<MediaSource, Duration>, Duration?>()

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

  /**
   * Negotiates [composition] and lowers it into the graph [export] would run, writing nothing.
   *
   * A preview presents the same edit an export of it writes, so it lowers through this rather than
   * through a negotiation of its own. Sharing the call is what makes the two pipelines the same
   * one: the same probes, the same device answer, the same output format and the same effect chain.
   *
   * @param composition The edit to lower.
   * @param spec What the export would be asked for.
   * @param layoutSize The output frame text is laid out against, for a caller lowering a frame
   *   smaller than the one an export writes. Null lays text out against the frame [spec] settles
   *   on, which is what an export does.
   * @return The lowered composition and the verdict it came with, or why it cannot run here.
   */
  @InternalFilmstripApi
  public suspend fun resolve(
    composition: EditComposition,
    spec: ExportSpec,
    layoutSize: Size? = null,
  ): ResolveResult =
    when (val result = negotiate(composition, spec, layoutSize)) {
      is NegotiationResult.Failed -> ResolveResult.Refused(result.error)
      is NegotiationResult.Done -> result.export.toResolveResult()
    }

  private suspend fun deviceCapabilities(): DeviceCapabilities = device ?: backend.capabilities().also { device = it }

  private suspend fun openingFor(
    source: MediaSource,
    cut: Duration,
  ): Duration? {
    val key = source to cut
    if (key in openings) return openings[key]
    return backend.syncSampleAtOrBefore(source, cut).also { openings[key] = it }
  }

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
        when (val resolved = result.export.toResolveResult()) {
          is ResolveResult.Refused -> flowOf(ExportStatus.Failure(resolved.error))
          is ResolveResult.Resolved -> backend.export(resolved.composition, to)
        }
      }
    }

  private suspend fun negotiate(
    composition: EditComposition,
    spec: ExportSpec,
    layoutSize: Size? = null,
  ): NegotiationResult =
    when (val probed = probes.read(composition)) {
      is ProbeCacheResult.Failed -> {
        NegotiationResult.Failed(probed.error)
      }
      is ProbeCacheResult.Read -> {
        NegotiationResult.Done(
          planner.negotiate(
            composition = composition,
            spec = spec,
            device = deviceCapabilities(),
            infos = probed.infos,
            openings = copyOpenings(composition, ::openingFor),
            layoutSize = layoutSize,
          ),
        )
      }
    }

  private sealed interface NegotiationResult {
    class Done(
      val export: NegotiatedExport,
    ) : NegotiationResult

    class Failed(
      val error: ExportError,
    ) : NegotiationResult
  }
}

/**
 * What [PlannedExportEngine.resolve] settled on.
 */
@InternalFilmstripApi
public sealed interface ResolveResult {
  /**
   * The edit lowered.
   *
   * @property composition The graph an export of this edit would run.
   * @property verdict What a caller asking [ExportEngine.plan] for the same edit would be told,
   *   which carries the parity a preview reports.
   */
  @InternalFilmstripApi
  public class Resolved(
    public val composition: ResolvedComposition,
    public val verdict: Verdict,
  ) : ResolveResult

  /**
   * The edit cannot run here.
   *
   * @property error Why it cannot.
   */
  @InternalFilmstripApi
  public class Refused(
    public val error: ExportError,
  ) : ResolveResult
}

/**
 * Turns what a negotiation settled on into what a preview lowers from.
 *
 * A capable negotiation carries the graph an export of the same edit would run. One that is not
 * carries the first reason it was refused.
 */
@InternalFilmstripApi
public fun NegotiatedExport.toResolveResult(): ResolveResult =
  when (val resolved = composition?.toResolvedComposition()) {
    null -> ResolveResult.Refused(refusal())
    else -> ResolveResult.Resolved(resolved, verdict)
  }

/**
 * The first reason this negotiation refused the edit, or a stand-in when it named none.
 */
@InternalFilmstripApi
public fun NegotiatedExport.refusal(): ExportError =
  (verdict as? Verdict.Incapable)?.reasons?.firstOrNull()
    ?: ExportError.InvalidComposition(NO_LONGER_RUNNABLE)

private const val NO_LONGER_RUNNABLE = "The composition no longer plans to anything runnable."

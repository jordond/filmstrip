package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.export.ExportEngine
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An export engine a preview can lower an edit through without writing anything.
 */
@InternalFilmstripApi
public interface ExportResolver : ExportEngine {
  /**
   * Negotiates [composition] and lowers it into the graph [export] would run, writing nothing.
   *
   * A preview presents the same edit an export of it writes, so it lowers through this too. Sharing
   * the call is what makes the two pipelines the same one: the same probes, the same device answer,
   * the same output format and the same effect chain.
   *
   * @param composition The edit to lower.
   * @param spec What the export would be asked for.
   * @param layoutSize The output frame text is laid out against, for a caller lowering a frame
   *   smaller than the one an export writes. Null lays text out against the frame [spec] settles
   *   on, which is what an export does.
   * @param grade The grade to lower at, for a caller rendering the grade of an export it is not lowering, as
   *   [exportGrade] answers it. Null settles the grade from [spec], which is what an export does.
   * @return The lowered composition and the verdict it came with, or why it cannot run here.
   */
  public suspend fun resolve(
    composition: Composition,
    spec: ExportSpec,
    layoutSize: Size? = null,
    grade: ResolvedHdr? = null,
  ): ResolveResult

  /**
   * The grade an export of [composition] at [spec] resolves to on this device, or that export's refusal.
   *
   * @param composition The edit the export would write.
   * @param spec What the export would be asked for.
   * @return The settled grade, or why the export has none.
   */
  public suspend fun exportGrade(
    composition: Composition,
    spec: ExportSpec,
  ): ExportGrade
}

/**
 * The grade an export resolves to, which a preview, a thumbnail and a frame of the same edit all render at.
 */
@InternalFilmstripApi
public sealed interface ExportGrade {
  /**
   * The export has a grade.
   *
   * @property hdr What the export does with high dynamic range. An SDR edit reads [ResolvedHdr.Keep], since there is
   *   no grade to map.
   */
  @InternalFilmstripApi
  public data class Settled(
    public val hdr: ResolvedHdr,
  ) : ExportGrade

  /**
   * The export is refused before it reaches a grade, or at it.
   *
   * @property error Why it is refused, in the planner's words.
   */
  @InternalFilmstripApi
  public data class Refused(
    public val error: FilmstripError,
  ) : ExportGrade
}

/**
 * An engine that plans, resolves and exports through one [EngineLowering].
 *
 * A subclass builds the lowering out of its own lower and run steps and says what its device can do, and every plan,
 * resolve and export goes through that lowering.
 */
@InternalFilmstripApi
public abstract class LoweringExportEngine : ExportResolver {
  /**
   * The lowering every plan, resolve and export of this engine goes through.
   */
  protected abstract val lowering: EngineLowering<*>

  final override suspend fun plan(
    composition: Composition,
    spec: ExportSpec,
  ): Verdict = lowering.plan(composition, spec)

  final override fun export(
    plan: ExportPlan,
    to: MediaSink,
  ): Flow<ExportStatus> = lowering.export(plan, to)

  final override suspend fun resolve(
    composition: Composition,
    spec: ExportSpec,
    layoutSize: Size?,
    grade: ResolvedHdr?,
  ): ResolveResult = lowering.resolve(composition, spec, layoutSize, grade)

  final override suspend fun exportGrade(
    composition: Composition,
    spec: ExportSpec,
  ): ExportGrade = lowering.exportGrade(composition, spec)
}

/**
 * What one engine lowered an edit to: what the shared planner settled on, and the engine's own
 * graph when the edit can run.
 *
 * @property graph What the engine runs, or null when [export] refused the edit.
 */
@InternalFilmstripApi
public class LoweredExport<out G : Any>(
  public val export: NegotiatedExport,
  public val graph: G?,
) {
  /**
   * What a caller planning the same edit is told.
   */
  public val verdict: Verdict get() = export.verdict
}

/**
 * A lowering refused for [error] alone, which is how an engine turns an edit away for a reason the
 * shared planner cannot see.
 */
@InternalFilmstripApi
public fun refusedLowering(error: FilmstripError): LoweredExport<Nothing> =
  LoweredExport(NegotiatedExport(Verdict.Incapable(listOf(error), null), null), null)

/**
 * Plans, resolves and exports an edit through one lowering, so an engine only says how to lower an
 * edit and how to run the graph it lowered to.
 *
 * Every resolver is asked to [EffectResolver.prepare] for the edit before each lowering. An export
 * lowers the plan's own composition and spec again when it starts, so a picture an overlay names by
 * path or URI is read as it is on disk then. A lowering reads each such picture once, in that prepare
 * step, and its size, color, orientation, crop and samples all come from those bytes, and nothing it read
 * is carried into a later lowering.
 *
 * Resolvers hold what they prepared until they next prepare, so lowerings of this engine run one at a
 * time and no overlapping one can replace what another is drawing. A waiter is cancellable, and a
 * cancelled lowering lets the next one in. A lowering holds no lock while its graph runs, and neither
 * [EffectResolver.prepare] nor [lower] may start another lowering of this engine.
 *
 * @param grade Reads the grade an export of an edit at a spec resolves to, which runs no effect resolver.
 * @param lower Lowers an edit, with the frame text is laid out against when it is not the output's, and the grade to
 * take as settled when it is not the spec's own.
 * @param run Runs a graph [lower] produced into a sink, with the plan it settled on. The adjustments that plan made
 * are the ones the [ResolvedComposition] behind the graph carries.
 */
@InternalFilmstripApi
public class EngineLowering<G : Any>(
  private val resolvers: List<EffectResolver>,
  private val grade: suspend (Composition, ExportSpec) -> ExportGrade,
  private val lower: suspend (Composition, ExportSpec, Size?, ResolvedHdr?) -> LoweredExport<G>,
  private val run: (G, ExportPlan, MediaSink) -> Flow<ExportStatus>,
) {
  private val oneAtATime = Mutex()

  /**
   * Lowers [composition] the way [plan], [resolve] and [export] all do.
   */
  public suspend fun lowered(
    composition: Composition,
    spec: ExportSpec,
    layoutSize: Size? = null,
    grade: ResolvedHdr? = null,
  ): LoweredExport<G> =
    oneAtATime.withLock {
      resolvers.forEach { it.prepare(composition) }
      lower(composition, spec, layoutSize, grade)
    }

  /**
   * What [ExportResolver.exportGrade] answers.
   */
  public suspend fun exportGrade(
    composition: Composition,
    spec: ExportSpec,
  ): ExportGrade = oneAtATime.withLock { grade(composition, spec) }

  /**
   * What [ExportEngine.plan] answers.
   */
  public suspend fun plan(
    composition: Composition,
    spec: ExportSpec,
  ): Verdict = lowered(composition, spec).verdict

  /**
   * What [ExportResolver.resolve] answers.
   */
  public suspend fun resolve(
    composition: Composition,
    spec: ExportSpec,
    layoutSize: Size?,
    grade: ResolvedHdr?,
  ): ResolveResult = lowered(composition, spec, layoutSize, grade).export.toResolveResult()

  /**
   * What [ExportEngine.export] answers, failing with the first refusal when the plan no longer
   * lowers to anything this engine can run.
   */
  public fun export(
    plan: ExportPlan,
    to: MediaSink,
  ): Flow<ExportStatus> =
    flow {
      val lowered = lowered(plan.composition, plan.spec)
      val graph = lowered.graph
      val settled =
        when (val verdict = lowered.verdict) {
          is Verdict.Capable -> verdict.plan
          is Verdict.Degraded -> verdict.plan
          is Verdict.Incapable -> null
        }
      if (graph != null && settled != null) {
        emitAll(run(graph, settled, to))
      } else {
        emit(ExportStatus.Failure(lowered.export.refusal()))
      }
    }
}

/**
 * What [ExportResolver.resolve] settled on.
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
    public val error: FilmstripError,
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
  when (val resolved = composition) {
    null -> ResolveResult.Refused(refusal())
    else -> ResolveResult.Resolved(resolved, verdict)
  }

/**
 * The first reason this negotiation refused the edit, or a stand-in when it named none.
 */
@InternalFilmstripApi
public fun NegotiatedExport.refusal(): FilmstripError =
  (verdict as? Verdict.Incapable)?.reasons?.firstOrNull()
    ?: FilmstripError.InvalidComposition(NO_LONGER_RUNNABLE)

private const val NO_LONGER_RUNNABLE = "The composition no longer plans to anything runnable."

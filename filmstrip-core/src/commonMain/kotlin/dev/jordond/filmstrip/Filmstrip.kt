package dev.jordond.filmstrip

import androidx.compose.runtime.Stable
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.effect.EffectId
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.player.PreviewFactory
import dev.jordond.filmstrip.still.FrameRenderer
import kotlinx.coroutines.flow.Flow

/**
 * Probes, plans, exports, previews and renders frames of an edit.
 *
 * One instance per component graph: injectable, disposable, and created by the [Filmstrip] factory
 * function. Implemented by filmstrip, never by a consumer.
 *
 * [probe] asks what a file is, [capabilities] asks what the device can encode, [plan] asks what this device will do
 * with an edit, and [export], [preview] and [frame] do the work, all three over the same [Composition] value,
 * built with [dev.jordond.filmstrip.edit.compositionOf].
 *
 * The last two are what [FrameRenderer] and [PreviewFactory] declare, so a component that only
 * draws frames or only plays takes one of those and is handed this.
 *
 * Which of these work depends on what is registered. [export], [plan] and [capabilities] need this target's engine
 * artifact, which is `filmstrip-transform-media3`, `filmstrip-transform-avfoundation`, `filmstrip-transform-webcodecs`
 * or `filmstrip-transform-ffmpeg`. [preview] and effect-applied frames need `filmstrip-player`, and effects need
 * `filmstrip-effects`. Each registers itself when it is a dependency, and `dev.jordond.filmstrip:filmstrip` brings the
 * engine and the player together. A missing one is reported as [FilmstripError.BackendMissing] naming the artifact
 * rather than as a crash.
 */
@Stable
public interface Filmstrip :
  FrameRenderer,
  PreviewFactory {
  /**
   * Everything this instance was built with, for diagnostics.
   */
  public val components: ComponentRegistry

  /**
   * Reads a source's metadata without decoding it.
   *
   * Answered by core alone on Android and Apple, where the OS reads a container for free. On the
   * desktop and in a browser it needs a registered backend, and reports which artifact to add when
   * there is none.
   *
   * @param source The media to read.
   * @return What the source is, or why it could not be read.
   */
  public suspend fun probe(source: MediaSource): ProbeResult

  /**
   * Asks what this device's hardware encoders can do. Cached per process.
   *
   * @return What the device supports, or why it could not be asked.
   */
  public suspend fun capabilities(): CapabilitiesResult

  /**
   * Resolves every effect and validates the pipeline against this device, without decoding,
   * encoding or writing anything.
   *
   * Cheap, side-effect-free, and it never throws.
   *
   * @param composition The edit to resolve.
   * @param spec The output being asked for.
   * @return What this device will do with the edit.
   */
  public suspend fun plan(
    composition: Composition,
    spec: ExportSpec,
  ): Verdict

  /**
   * Runs a plan.
   *
   * A plan comes only from [Verdict.Capable] or [Verdict.Degraded], including the one
   * [Verdict.Incapable.withoutUnsupported] carries with the effects this device cannot run left out,
   * so a caller always has the list of what this device will change before a render starts. Cancel
   * by cancelling the collecting scope. Exports run one at a time across every instance in the
   * process, on every backend, so the flow may wait before emitting [ExportStatus.Started].
   *
   * @param plan The plan to run.
   * @param to Where to write the output.
   * @return A flow of statuses ending in [ExportStatus.Finished].
   */
  public fun export(
    plan: ExportPlan,
    to: MediaSink,
  ): Flow<ExportStatus>

  /**
   * Plans and exports in one call.
   *
   * Adjustments arrive as [ExportStatus.Adjusted] before any progress, and an incapable device
   * fails before any work starts, with every reason [plan] would have given on
   * [ExportStatus.Failure.reasons]. Use [plan] instead to ask the user before committing.
   *
   * @param composition The edit to export.
   * @param spec The output being asked for.
   * @param to Where to write the output.
   * @return A flow of statuses ending in [ExportStatus.Finished].
   */
  public fun export(
    composition: Composition,
    spec: ExportSpec,
    to: MediaSink,
  ): Flow<ExportStatus>

  /**
   * How faithfully an effect will be previewed, with no work at all.
   *
   * The answer depends on the export backend that is registered, because parity is a property of
   * the pipeline that renders an effect rather than of the effect. The same id can be
   * [EffectParity.Exact] on one backend and unrenderable on another, so this returns what the
   * registered engine says and nothing when there is no engine to ask.
   *
   * Parity is also readable from an [ExportPlan] once an edit has been planned, and from a player's
   * live preview info.
   *
   * @param specId The id of the effect spec to look up.
   * @return The effect's parity, or null when the registered engine does not know [specId], cannot
   *   render it, or no export backend is registered at all. Call [plan] for the reason, which is
   *   the only place that distinguishes the three.
   */
  public fun parityOf(specId: EffectId): EffectParity?
}

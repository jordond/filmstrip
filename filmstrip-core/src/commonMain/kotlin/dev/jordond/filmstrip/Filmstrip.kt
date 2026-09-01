package dev.jordond.filmstrip

import androidx.compose.runtime.Stable
import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.diagnostics.BackendInfo
import dev.jordond.filmstrip.diagnostics.DiagnosticListener
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.FilmstripDsl
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.export.ExportEngineFactory
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.internal.DefaultFilmstrip
import dev.jordond.filmstrip.media.FrameResult
import dev.jordond.filmstrip.media.MediaProberFactory
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.player.PlayerConfig
import dev.jordond.filmstrip.player.PlayerEngineFactory
import dev.jordond.filmstrip.player.VideoPlayer
import dev.jordond.filmstrip.thumbnail.ThumbnailSourceFactory
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

/**
 * The entry point.
 *
 * One instance per component graph: injectable, disposable, and created by the [Filmstrip] factory
 * function. Implemented by filmstrip, never by a consumer.
 *
 * Four questions and three actions. [probe] asks what a file is, [capabilities] asks what the
 * device can encode, [plan] asks what this device will do with an edit, and [export], [preview] and
 * [frame] do the work, all three over the same [EditComposition] value, built with [compositionOf].
 *
 * Which of these work depends on what is on the classpath. [export], [plan] and [capabilities] need
 * `filmstrip-transform`, or `filmstrip-transform-ffmpeg` on the desktop, [preview] and
 * effect-applied frames need `filmstrip-player`, and effects need `filmstrip-effects`. Each
 * registers itself through [FilmstripBuilder], and a missing one is reported as
 * [ExportError.BackendMissing] naming the artifact rather than as a crash.
 */
@Stable
public interface Filmstrip {
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
    composition: EditComposition,
    spec: ExportSpec,
  ): Verdict

  /**
   * Runs a plan.
   *
   * A plan comes only from [Verdict.Capable] or [Verdict.Degraded], so a caller always has the list
   * of what this device will change before a render starts. Cancel by cancelling the collecting
   * scope. Exports are serialized on an internal lock, so the flow may wait before emitting
   * [ExportStatus.Started].
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
   * fails before any work starts. Use [plan] instead to ask the user before committing.
   *
   * @param composition The edit to export.
   * @param spec The output being asked for.
   * @param to Where to write the output.
   * @return A flow of statuses ending in [ExportStatus.Finished].
   */
  public fun export(
    composition: EditComposition,
    spec: ExportSpec,
    to: MediaSink,
  ): Flow<ExportStatus>

  /**
   * Renders one frame of a composition, with its effects applied.
   *
   * Lands on the frame covering [at], rather than on the nearest sync sample the way [frames] may.
   *
   * @param composition The edit to render from.
   * @param at Where in the composition to render.
   * @param heightPx The height to render at, in pixels. Zero renders at the composition's own
   *   output height.
   * @return The frame, which the caller owns and must close, or why it could not be produced.
   */
  public suspend fun frame(
    composition: EditComposition,
    at: Duration,
    heightPx: Int = 0,
  ): FrameResult

  /**
   * Renders several frames, emitting each as it is ready.
   *
   * For a timeline strip, which reads as a run of frames rather than as a set of exact instants.
   * Each frame may therefore come from the nearest sync sample rather than the one covering its
   * entry in [at], where that is the faster read. [FrameResult.Success.presentationTime] says where
   * a frame actually landed. Use [frame] when a position has to be exact.
   *
   * @param composition The edit to render from.
   * @param at Where in the composition to render each frame.
   * @param heightPx The height to render at, in pixels.
   * @return A flow of frames, one per entry in [at]. The caller owns each and must close it.
   */
  public fun frames(
    composition: EditComposition,
    at: List<Duration>,
    heightPx: Int,
  ): Flow<FrameResult>

  /**
   * Opens a player over the same composition value that [export] takes.
   *
   * Returns immediately. The composition loads asynchronously and progress is observable on the
   * player's own state. When no preview backend is registered, the returned player reports
   * [dev.jordond.filmstrip.player.PlaybackError.BackendMissing] rather than throwing.
   *
   * @param composition The edit to play.
   * @param config How the player should behave.
   * @return A player, which the caller owns and must close.
   */
  public fun preview(
    composition: EditComposition,
    config: PlayerConfig = PlayerConfig(),
  ): VideoPlayer

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
  public fun parityOf(specId: String): EffectParity?

  /**
   * Where an artifact that bundles backends hangs a factory of its own.
   *
   * `dev.jordond.filmstrip:filmstrip` extends it with `Filmstrip.create()`. Empty otherwise.
   */
  public companion object
}

/**
 * The outcome of asking what this device can encode.
 *
 * A missing export backend is a [Failure] naming the artifact, not an empty encoder list.
 */
public sealed interface CapabilitiesResult {
  /**
   * The device was probed.
   *
   * @property capabilities What this device's encoders turned out to support.
   */
  @Poko
  public class Success(
    public val capabilities: DeviceCapabilities,
  ) : CapabilitiesResult

  /**
   * The device could not be probed.
   *
   * @property error Why the probe failed.
   */
  @Poko
  public class Failure(
    public val error: ExportError,
  ) : CapabilitiesResult
}

/**
 * Creates a [Filmstrip], registering whatever [block] asks for and nothing else.
 *
 * An instance built with an empty block can only [Filmstrip.probe], and only on Android and Apple.
 * Depend on `dev.jordond.filmstrip:filmstrip` and call `Filmstrip.create()` to get one with every
 * backend already registered.
 *
 * On Android the application context comes from App Startup. Where App Startup is disabled or does
 * not run, such as a bare JVM test, install one with the Android-only `Filmstrip(context)` overload.
 *
 * @param block Registers components on the builder.
 * @return The configured instance.
 */
public fun Filmstrip(block: FilmstripBuilder.() -> Unit = {}): Filmstrip = FilmstripBuilder().apply(block).build()

/**
 * Assembles a [Filmstrip].
 *
 * Also the Swift entry point: `FilmstripBuilder()`, then the explicit `add` calls, then [build].
 */
@FilmstripDsl
public class FilmstripBuilder {
  private var registry: ComponentRegistry.Builder = ComponentRegistry.Builder()

  /**
   * Registers components through a receiver lambda.
   *
   * @param block Registers components on the registry builder.
   * @return This builder.
   */
  public fun components(block: ComponentRegistry.Builder.() -> Unit): FilmstripBuilder = apply { registry.block() }

  /**
   * Registers one effect resolver.
   *
   * @param resolver The resolver to register.
   * @return This builder.
   */
  public fun addEffectResolver(resolver: EffectResolver): FilmstripBuilder = apply { registry.add(resolver) }

  /**
   * Registers one player engine factory.
   *
   * @param factory The factory to register.
   * @return This builder.
   */
  public fun addPlayerEngineFactory(factory: PlayerEngineFactory): FilmstripBuilder = apply { registry.add(factory) }

  /**
   * Registers one thumbnail source factory.
   *
   * @param factory The factory to register.
   * @return This builder.
   */
  public fun addThumbnailSourceFactory(factory: ThumbnailSourceFactory): FilmstripBuilder =
    apply { registry.add(factory) }

  /**
   * Registers one export engine factory.
   *
   * @param factory The factory to register.
   * @return This builder.
   */
  @InternalFilmstripApi
  public fun addExportEngineFactory(factory: ExportEngineFactory): FilmstripBuilder = apply { registry.add(factory) }

  /**
   * Registers one media prober factory.
   *
   * @param factory The factory to register.
   * @return This builder.
   */
  @InternalFilmstripApi
  public fun addMediaProberFactory(factory: MediaProberFactory): FilmstripBuilder = apply { registry.add(factory) }

  /**
   * Records what a backend calls itself, so a diagnostic report can name it.
   *
   * @param info The backend's identity.
   * @return This builder.
   */
  @InternalFilmstripApi
  public fun addBackendInfo(info: BackendInfo): FilmstripBuilder = apply { registry.add(info) }

  /**
   * Registers a listener for what the components learn while they run.
   *
   * @param listener The listener to register.
   * @return This builder.
   */
  public fun addDiagnosticListener(listener: DiagnosticListener): FilmstripBuilder = apply { registry.add(listener) }

  /**
   * Builds the configured [Filmstrip].
   */
  public fun build(): Filmstrip = DefaultFilmstrip(registry.build())
}

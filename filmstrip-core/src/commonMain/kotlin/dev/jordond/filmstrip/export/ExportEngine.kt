package dev.jordond.filmstrip.export

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.media.MediaSink
import kotlinx.coroutines.flow.Flow

/**
 * The seam between `filmstrip-core` and the module that owns encoding.
 *
 * Each engine artifact (`filmstrip-transform-media3`, `-avfoundation`, `-webcodecs`, `-ffmpeg`)
 * registers its own implementation through [ExportEngineFactory]. See
 * [dev.jordond.filmstrip.effect.EffectResolver] for the shape this and the effect-resolver SPI
 * share.
 */
@InternalFilmstripApi
public interface ExportEngine {
  /**
   * Asks what this device's encoders can do.
   *
   * Implementations cache the answer, so a caller may ask repeatedly. A backend that cannot be
   * asked at all reports why, rather than reporting a device with no encoders.
   *
   * @return What this device's encoders support, or why they could not be asked.
   */
  public suspend fun capabilities(): CapabilitiesResult

  /**
   * Resolves a composition against this device without decoding, encoding or writing anything.
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
   * @param plan The plan to run.
   * @param to Where to write the output.
   * @return A flow of statuses ending in [ExportStatus.Finished].
   */
  public fun export(
    plan: ExportPlan,
    to: MediaSink,
  ): Flow<ExportStatus>

  /**
   * Answers [Filmstrip.parityOf] for this engine.
   *
   * Parity is a property of the pipeline that renders an effect, so the engine owns the answer. It
   * backs the cheapest of the three ways to read parity, so it must stay a lookup: no I/O, no
   * suspending, and no probing of the device.
   *
   * @param specId The id of the effect spec to look up.
   * @return The effect's parity, or null when this engine does not know [specId] or cannot render
   *   it. Call [plan] for the reason.
   */
  public fun parityOf(specId: String): EffectParity?
}

/**
 * Builds an [ExportEngine], or declines.
 */
@InternalFilmstripApi
public fun interface ExportEngineFactory {
  /**
   * Builds an engine against [components].
   *
   * @param components The components registered on the owning [Filmstrip].
   * @return An engine, or null to defer to the next factory.
   */
  public fun create(components: ComponentRegistry): ExportEngine?
}

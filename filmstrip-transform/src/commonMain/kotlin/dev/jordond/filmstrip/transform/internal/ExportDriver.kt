package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSink
import kotlinx.coroutines.flow.Flow

/**
 * What only a platform encoder can answer: what it accepts, what its renderer can draw, and how to
 * drive both.
 *
 * An engine artifact implements this and hands it to [PlannedExportEngine], which owns probing,
 * caching and lowering.
 */
@InternalFilmstripApi
public interface ExportDriver {
  /**
   * Reads what this device's encoders can do.
   *
   * @return The device's encoder capabilities, cached after the first call.
   */
  public suspend fun capabilities(): DeviceCapabilities

  /**
   * Describes what this platform's effect pipeline can render, so a resolver advertises against
   * real capability rather than an assumed floor.
   *
   * @param outputSize The frame the composition resolved to.
   * @param hdr Whether the pipeline is keeping HDR rather than tone-mapping.
   */
  public fun renderCapabilities(
    outputSize: Size,
    hdr: Boolean,
  ): RenderCapabilities

  /**
   * Names the render backend an unclaimed effect would have needed a resolver for.
   */
  public fun unclaimed(specId: String): String

  /**
   * Runs a plan, reporting progress as it goes.
   *
   * @return A flow of status updates that ends with success or failure.
   */
  public fun export(
    resolved: ResolvedComposition,
    to: MediaSink,
  ): Flow<ExportStatus>
}

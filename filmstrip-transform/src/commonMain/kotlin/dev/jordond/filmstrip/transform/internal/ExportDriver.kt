package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration

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
   * Describes what this platform's effect pipeline can render, which is what a resolver advertises
   * against.
   *
   * @param outputSize The frame the composition resolved to.
   * @param hdr Whether the pipeline is keeping HDR rather than tone-mapping.
   */
  public fun renderCapabilities(
    outputSize: Size,
    hdr: Boolean,
  ): RenderCapabilities

  /**
   * Reads the sync sample at or before [cut] in [source], which is where a stream copy of a clip
   * trimmed to [cut] would have to open.
   *
   * Null when this platform can name none, and the clip's trim then blocks the copy. Asked only for
   * a clip whose [Clip.snapWithin] leaves the answer able to change the plan, so a driver with no
   * cheap way to read one may decline by keeping the default.
   */
  public suspend fun syncSampleAtOrBefore(
    source: MediaSource,
    cut: Duration,
  ): Duration? = null

  /**
   * Why this device cannot tone map an HDR source, or null when it can.
   *
   * Read once per plan with an HDR source, so an answer that costs something to work out is only paid for there. A
   * plan that needs the tone map is refused with this as its message.
   *
   * @param sdrSources The sources drawn beside that HDR source that carry no grade of their own.
   */
  public fun toneMapLimit(sdrSources: List<MediaSource>): String? = null

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

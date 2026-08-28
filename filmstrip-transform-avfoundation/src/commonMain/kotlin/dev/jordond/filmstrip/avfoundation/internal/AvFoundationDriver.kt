package dev.jordond.filmstrip.avfoundation.internal

import dev.jordond.filmstrip.PlatformContext
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.transform.internal.ExportDriver
import dev.jordond.filmstrip.transform.internal.ResolvedComposition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

/**
 * The Apple export driver, on an AVFoundation reader and writer.
 *
 * What the encoders can do is read in [appleEncoderCapabilities], what runs is built in
 * [toAvComposition], and driving the two pumps is [WriterRun].
 */
internal class AvFoundationDriver(
  @Suppress("unused") private val context: PlatformContext,
  private val prober: MediaProber,
) : ExportDriver {
  private var cached: DeviceCapabilities? = null

  override suspend fun capabilities(): DeviceCapabilities =
    cached ?: withContext(Dispatchers.Default) { appleEncoderCapabilities().also { cached = it } }

  override fun renderCapabilities(
    outputSize: Size,
    hdr: Boolean,
  ): RenderCapabilities = coreImageRenderCapabilities(outputSize, hdr)

  override fun unclaimed(specId: String): String =
    "No resolver claimed $specId on the Apple backend. Register the built-in catalogue with " +
      "builtInEffects(), or add a resolver that recognises RenderApi.CoreImage."

  override fun export(
    resolved: ResolvedComposition,
    to: MediaSink,
  ): Flow<ExportStatus> =
    when (val prepared = prepare(resolved, to)) {
      is Preparation.Failed -> {
        flowOf(ExportStatus.Failure(prepared.error))
      }
      is Preparation.Ready -> {
        WriterRun(
          prober = prober,
          resolved = resolved,
          composition = prepared.composition,
          destination = prepared.destination,
        ).run()
      }
    }

  /**
   * Settles everything that can fail before a reader or a writer exists, so the run itself has one
   * shape.
   */
  private fun prepare(
    resolved: ResolvedComposition,
    to: MediaSink,
  ): Preparation {
    val composition =
      try {
        resolved.toAvComposition()
      } catch (failure: AppleLoweringFailure) {
        return Preparation.Failed(ExportError.InvalidComposition(failure.reason))
      }

    return when (val destination = resolveDestination(to)) {
      is DestinationResult.Failed -> Preparation.Failed(destination.error)
      is DestinationResult.Ready -> Preparation.Ready(composition, destination.destination)
    }
  }

  private sealed interface Preparation {
    class Ready(
      val composition: AvComposition,
      val destination: AvDestination,
    ) : Preparation

    class Failed(
      val error: ExportError,
    ) : Preparation
  }
}

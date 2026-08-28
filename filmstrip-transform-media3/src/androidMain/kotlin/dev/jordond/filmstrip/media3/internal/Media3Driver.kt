package dev.jordond.filmstrip.media3.internal

import android.content.Context
import androidx.media3.transformer.Composition
import dev.jordond.filmstrip.InternalFilmstripApi
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
 * The Android export driver, on media3's Transformer.
 *
 * What the encoders can do is read in [encoderCapabilities], what runs is built in [toMedia3], and
 * driving media3 is [TransformerRun].
 */
@OptIn(InternalFilmstripApi::class)
internal class Media3Driver(
  private val context: PlatformContext,
  private val prober: MediaProber,
) : ExportDriver {
  private var cached: DeviceCapabilities? = null

  override suspend fun capabilities(): DeviceCapabilities =
    cached ?: withContext(Dispatchers.Default) { encoderCapabilities().also { cached = it } }

  override fun renderCapabilities(
    outputSize: Size,
    hdr: Boolean,
  ): RenderCapabilities = media3RenderCapabilities(outputSize, hdr)

  override fun unclaimed(specId: String): String =
    "No resolver claimed $specId on the Android backend. Register the built-in catalogue with " +
      "builtInEffects(), or add a resolver that recognises RenderApi.OpenGlEs."

  override fun export(
    resolved: ResolvedComposition,
    to: MediaSink,
  ): Flow<ExportStatus> =
    when (val prepared = prepare(resolved, to)) {
      is Preparation.Failed -> {
        flowOf(ExportStatus.Failure(prepared.error))
      }
      is Preparation.Ready -> {
        TransformerRun(
          context = prepared.context,
          prober = prober,
          resolved = resolved,
          composition = prepared.composition,
          destination = prepared.destination,
        ).run()
      }
    }

  /**
   * Settles everything that can fail before media3 is started, so the run itself has one shape.
   */
  private fun prepare(
    resolved: ResolvedComposition,
    to: MediaSink,
  ): Preparation {
    // Not a platform failure with a code of its own, and every other arm is about a source, a sink
    // or a codec. What went wrong is the graph filmstrip was built with.
    val android =
      context.context
        ?: return Preparation.Failed(ExportError.Underlying(NO_PLATFORM_CODE, PlatformContext.MISSING_CONTEXT))

    val composition =
      try {
        resolved.toMedia3()
      } catch (failure: Media3LoweringFailure) {
        return Preparation.Failed(ExportError.InvalidComposition(failure.reason))
      }

    return when (val destination = resolveDestination(android, to)) {
      is DestinationResult.Failed -> Preparation.Failed(destination.error)
      is DestinationResult.Ready -> Preparation.Ready(android, composition, destination.destination)
    }
  }

  private sealed interface Preparation {
    class Ready(
      val context: Context,
      val composition: Composition,
      val destination: Media3Destination,
    ) : Preparation

    class Failed(
      val error: ExportError,
    ) : Preparation
  }

  private companion object {
    const val NO_PLATFORM_CODE = 0
  }
}

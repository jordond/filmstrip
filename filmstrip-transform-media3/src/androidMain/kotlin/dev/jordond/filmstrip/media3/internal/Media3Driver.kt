package dev.jordond.filmstrip.media3.internal

import android.content.Context
import android.media.MediaExtractor
import androidx.media3.transformer.Composition
import dev.jordond.filmstrip.FilmstripContext
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media3.internal.bounds.Media3ClipReads
import dev.jordond.filmstrip.media3.internal.bounds.open
import dev.jordond.filmstrip.media3.internal.bounds.readClips
import dev.jordond.filmstrip.media3.internal.bounds.videoTrackIndex
import dev.jordond.filmstrip.media3.internal.failure.Media3LoweringFailure
import dev.jordond.filmstrip.transform.internal.ExportDriver
import dev.jordond.filmstrip.transform.internal.probe.OpeningRead
import dev.jordond.filmstrip.transform.internal.probe.ProcessProbe
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/**
 * The Android export driver, on media3's Transformer.
 *
 * What the encoders can do is read once per process in [encoderCapabilities], what runs is built in
 * [toMedia3], and driving media3 is [TransformerRun].
 */
internal class Media3Driver(
  private val prober: MediaProber,
) : ExportDriver {
  override suspend fun capabilities(): DeviceCapabilities = processEncoders.read()

  override fun renderCapabilities(
    outputSize: Size,
    hdr: Boolean,
  ): RenderCapabilities = media3RenderCapabilities(hdr)

  override fun unclaimed(specId: String): String =
    "No resolver claimed $specId on the Android backend. Register the built-in catalogue with " +
      "builtInEffects(), or add a resolver that recognises RenderApi.OpenGlEs."

  override suspend fun syncSampleAtOrBefore(
    source: MediaSource,
    cut: Duration,
  ): OpeningRead = OpeningRead.At(syncSampleOf(source, cut))

  private suspend fun syncSampleOf(
    source: MediaSource,
    cut: Duration,
  ): Duration? {
    // A still carries no container, and so no sync sample of its own to read. Checked ahead of the
    // dispatcher switch below, so asking about one costs nothing.
    if (source is MediaSource.Image) return null

    return withContext(Dispatchers.IO) {
      val extractor = MediaExtractor()
      try {
        if (!extractor.open(source)) return@withContext null
        val track = extractor.videoTrackIndex() ?: return@withContext null
        extractor.selectTrack(track)
        extractor.seekTo(cut.inWholeMicroseconds, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        val sampleTime = extractor.sampleTime
        if (sampleTime < 0) return@withContext null

        val opening = sampleTime.microseconds
        opening.takeIf { it <= cut }
      } catch (unreadable: IOException) {
        // Thrown when a source handed over as bytes could not be written to the cache.
        null
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (unopened: RuntimeException) {
        // setDataSource and seekTo both throw a bare RuntimeException for a container or a track
        // the extractor cannot make sense of.
        null
      } finally {
        extractor.release()
      }
    }
  }

  override fun export(
    resolved: ResolvedComposition,
    to: MediaSink,
  ): Flow<ExportStatus> =
    flow {
      val run =
        when (val prepared = prepare(resolved, resolved.readClips(), to)) {
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
      emitAll(run)
    }

  /**
   * Settles everything that can fail before media3 is started, so the run itself has one shape.
   */
  private fun prepare(
    resolved: ResolvedComposition,
    bounds: Media3ClipReads,
    to: MediaSink,
  ): Preparation {
    // Not a platform failure with a code of its own, and every other arm is about a source, a sink
    // or a codec. What went wrong is the graph filmstrip was built with.
    val android =
      FilmstripContext.get()
        ?: return Preparation.Failed(
          FilmstripError.Underlying(FilmstripError.Underlying.NO_PLATFORM_CODE, FilmstripContext.MISSING_CONTEXT),
        )

    val composition =
      try {
        resolved.toMedia3(bounds)
      } catch (failure: Media3LoweringFailure) {
        return Preparation.Failed(FilmstripError.InvalidComposition(failure.reason))
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
      val error: FilmstripError,
    ) : Preparation
  }
}

// encoderCapabilities walks the platform's codec list and reads the published limits off every encoder on it, and
// what those encoders report belongs to the device rather than to whichever driver asked.
private val processEncoders = ProcessProbe { encoderCapabilities() }

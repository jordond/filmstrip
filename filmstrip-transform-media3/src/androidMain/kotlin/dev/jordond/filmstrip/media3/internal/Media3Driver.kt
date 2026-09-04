package dev.jordond.filmstrip.media3.internal

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.transformer.Composition
import dev.jordond.filmstrip.FilmstripContext
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.AndroidScratch
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.ExportDriver
import dev.jordond.filmstrip.transform.internal.ResolvedComposition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/**
 * The Android export driver, on media3's Transformer.
 *
 * What the encoders can do is read in [encoderCapabilities], what runs is built in [toMedia3], and
 * driving media3 is [TransformerRun].
 */
@OptIn(InternalFilmstripApi::class)
internal class Media3Driver(
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

  override suspend fun syncSampleAtOrBefore(
    source: MediaSource,
    cut: Duration,
  ): Duration? {
    // A still carries no container, and so no sync sample of its own to read. Checked ahead of the
    // dispatcher switch below, so asking about one costs nothing.
    if (source is MediaSource.Image) return null

    return withContext(Dispatchers.IO) {
      val context = FilmstripContext.get() ?: return@withContext null

      val extractor = MediaExtractor()
      try {
        val uri = source.toExtractorUri(context) ?: return@withContext null
        extractor.setDataSource(context, uri, null)
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
      FilmstripContext.get()
        ?: return Preparation.Failed(
          ExportError.Underlying(ExportError.Underlying.NO_PLATFORM_CODE, FilmstripContext.MISSING_CONTEXT),
        )

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
}

/**
 * The uri [MediaExtractor.setDataSource] opens this source through, or null when it names no
 * container an extractor could read a sync sample out of.
 *
 * Bytes are written to the cache first, under the same name an export or a probe of the same
 * source would land on, so this never writes a second copy of them.
 */
private fun MediaSource.toExtractorUri(context: Context): Uri? =
  when (this) {
    is MediaSource.Path -> Uri.fromFile(File(path))
    is MediaSource.Uri -> Uri.parse(uri)
    is MediaSource.Bytes -> Uri.fromFile(AndroidScratch.fileFor(context, this))
    // A still carries no container, and so no sync sample of its own to read.
    is MediaSource.Image -> null
  }

/**
 * The index of this extractor's first video track, or null when it opened no container carrying
 * one.
 */
private fun MediaExtractor.videoTrackIndex(): Int? {
  for (index in 0 until trackCount) {
    val mime = getTrackFormat(index).getString(MediaFormat.KEY_MIME)
    if (mime?.startsWith("video/") == true) return index
  }
  return null
}

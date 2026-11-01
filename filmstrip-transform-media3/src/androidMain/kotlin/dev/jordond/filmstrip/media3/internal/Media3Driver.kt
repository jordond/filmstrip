package dev.jordond.filmstrip.media3.internal

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.transformer.Composition
import dev.jordond.filmstrip.FilmstripContext
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.AndroidScratch
import dev.jordond.filmstrip.internal.outputSlotsOf
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.ExportDriver
import dev.jordond.filmstrip.transform.internal.ResolvedComposition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.concurrent.Volatile
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
  override suspend fun capabilities(): DeviceCapabilities = ProcessEncoderCapabilities.read()

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
    flow {
      val run =
        when (val prepared = prepare(resolved, resolved.readFrameBounds(), to)) {
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
    bounds: Media3FrameBounds,
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
        resolved.toMedia3(bounds)
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
 * Reads the samples every clip this plan lays on the output frame grid decodes, off each source's own container.
 *
 * A clip is read once per window, however many passes of a looping track cut the same one. A clip whose container
 * cannot be read, or whose window holds no sample, is left out, and the lowering lays it from its own start.
 *
 * @return what every lowering of this plan lays its video items by.
 */
@InternalFilmstripApi
public suspend fun ResolvedComposition.readFrameBounds(): Media3FrameBounds {
  val rate = output.frameRate?.takeIf { it > 0 }
  if (rate == null || path == ExportPath.Transmux || audio == AudioSpec.AudioOnly) return Media3FrameBounds.None

  val windows =
    tracks
      .filter { it.content != TrackContent.Audio }
      .flatMap { it.clips }
      .filter { clip ->
        clip.source !is MediaSource.Image && clip.info.video != null && !outputSlotsOf(clip.span, rate).isEmpty()
      }.map { ClipWindow(it.source, it.start, it.end) }
      .distinct()

  if (windows.isEmpty()) return Media3FrameBounds.None
  return Media3FrameBounds(
    windows
      .mapNotNull { window -> frameBoundsOf(window.source, window.start, window.end)?.let { window to it } }
      .toMap(),
  )
}

/**
 * The samples [source] decodes between [start] and [end], or null when its container cannot be read or holds no video
 * sample in that window.
 *
 * The extractor walks samples in decode order, so the earliest and the latest in presentation order are found by
 * reading on past each end until a second sync sample beyond it, which is as far as a frame can be reordered.
 */
private suspend fun frameBoundsOf(
  source: MediaSource,
  start: Duration,
  end: Duration,
): FrameBounds? =
  withContext(Dispatchers.IO) {
    val context = FilmstripContext.get() ?: return@withContext null

    val extractor = MediaExtractor()
    try {
      val uri = source.toExtractorUri(context) ?: return@withContext null
      extractor.setDataSource(context, uri, null)
      val track = extractor.videoTrackIndex() ?: return@withContext null
      val sourceEnd = extractor.longestTrackUs()
      extractor.selectTrack(track)

      val cut = sourceEnd?.let { minOf(end.inWholeMicroseconds, it) } ?: end.inWholeMicroseconds
      val first = extractor.earliestSample(start.inWholeMicroseconds, cut) ?: return@withContext null
      val last = extractor.latestSampleBefore(cut) ?: return@withContext null
      FrameBounds(first = first.microseconds, last = last.microseconds, end = cut.microseconds)
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

/**
 * The earliest sample at or after [from] and before [until], in presentation order.
 */
private fun MediaExtractor.earliestSample(
  from: Long,
  until: Long,
): Long? {
  seekTo(from, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
  var earliest = Long.MAX_VALUE
  walkUntilSecondSyncFrom(from) { time ->
    if (time in from..<until) earliest = minOf(earliest, time)
  }
  return earliest.takeIf { it != Long.MAX_VALUE }
}

/**
 * The latest sample before [until], in presentation order.
 *
 * Opened on the sync sample before [until] rather than at it, since a cut that sits exactly on a sync sample closes on
 * a frame of the group before.
 */
private fun MediaExtractor.latestSampleBefore(until: Long): Long? {
  seekTo(until - 1, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
  var latest = Long.MIN_VALUE
  walkUntilSecondSyncFrom(until) { time ->
    if (time < until) latest = maxOf(latest, time)
  }
  return latest.takeIf { it != Long.MIN_VALUE }
}

/**
 * Hands [visit] every sample from where the extractor stands, up to the second sync sample at or after [time] or the
 * end of the track.
 */
private inline fun MediaExtractor.walkUntilSecondSyncFrom(
  time: Long,
  visit: (Long) -> Unit,
) {
  var syncs = 0
  while (sampleTime >= 0) {
    val sample = sampleTime
    if (sample >= time && (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0 && ++syncs == 2) return
    visit(sample)
    if (!advance()) return
  }
}

/**
 * How long this extractor's longest track runs, in microseconds, which is where media3 ends a clip that asks for more
 * than the source holds. Null when no track says.
 */
private fun MediaExtractor.longestTrackUs(): Long? =
  (0 until trackCount)
    .mapNotNull { index ->
      getTrackFormat(index).takeIf { it.containsKey(MediaFormat.KEY_DURATION) }?.getLong(MediaFormat.KEY_DURATION)
    }.maxOrNull()

/**
 * The one encoder capability answer this process reads.
 *
 * [encoderCapabilities] walks the platform's codec list and reads the published limits off every
 * encoder on it, and what those encoders report belongs to the device rather than to whichever
 * driver asked. A process builds a driver per export engine, and the preview builds one per player,
 * so the first caller pays for the walk and every later one reads the same answer. The lock stops
 * two racing first calls from both probing.
 */
private object ProcessEncoderCapabilities {
  private val lock = Mutex()

  @Volatile
  private var probed: DeviceCapabilities? = null

  suspend fun read(): DeviceCapabilities =
    probed ?: lock.withLock {
      probed ?: withContext(Dispatchers.Default) {
        // Assigned here rather than on the way out, so a probe that finished is kept even when the
        // caller that paid for it is cancelled.
        encoderCapabilities().also { probed = it }
      }
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

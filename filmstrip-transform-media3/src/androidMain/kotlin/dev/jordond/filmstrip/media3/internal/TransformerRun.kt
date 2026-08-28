package dev.jordond.filmstrip.media3.internal

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.Transformer
import dev.jordond.filmstrip.export.Adjustment
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.transform.internal.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.ResolvedHdr
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Runs a media3 [Transformer] and reports how far along it is.
 *
 * A [Transformer] never emits progress on its own, so this polls it.
 */
internal class TransformerRun(
  private val context: Context,
  private val prober: MediaProber,
  private val resolved: ResolvedComposition,
  private val composition: Composition,
  private val destination: Media3Destination,
) {
  /**
   * What media3 negotiated down to once it had the real encoders in hand, or null while it is still
   * running what was asked for.
   *
   * Written on the export's looper and read once it has finished.
   */
  @Volatile
  private var negotiated: TransformationRequest? = null

  fun run(): Flow<ExportStatus> =
    flow {
      val outcome = CompletableDeferred<Outcome>()
      val looper = LooperThread()
      var transformer: Transformer? = null

      try {
        when (val started = looper.run { start(outcome, looper.looper) }) {
          is Startup.Rejected -> {
            emit(ExportStatus.Failure(ExportError.InvalidComposition(started.reason)))
          }
          is Startup.Running -> {
            transformer = started.transformer
            emit(ExportStatus.Started)
            emit(report(awaitOutcome(outcome, started.transformer, looper)))
          }
        }
      } finally {
        withContext(NonCancellable) {
          val abandoned = !outcome.isCompleted
          transformer?.let { engine -> looper.run { if (abandoned) engine.cancel() } }
          if (abandoned) destination.discard()
          looper.close()
        }
      }
    }

  /**
   * Builds the transformer and hands it the composition.
   *
   * `Transformer.start` rejects what it cannot open by throwing rather than by reporting, and every
   * other failure in this backend reaches the caller as a status, so this one does too.
   */
  private fun start(
    outcome: CompletableDeferred<Outcome>,
    looper: Looper,
  ): Startup {
    val transformer =
      Transformer
        .Builder(context)
        .apply {
          // A copy names no MIME type at all, so media3 decides for itself whether it can
          // transmux rather than being asked to encode a codec it may have no encoder for.
          if (resolved.path != ExportPath.Transmux) {
            setVideoMimeType(resolved.output.videoCodec.toMimeType())
            resolved.output.audioCodec
              .toMimeType()
              ?.let(::setAudioMimeType)
          }
        }.setLooper(looper)
        .addListener(
          object : Transformer.Listener {
            override fun onCompleted(
              composition: Composition,
              result: ExportResult,
            ) {
              outcome.complete(Outcome.Completed)
            }

            override fun onError(
              composition: Composition,
              result: ExportResult,
              exception: ExportException,
            ) {
              outcome.complete(Outcome.Failed(exception))
            }

            override fun onFallbackApplied(
              composition: Composition,
              original: TransformationRequest,
              fallback: TransformationRequest,
            ) {
              negotiated = fallback
            }
          },
        ).build()

    return try {
      transformer.start(composition, destination.path)
      Startup.Running(transformer)
    } catch (rejected: IllegalArgumentException) {
      Startup.Rejected(rejected.message ?: REJECTED)
    } catch (rejected: IllegalStateException) {
      Startup.Rejected(rejected.message ?: REJECTED)
    }
  }

  /**
   * Waits for media3 to finish, emitting progress as it climbs.
   */
  private suspend fun FlowCollector<ExportStatus>.awaitOutcome(
    outcome: CompletableDeferred<Outcome>,
    transformer: Transformer,
    looper: LooperThread,
  ): Outcome {
    val holder = ProgressHolder()
    val startedAt = SystemClock.elapsedRealtime()
    var reported = 0f

    while (true) {
      val finished = withTimeoutOrNull(PROGRESS_INTERVAL) { outcome.await() }
      if (finished != null) return finished

      val available = looper.run { transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE }
      // Monotonic by contract, and media3 is free to report a lower number after a fallback.
      val fraction = if (available) max(reported, (holder.progress / PERCENT).coerceIn(0f, 1f)) else reported
      if (fraction > reported) {
        reported = fraction
        emit(progress(fraction, (SystemClock.elapsedRealtime() - startedAt).milliseconds))
      }
    }
  }

  private fun progress(
    fraction: Float,
    elapsed: Duration,
  ): ExportStatus.Progress =
    ExportStatus.Progress(
      fraction = fraction,
      position = resolved.duration * fraction.toDouble(),
      estimatedRemaining = ((elapsed / fraction.toDouble()) - elapsed).takeIf { it > Duration.ZERO },
    )

  private suspend fun report(outcome: Outcome): ExportStatus =
    when (outcome) {
      is Outcome.Failed -> {
        destination.discard()
        ExportStatus.Failure(outcome.exception.toExportError(resolved.output.videoCodec))
      }
      is Outcome.Completed -> {
        published()
      }
    }

  private suspend fun published(): ExportStatus =
    when (val published = destination.publish()) {
      is Media3Destination.PublishResult.Failure -> ExportStatus.Failure(published.error)
      is Media3Destination.PublishResult.Success -> probed(published.sink)
    }

  // media3 reports what it encoded, not what landed in the container, and Success carries a
  // MediaInfo. Reading the written file back is the only answer that is about the real file.
  private suspend fun probed(sink: MediaSink): ExportStatus =
    when (val probe = prober.probe(sink.asSource())) {
      is ProbeResult.Failure -> ExportStatus.Failure(ExportError.SinkUnwritable(sink.describe(), NOT_READABLE))
      is ProbeResult.Success -> ExportStatus.Success(sink, probe.info, resolved.adjustments + negotiatedAway())
    }

  /**
   * What media3 gave up on once it had the real encoders, as adjustments.
   *
   * The plan is resolved against what the codec list advertises, and media3 negotiates again
   * against what those codecs turn out to accept. Anything it changes there is a difference between
   * what the caller was promised and what was written, which is what an [Adjustment] is for.
   */
  private fun negotiatedAway(): List<Adjustment> {
    // Nothing was requested to fall back from on a copy, and the codecs below are the source's
    // own rather than anything media3 was asked to encode, so there is nothing to compare.
    if (resolved.path == ExportPath.Transmux) return emptyList()
    val applied = negotiated ?: return emptyList()

    return buildList {
      val video = applied.videoMimeType?.takeIf { it != resolved.output.videoCodec.toMimeType() }
      if (video != null) {
        add(
          Adjustment(
            kind = AdjustmentKind.CodecFallback,
            requested = resolved.output.videoCodec.name,
            resolved = videoCodecName(video),
            message =
              "This device's encoder refused ${resolved.output.videoCodec}, so media3 encoded " +
                "${videoCodecName(video)} instead.",
          ),
        )
      }

      val audio = applied.audioMimeType?.takeIf { it != resolved.output.audioCodec.toMimeType() }
      if (audio != null) {
        add(
          Adjustment(
            kind = AdjustmentKind.CodecFallback,
            requested = resolved.output.audioCodec.name,
            resolved = audioCodecName(audio),
            message =
              "This device's encoder refused ${resolved.output.audioCodec}, so media3 encoded " +
                "${audioCodecName(audio)} instead.",
          ),
        )
      }

      val height = resolved.output.size.height
      if (applied.outputHeight != C.LENGTH_UNSET && applied.outputHeight != height) {
        add(
          Adjustment(
            kind = AdjustmentKind.ResolutionClamped,
            requested = "${resolved.output.size.width}x$height",
            resolved = "${applied.outputHeight}p",
            message =
              "The encoder did not accept a ${height}px frame, so media3 wrote " +
                "${applied.outputHeight}px instead.",
          ),
        )
      }

      // KEEP_HDR to tone-mapped is the only direction media3 falls back in, so reaching here at all
      // means the grade did not survive.
      if (resolved.hdr == ResolvedHdr.Keep && applied.hdrMode != Composition.HDR_MODE_KEEP_HDR) {
        add(
          Adjustment(
            kind = AdjustmentKind.HdrToneMapped,
            requested = HdrMode.KeepHdr.name,
            resolved = HdrMode.ToneMapToSdr.name,
            message = "The encoder this device chose cannot write HDR, so media3 tone-mapped to SDR.",
          ),
        )
      }
    }
  }

  private fun MediaSink.asSource(): MediaSource =
    when (this) {
      is MediaSink.Path -> MediaSource.Path(path)
      is MediaSink.Uri -> MediaSource.Uri(uri)
      is MediaSink.Temporary -> MediaSource.Path(destination.path)
    }

  private fun MediaSink.describe(): String =
    when (this) {
      is MediaSink.Path -> path
      is MediaSink.Uri -> uri
      is MediaSink.Temporary -> destination.path
    }

  private sealed interface Outcome {
    data object Completed : Outcome

    class Failed(
      val exception: ExportException,
    ) : Outcome
  }

  private sealed interface Startup {
    class Running(
      val transformer: Transformer,
    ) : Startup

    class Rejected(
      val reason: String,
    ) : Startup
  }

  private companion object {
    val PROGRESS_INTERVAL = 500L.milliseconds
    const val PERCENT = 100f

    const val NOT_READABLE =
      "media3 reported success but the output could not be read back, so nothing usable was written."

    const val REJECTED = "media3 refused to start this export and gave no reason."
  }
}

/**
 * The thread media3 is driven from.
 *
 * `Transformer` pins itself to one `Looper` and every call has to come from it, so filmstrip brings
 * its own rather than taking over the app's main thread for the length of an export.
 */
private class LooperThread : AutoCloseable {
  private val thread = HandlerThread("filmstrip-transformer").apply { start() }

  val looper: Looper = thread.looper

  private val handler = Handler(looper)

  suspend fun <T> run(block: () -> T): T =
    suspendCancellableCoroutine { continuation ->
      handler.post { continuation.resumeWith(runCatching(block)) }
    }

  override fun close() {
    thread.quitSafely()
  }
}

package dev.jordond.filmstrip.avfoundation.internal

import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.transform.internal.ResolvedComposition
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderAudioMixOutput
import platform.AVFoundation.AVAssetReaderOutput
import platform.AVFoundation.AVAssetReaderStatusCancelled
import platform.AVFoundation.AVAssetReaderStatusFailed
import platform.AVFoundation.AVAssetReaderTrackOutput
import platform.AVFoundation.AVAssetReaderVideoCompositionOutput
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVAssetWriter
import platform.AVFoundation.AVAssetWriterInput
import platform.AVFoundation.AVAssetWriterStatusCompleted
import platform.AVFoundation.AVAssetWriterStatusWriting
import platform.AVFoundation.AVFileTypeMPEG4
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.tracksWithMediaType
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMSampleBufferGetPresentationTimeStamp
import platform.CoreMedia.CMSampleBufferRef
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSNumber
import platform.darwin.dispatch_queue_create
import kotlin.concurrent.Volatile
import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Drives one export with an `AVAssetReader` pulling composited frames and mixed samples, and an
 * `AVAssetWriter` encoding them.
 *
 * A reader and a writer on every path, transmux included, so a bitrate, an output size and a frame
 * rate are settable everywhere [dev.jordond.filmstrip.export.OutputFormat] promises them, and
 * progress stays linear in the work done.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class WriterRun(
  private val prober: MediaProber,
  private val resolved: ResolvedComposition,
  private val composition: AvComposition,
  private val destination: AvDestination,
) {
  /**
   * How far the video pump has written, in microseconds of composition time.
   *
   * Written on the pump queue and read from the collecting coroutine, which is what the volatile is
   * for. A presentation timestamp is the only progress an `AVAssetWriter` offers, since it reports
   * no completion fraction of its own.
   */
  @Volatile
  private var writtenMicros = 0L

  private val queue = dispatch_queue_create(QUEUE_LABEL, attr = null)

  fun run(): Flow<ExportStatus> =
    flow {
      // Hardware codec sessions are a small shared pool on this platform, and a second export
      // starting inside a first is how a device reports one it cannot open. Started is emitted
      // after the lock, which is what lets a caller tell queued from running.
      exportLock.withLock { collect() }
    }

  private suspend fun FlowCollector<ExportStatus>.collect() {
    val outcome = CompletableDeferred<Outcome>()
    var session: Session? = null

    try {
      when (val started = start(outcome)) {
        is Startup.Rejected -> {
          emit(ExportStatus.Failure(started.error))
        }
        is Startup.Running -> {
          session = started.session
          emit(ExportStatus.Started)
          emit(report(awaitOutcome(outcome)))
        }
      }
    } finally {
      withContext(NonCancellable) {
        if (!outcome.isCompleted) {
          session?.writer?.cancelWriting()
          session?.reader?.cancelReading()
          destination.discard()
        }
      }
    }
  }

  /**
   * Opens the reader and the writer and starts both pumps.
   *
   * Everything that can be refused is refused here, as a status, never an exception, so the run
   * itself only ever ends one way.
   */
  private fun start(outcome: CompletableDeferred<Outcome>): Startup {
    shortOfSpace()?.let { return Startup.Rejected(it) }

    // The writer refuses to open onto a file that already exists, and the run owns everything at
    // this path from here on.
    destination.discard()

    val asset = composition.composition
    // The factories, not the constructors. An Objective-C `init` that answers nil reaches Kotlin as
    // a non-null reference that is not there, and both of these answer nil for reasons a caller has
    // to be told about.
    val reader =
      memScoped {
        val failure = alloc<ObjCObjectVar<NSError?>>()
        AVAssetReader.assetReaderWithAsset(asset, error = failure.ptr)
          ?: return Startup.Rejected(
            failure.value?.toExportError(FailingSide.Reader) ?: ExportError.InvalidComposition(NO_READER),
          )
      }
    val writer =
      memScoped {
        val failure = alloc<ObjCObjectVar<NSError?>>()
        AVAssetWriter.assetWriterWithURL(destination.url, fileType = AVFileTypeMPEG4, error = failure.ptr)
          ?: return Startup.Rejected(
            failure.value?.toExportError(FailingSide.Writer)
              ?: ExportError.SinkUnwritable(destination.path, NO_WRITER),
          )
      }

    val videoTracks = asset.tracksWithMediaType(AVMediaTypeVideo)
    val audioTracks = asset.tracksWithMediaType(AVMediaTypeAudio)
    // The hints are only alive for as long as it takes to open the inputs, which retain whichever
    // one they were handed.
    val videoHint = videoTracks.copyFormatHint()
    val audioHint = audioTracks.copyFormatHint()

    // A passthrough input carries no output settings, so the only thing describing what it is
    // writing is the source's format. Without one there is nothing to pass through and the encode
    // is the honest answer.
    val transmux =
      resolved.path == ExportPath.Transmux &&
        videoHint != null &&
        (audioTracks.isEmpty() || audioHint != null)

    if (refusesFallbackEncode(resolved.path, transmux, resolved.hdrTransfer)) {
      return Startup.Rejected(ExportError.InvalidComposition(NO_HDR_PASSTHROUGH))
    }

    val pumps = mutableListOf<Pump>()
    // An input takes output settings or a format hint, never both. AVFoundation refuses one
    // carrying a full settings dictionary alongside a hint describing a different format, so the
    // encode names its settings and the passthrough names the source.
    try {
      if (videoTracks.isNotEmpty() && composition.videoComposition != null) {
        val output = videoOutput(videoTracks, transmux)
        val input =
          AVAssetWriterInput(
            mediaType = AVMediaTypeVideo,
            outputSettings =
              if (transmux) {
                null
              } else {
                videoWriterSettings(
                  output = resolved.output,
                  encodesHdr = composition.encodesHdr,
                  transfer = composition.transfer,
                  sourceFormat = videoHint,
                )
              },
            sourceFormatHint = videoHint.takeIf { transmux },
          )
        pumps += Pump(input, output, reportsProgress = true)
      }

      if (audioTracks.isNotEmpty()) {
        val settings = if (transmux) null else audioWriterSettings(resolved.output)
        if (settings != null || transmux) {
          val output = audioOutput(audioTracks, transmux)
          val input =
            AVAssetWriterInput(
              mediaType = AVMediaTypeAudio,
              outputSettings = settings,
              sourceFormatHint = audioHint.takeIf { transmux },
            )
          pumps += Pump(input, output, reportsProgress = false)
        }
      }
    } finally {
      videoHint?.let { CFRelease(it) }
      audioHint?.let { CFRelease(it) }
    }

    if (pumps.isEmpty()) return Startup.Rejected(ExportError.InvalidComposition(NOTHING_TO_WRITE))

    pumps.forEach { pump ->
      // Never assert-then-add. A release build would drop the assertion and write a file with a
      // track missing, which reads as a successful export of the wrong thing.
      if (!reader.canAddOutput(pump.output)) {
        return Startup.Rejected(ExportError.InvalidComposition(READER_REFUSED))
      }
      if (!writer.canAddInput(pump.input)) {
        return Startup.Rejected(ExportError.NoEncoder(resolved.output.videoCodec, WRITER_REFUSED))
      }
      reader.addOutput(pump.output)
      pump.input.expectsMediaDataInRealTime = false
      writer.addInput(pump.input)
    }

    if (!writer.startWriting()) {
      return Startup.Rejected(writer.error.toExportError(FailingSide.Writer))
    }
    writer.startSessionAtSourceTime(Duration.ZERO.toCMTime())

    if (!reader.startReading()) {
      writer.cancelWriting()
      return Startup.Rejected(reader.error.toExportError(FailingSide.Reader))
    }

    val session = Session(reader, writer, pumps, outcome)
    pumps.forEach { session.drive(it) }
    return Startup.Running(session)
  }

  /**
   * Waits for both pumps to drain, emitting progress as the video one climbs.
   */
  private suspend fun FlowCollector<ExportStatus>.awaitOutcome(outcome: CompletableDeferred<Outcome>): Outcome {
    val startedAt = TimeSource.Monotonic.markNow()
    var reported = 0f

    while (true) {
      val finished = withTimeoutOrNull(PROGRESS_INTERVAL) { outcome.await() }
      if (finished != null) return finished

      val position = writtenMicros.microseconds
      val fraction =
        if (resolved.duration > Duration.ZERO) {
          // Monotonic by contract, and a pump can report a lower timestamp than the last one it
          // wrote when the audio and video streams interleave out of order.
          max(reported, (position / resolved.duration).toFloat().coerceIn(0f, 1f))
        } else {
          reported
        }

      if (fraction > reported) {
        reported = fraction
        emit(progress(fraction, position, startedAt.elapsedNow()))
      }
    }
  }

  private fun progress(
    fraction: Float,
    position: Duration,
    elapsed: Duration,
  ): ExportStatus.Progress =
    ExportStatus.Progress(
      fraction = fraction,
      position = position,
      estimatedRemaining = ((elapsed / fraction.toDouble()) - elapsed).takeIf { it > Duration.ZERO },
    )

  private suspend fun report(outcome: Outcome): ExportStatus =
    when (outcome) {
      is Outcome.Failed -> {
        destination.discard()
        ExportStatus.Failure(outcome.error)
      }
      is Outcome.Completed -> {
        probed()
      }
    }

  // AVAssetWriter reports what it was handed, not what landed in the container, and Success carries
  // a MediaInfo. Reading the written file back is the only answer that is about the real file.
  private suspend fun probed(): ExportStatus =
    when (val probe = prober.probe(destination.asSource())) {
      is ProbeResult.Failure -> ExportStatus.Failure(ExportError.SinkUnwritable(destination.path, NOT_READABLE))
      is ProbeResult.Success -> ExportStatus.Success(destination.sink, probe.info, resolved.adjustments)
    }

  private fun videoOutput(
    tracks: List<*>,
    transmux: Boolean,
  ): AVAssetReaderOutput =
    if (transmux) {
      AVAssetReaderTrackOutput(track = tracks.first() as AVAssetTrack, outputSettings = null)
    } else {
      AVAssetReaderVideoCompositionOutput(
        videoTracks = tracks,
        videoSettings = videoReaderSettings(composition.encodesHdr),
      ).apply { videoComposition = composition.videoComposition }
    }

  private fun audioOutput(
    tracks: List<*>,
    transmux: Boolean,
  ): AVAssetReaderOutput =
    if (transmux) {
      AVAssetReaderTrackOutput(track = tracks.first() as AVAssetTrack, outputSettings = null)
    } else {
      AVAssetReaderAudioMixOutput(
        audioTracks = tracks,
        audioSettings = pcmReaderSettings(resolved.output),
      ).apply { audioMix = composition.audioMix }
    }

  /**
   * Refuses before opening anything when the plan's own size estimate does not fit.
   *
   * Only reachable when the plan named a bitrate, since without one there is no number to check
   * against. The headroom matches the one the planner puts on [ExportEstimate.outputSizeBytesMax],
   * so a caller who checked that number sees the same answer here.
   */
  private fun shortOfSpace(): ExportError? {
    val bitrate = resolved.output.bitrate?.bitsPerSecond ?: return null
    val required =
      (bitrate * resolved.duration.inWholeMilliseconds / MILLIS_PER_SECOND) *
        HEADROOM_NUMERATOR / HEADROOM_DENOMINATOR / BITS_PER_BYTE
    val free =
      (
        NSFileManager.defaultManager
          .attributesOfFileSystemForPath(destination.path.parentDirectory(), error = null)
          ?.get(NSFileSystemFreeSize) as? NSNumber
      )?.longLongValue ?: return null

    return if (free < required) {
      ExportError.InsufficientStorage(required, "This export needs about $required bytes and $free are free.")
    } else {
      null
    }
  }

  private fun NSError?.toExportError(side: FailingSide): ExportError =
    this?.toExportError(resolved.output.videoCodec, side, destination.path)
      ?: ExportError.Underlying(
        ExportError.Underlying.NO_PLATFORM_CODE,
        "AVFoundation refused to start and reported no error.",
      )

  /**
   * One writer input and the reader output that feeds it.
   */
  private class Pump(
    val input: AVAssetWriterInput,
    val output: AVAssetReaderOutput,
    val reportsProgress: Boolean,
  )

  /**
   * The two pumps and the barrier that decides when the file is finished.
   *
   * Both pumps run on one serial queue, so everything mutable in here is confined to it and needs
   * no atomics. The completion handler is the only thing that crosses back to the coroutine.
   */
  private inner class Session(
    val reader: AVAssetReader,
    val writer: AVAssetWriter,
    private val pumps: List<Pump>,
    private val outcome: CompletableDeferred<Outcome>,
  ) {
    private var drained = 0

    fun drive(pump: Pump) {
      pump.input.requestMediaDataWhenReadyOnQueue(queue) {
        while (pump.input.isReadyForMoreMediaData()) {
          if (autoreleasepool { !step(pump) }) {
            finish(pump)
            return@requestMediaDataWhenReadyOnQueue
          }
        }
      }
    }

    /**
     * Moves one sample across, and says whether there is more to move.
     */
    private fun step(pump: Pump): Boolean {
      val buffer: CMSampleBufferRef = pump.output.copyNextSampleBuffer() ?: return false
      val appended = pump.input.appendSampleBuffer(buffer)
      if (appended && pump.reportsProgress) {
        writtenMicros = CMSampleBufferGetPresentationTimeStamp(buffer).toDuration().inWholeMicroseconds
      }
      // copyNextSampleBuffer follows the create rule, so every buffer is released here whatever the
      // append did with it.
      CFRelease(buffer)
      return appended
    }

    private fun finish(pump: Pump) {
      // Cancellation reaches the writer through the collector's scope, not the pumps, so
      // by the time one drains the writer may already have been stopped. Both of these raise on a
      // writer that is no longer writing, and an Objective-C exception here terminates the process
      // instead of failing the export.
      if (writer.status != AVAssetWriterStatusWriting) {
        drained++
        return
      }

      pump.input.markAsFinished()
      drained++
      if (drained < pumps.size) return

      val readerFailure = reader.error.takeIf { reader.status == AVAssetReaderStatusFailed }
      if (readerFailure != null) {
        writer.cancelWriting()
        outcome.complete(Outcome.Failed(readerFailure.toExportError(FailingSide.Reader)))
        return
      }
      if (reader.status == AVAssetReaderStatusCancelled) {
        writer.cancelWriting()
        outcome.complete(Outcome.Failed(ExportError.InvalidComposition(READ_CANCELLED)))
        return
      }

      // A step that threw is reported over whatever the writer says, because the writer's own
      // complaint is a consequence of the frame the chain could not build.
      val broken = composition.chainFailure()
      if (broken != null) {
        writer.cancelWriting()
        outcome.complete(Outcome.Failed(broken))
        return
      }

      writer.finishWritingWithCompletionHandler {
        outcome.complete(
          if (writer.status == AVAssetWriterStatusCompleted) {
            Outcome.Completed
          } else {
            Outcome.Failed(writer.error.toExportError(FailingSide.Writer))
          },
        )
      }
    }
  }

  private sealed interface Outcome {
    data object Completed : Outcome

    class Failed(
      val error: ExportError,
    ) : Outcome
  }

  private sealed interface Startup {
    class Running(
      val session: Session,
    ) : Startup

    class Rejected(
      val error: ExportError,
    ) : Startup
  }

  private companion object {
    val PROGRESS_INTERVAL = 250L.milliseconds

    const val QUEUE_LABEL = "dev.jordond.filmstrip.export"
    const val MILLIS_PER_SECOND = 1_000L
    const val BITS_PER_BYTE = 8L
    const val HEADROOM_NUMERATOR = 13L
    const val HEADROOM_DENOMINATOR = 10L

    const val NO_READER = "AVFoundation could not open a reader over this composition."
    const val NO_WRITER = "AVFoundation could not open a writer at this destination."

    const val NO_HDR_PASSTHROUGH =
      "This export was planned as a copy so the HDR grade would ride across untouched, and " +
        "AVFoundation described none of the source's tracks well enough to copy. Re-encoding " +
        "would have written the grade this device cannot encode."
    const val READER_REFUSED = "The reader refused an output for this composition."
    const val WRITER_REFUSED = "The writer refused an input for these output settings."
    const val READ_CANCELLED = "Reading the composition was cancelled before it finished."

    const val NOTHING_TO_WRITE =
      "This composition contributed no track the writer could take, so the export would write a " +
        "file with nothing in it."

    const val NOT_READABLE =
      "The writer reported success but the output could not be read back, so nothing usable was " +
        "written."
  }
}

/**
 * The only serialisation point on this platform.
 *
 * Hardware encode sessions are a small shared pool, and a device that has none left refuses the
 * session instead of queueing for one. Running exports one at a time turns that into a wait.
 */
private val exportLock = Mutex()

private fun String.parentDirectory(): String = substringBeforeLast('/', missingDelimiterValue = ".").ifEmpty { "/" }

/**
 * Whether an export planned as a copy has to be refused rather than encoded instead.
 *
 * A copy is the only way a graded source reaches a device that cannot encode the grade, so falling
 * back to an encode would ask VideoToolbox for what `capabilities()` said it has not got and write
 * an SDR stream into a file tagged BT.2020. An SDR copy that cannot pass through re-encodes to the
 * same picture, so only a graded one is refused.
 *
 * @param canPassThrough Whether the writer could describe every track well enough to copy it.
 */
internal fun refusesFallbackEncode(
  path: ExportPath,
  canPassThrough: Boolean,
  transfer: HdrTransfer?,
): Boolean = path == ExportPath.Transmux && !canPassThrough && transfer != null

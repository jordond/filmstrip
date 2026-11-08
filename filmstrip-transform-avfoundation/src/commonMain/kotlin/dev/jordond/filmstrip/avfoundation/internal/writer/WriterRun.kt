package dev.jordond.filmstrip.avfoundation.internal.writer

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.avfoundation.internal.composition.AvComposition
import dev.jordond.filmstrip.avfoundation.internal.interop.copyFormatHint
import dev.jordond.filmstrip.avfoundation.internal.time.MEDIA_TIMESCALE
import dev.jordond.filmstrip.avfoundation.internal.time.toCMTime
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.transform.internal.heldTail
import dev.jordond.filmstrip.transform.internal.probe.readSources
import dev.jordond.filmstrip.transform.internal.remainingAt
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.videoSlots
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderAudioMixOutput
import platform.AVFoundation.AVAssetReaderOutput
import platform.AVFoundation.AVAssetReaderTrackOutput
import platform.AVFoundation.AVAssetReaderVideoCompositionOutput
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVAssetWriter
import platform.AVFoundation.AVAssetWriterInput
import platform.AVFoundation.AVFileTypeMPEG4
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.mediaTimeScale
import platform.AVFoundation.naturalTimeScale
import platform.AVFoundation.tracksWithMediaType
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMFormatDescriptionRef
import platform.Foundation.NSError
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSNumber
import platform.darwin.dispatch_queue_create
import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Drives one export with an `AVAssetReader` pulling composited frames and mixed samples, and an
 * `AVAssetWriter` encoding them.
 *
 * A reader and a writer on every path, copy included, so a bitrate, an output size and a frame
 * rate are settable everywhere [dev.jordond.filmstrip.export.OutputFormat] promises them, and
 * progress stays linear in the work done.
 *
 * @param copyVideoPackets How many video samples a copy writes before its video stops, or null when it writes every
 * sample the composition holds.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class WriterRun(
  private val prober: MediaProber,
  private val resolved: ResolvedComposition,
  private val composition: AvComposition,
  private val destination: AvDestination,
  private val copyVideoPackets: Int? = null,
  private val announcesStart: Boolean = true,
) {
  private val queue = dispatch_queue_create(QUEUE_LABEL, attr = null)

  // A plan on the grid renders a frame every frame duration until the audio ends, which can be up to half a slot past
  // the last slot, so the video pump stops once it reaches a frame past that slot.
  private val clockFrameRate: Int? =
    resolved.output.frameRate.takeIf {
      resolved.path != ExportPath.Copy &&
        (!resolved.heldTail.isEmpty() || composition.drawnByCompositor || composition.timedByGrid)
    }

  fun run(): Flow<ExportStatus> = flow { collect() }

  private suspend fun FlowCollector<ExportStatus>.collect() {
    val outcome = CompletableDeferred<WriterOutcome>()
    var session: WriterSession? = null

    try {
      when (val started = start(outcome)) {
        is Startup.Rejected -> {
          emit(ExportStatus.Failure(started.error))
        }
        is Startup.Running -> {
          session = started.session
          if (announcesStart) emit(ExportStatus.Started)
          emit(report(awaitOutcome(outcome, started.session)))
        }
      }
    } finally {
      withContext(NonCancellable) {
        if (!outcome.isCompleted) {
          session?.stop()
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
  private fun start(outcome: CompletableDeferred<WriterOutcome>): Startup {
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
            failure.value?.toFilmstripError(FailingSide.Reader) ?: FilmstripError.InvalidComposition(NO_READER),
          )
      }
    val writer =
      memScoped {
        val failure = alloc<ObjCObjectVar<NSError?>>()
        AVAssetWriter.assetWriterWithURL(destination.url, fileType = AVFileTypeMPEG4, error = failure.ptr)
          ?: return Startup.Rejected(
            failure.value?.toFilmstripError(FailingSide.Writer)
              ?: FilmstripError.SinkUnwritable(destination.path, NO_WRITER),
          )
      }

    val videoTracks = asset.tracksWithMediaType(AVMediaTypeVideo)
    val audioTracks = asset.tracksWithMediaType(AVMediaTypeAudio)
    // The hints are only alive for as long as it takes to open the inputs, which retain whichever
    // one they were handed.
    val videoHint = videoTracks.copyFormatHint()
    val audioHint = audioTracks.copyFormatHint()

    // A copying input carries no output settings, so the only thing describing what it is
    // writing is the source's format. Without one there is nothing to copy and the encode
    // is the honest answer.
    val copies =
      resolved.path == ExportPath.Copy &&
        videoHint != null &&
        (audioTracks.isEmpty() || audioHint != null)

    val pumps =
      try {
        if (refusesFallbackEncode(resolved.path, copies, resolved.hdrTransfer)) {
          return Startup.Rejected(FilmstripError.InvalidComposition(NO_HDR_COPY))
        }
        pumpsFor(videoTracks, audioTracks, copies, videoHint, audioHint)
      } finally {
        videoHint?.let { CFRelease(it) }
        audioHint?.let { CFRelease(it) }
      }

    if (pumps.isEmpty()) return Startup.Rejected(FilmstripError.InvalidComposition(NOTHING_TO_WRITE))
    attach(reader, writer, pumps)?.let { return Startup.Rejected(it) }
    begin(reader, writer)?.let { return Startup.Rejected(it) }

    val session =
      WriterSession(
        reader = reader,
        writer = writer,
        pumps = pumps,
        outcome = outcome,
        queue = queue,
        composition = composition,
        clockFrameRate = clockFrameRate,
        videoSlots = resolved.videoSlots,
        copyVideoPackets = copyVideoPackets.takeIf { copies },
        failure = { error, side -> error.toFilmstripError(side) },
      )
    session.drive()
    return Startup.Running(session)
  }

  /**
   * The pumps this export moves samples through, one per kind of track the output carries.
   *
   * An input takes output settings or a format hint, never both. AVFoundation refuses one carrying
   * a full settings dictionary alongside a hint describing a different format, so the encode names
   * its settings and the copy names the source.
   */
  private fun pumpsFor(
    videoTracks: List<*>,
    audioTracks: List<*>,
    copies: Boolean,
    videoHint: CMFormatDescriptionRef?,
    audioHint: CMFormatDescriptionRef?,
  ): List<WriterPump> =
    buildList {
      if (videoTracks.isNotEmpty() && composition.videoComposition != null) {
        add(videoPump(videoTracks, copies, videoHint))
      }
      if (audioTracks.isNotEmpty()) audioPump(audioTracks, copies, audioHint)?.let(::add)
    }

  private fun videoPump(
    tracks: List<*>,
    copies: Boolean,
    hint: CMFormatDescriptionRef?,
  ): WriterPump {
    val input =
      AVAssetWriterInput(
        mediaType = AVMediaTypeVideo,
        outputSettings =
          if (copies) {
            null
          } else {
            videoWriterSettings(
              output = resolved.output,
              encodesHdr = composition.encodesHdr,
              transfer = composition.transfer,
              sourceFormat = hint,
            )
          },
        sourceFormatHint = hint.takeIf { copies },
      )
    // An encode writes each frame on the composition's own tick. A copy is always a single clip, since
    // the plan refuses to copy more than one (CopyBlocker.MultipleClips), so the composition's video
    // track carries that clip's source timescale and each sample is written at the time its source holds it.
    input.mediaTimeScale = if (copies) (tracks.first() as AVAssetTrack).naturalTimeScale else MEDIA_TIMESCALE
    return WriterPump(input, videoOutput(tracks, copies), reportsProgress = true)
  }

  /**
   * The audio pump, or null when the output carries no audio and nothing is being copied.
   */
  private fun audioPump(
    tracks: List<*>,
    copies: Boolean,
    hint: CMFormatDescriptionRef?,
  ): WriterPump? {
    val settings = if (copies) null else audioWriterSettings(resolved.output)
    if (settings == null && !copies) return null

    val input =
      AVAssetWriterInput(
        mediaType = AVMediaTypeAudio,
        outputSettings = settings,
        sourceFormatHint = hint.takeIf { copies },
      )
    return WriterPump(input, audioOutput(tracks, copies), reportsProgress = false)
  }

  /**
   * Adds every pump's output to [reader] and its input to [writer], or says which side refused one.
   *
   * Never assert-then-add. A release build would drop the assertion and write a file with a track
   * missing, which reads as a successful export of the wrong thing.
   */
  private fun attach(
    reader: AVAssetReader,
    writer: AVAssetWriter,
    pumps: List<WriterPump>,
  ): FilmstripError? {
    pumps.forEach { pump ->
      if (!reader.canAddOutput(pump.output)) return FilmstripError.InvalidComposition(READER_REFUSED)
      if (!writer.canAddInput(pump.input)) return FilmstripError.NoEncoder(resolved.output.videoCodec, WRITER_REFUSED)
      reader.addOutput(pump.output)
      pump.input.expectsMediaDataInRealTime = false
      writer.addInput(pump.input)
    }
    return null
  }

  /**
   * Starts the writer's session at zero and then the reader, or says which of them refused.
   */
  private fun begin(
    reader: AVAssetReader,
    writer: AVAssetWriter,
  ): FilmstripError? {
    if (!writer.startWriting()) return writer.error.toFilmstripError(FailingSide.Writer)
    writer.startSessionAtSourceTime(Duration.ZERO.toCMTime())

    if (!reader.startReading()) {
      writer.cancelWriting()
      return reader.error.toFilmstripError(FailingSide.Reader)
    }
    return null
  }

  /**
   * Waits for both pumps to drain, emitting progress as the video one climbs.
   */
  private suspend fun FlowCollector<ExportStatus>.awaitOutcome(
    outcome: CompletableDeferred<WriterOutcome>,
    session: WriterSession,
  ): WriterOutcome {
    val startedAt = TimeSource.Monotonic.markNow()
    var reported = 0f

    while (true) {
      val finished = withTimeoutOrNull(PROGRESS_INTERVAL) { outcome.await() }
      if (finished != null) return finished

      val position = session.writtenMicros.microseconds
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
      estimatedRemaining = remainingAt(fraction, elapsed),
    )

  private suspend fun report(outcome: WriterOutcome): ExportStatus =
    when (outcome) {
      is WriterOutcome.Failed -> {
        destination.discard()
        ExportStatus.Failure(outcome.error)
      }
      is WriterOutcome.Completed -> {
        probed()
      }
    }

  // AVAssetWriter reports what it was handed, not what landed in the container, and Success carries
  // a MediaInfo. Reading the written file back is the only answer that is about the real file.
  private suspend fun probed(): ExportStatus =
    when (val probe = prober.probe(destination.asSource())) {
      is ProbeResult.Failure -> ExportStatus.Failure(FilmstripError.SinkUnwritable(destination.path, NOT_READABLE))
      is ProbeResult.Success -> ExportStatus.Success(destination.sink, probe.info, resolved.adjustments)
    }

  private fun videoOutput(
    tracks: List<*>,
    copies: Boolean,
  ): AVAssetReaderOutput =
    if (copies) {
      AVAssetReaderTrackOutput(track = tracks.first() as AVAssetTrack, outputSettings = null)
    } else {
      AVAssetReaderVideoCompositionOutput(
        videoTracks = tracks,
        videoSettings = videoReaderSettings(composition.encodesHdr),
      ).apply { videoComposition = composition.videoComposition }
    }

  private fun audioOutput(
    tracks: List<*>,
    copies: Boolean,
  ): AVAssetReaderOutput =
    if (copies) {
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
   * Every transcode carries the plan's bitrate. A copy carries none, so there is no number to check
   * against. The headroom matches the one the planner puts on [ExportEstimate.outputSizeBytesMax],
   * so a caller who checked that number sees the same answer here.
   */
  private fun shortOfSpace(): FilmstripError? {
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
      FilmstripError.InsufficientStorage(required, "This export needs about $required bytes and $free are free.")
    } else {
      null
    }
  }

  private fun NSError?.toFilmstripError(side: FailingSide): FilmstripError =
    this?.toFilmstripError(resolved.output.videoCodec, side, resolved.readSources, destination.path)
      ?: FilmstripError.Underlying(
        FilmstripError.Underlying.NO_PLATFORM_CODE,
        "AVFoundation refused to start and reported no error.",
      )

  private sealed interface Startup {
    class Running(
      val session: WriterSession,
    ) : Startup

    class Rejected(
      val error: FilmstripError,
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

    const val NO_HDR_COPY =
      "This export was planned as a copy so the HDR grade would ride across untouched, and " +
        "AVFoundation described none of the source's tracks well enough to copy. Re-encoding " +
        "would have written the grade this device cannot encode."
    const val READER_REFUSED = "The reader refused an output for this composition."
    const val WRITER_REFUSED = "The writer refused an input for these output settings."

    const val NOTHING_TO_WRITE =
      "This composition contributed no track the writer could take, so the export would write a " +
        "file with nothing in it."

    const val NOT_READABLE =
      "The writer reported success but the output could not be read back, so nothing usable was " +
        "written."
  }
}

private fun String.parentDirectory(): String = substringBeforeLast('/', missingDelimiterValue = ".").ifEmpty { "/" }

/**
 * Whether an export planned as a copy has to be refused rather than encoded instead.
 *
 * A copy is the only way a graded source reaches a device that cannot encode the grade, so falling
 * back to an encode would ask VideoToolbox for what `capabilities()` said it has not got and write
 * an SDR stream into a file tagged BT.2020. An SDR copy the writer cannot describe re-encodes to the
 * same picture, so only a graded one is refused.
 *
 * @param canCopy Whether the writer could describe every track well enough to copy it.
 */
internal fun refusesFallbackEncode(
  path: ExportPath,
  canCopy: Boolean,
  transfer: HdrTransfer?,
): Boolean = path == ExportPath.Copy && !canCopy && transfer != null

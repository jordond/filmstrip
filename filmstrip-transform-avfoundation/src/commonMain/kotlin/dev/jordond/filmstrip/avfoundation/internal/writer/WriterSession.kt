package dev.jordond.filmstrip.avfoundation.internal.writer

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.avfoundation.internal.composition.AvComposition
import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import dev.jordond.filmstrip.edit.outputSlotOf
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.autoreleasepool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderOutput
import platform.AVFoundation.AVAssetReaderStatusFailed
import platform.AVFoundation.AVAssetWriter
import platform.AVFoundation.AVAssetWriterInput
import platform.AVFoundation.AVAssetWriterStatusCompleted
import platform.AVFoundation.AVAssetWriterStatusWriting
import platform.AVFoundation.AVMediaTypeVideo
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMSampleBufferGetNumSamples
import platform.CoreMedia.CMSampleBufferGetPresentationTimeStamp
import platform.CoreMedia.CMSampleBufferRef
import platform.Foundation.NSError
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_t
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One writer input and the reader output that feeds it.
 *
 * @param read Takes the next sample from [output] on the pump queue, following the create rule.
 * @param append Hands a sample to [input], and says whether the input took it.
 */
@OptIn(ExperimentalForeignApi::class)
internal class WriterPump(
  val input: AVAssetWriterInput,
  val output: AVAssetReaderOutput,
  val reportsProgress: Boolean,
  val read: () -> CMSampleBufferRef? = output::copyNextSampleBuffer,
  val append: (CMSampleBufferRef) -> Boolean = input::appendSampleBuffer,
)

/**
 * How a [WriterSession] ended once both pumps drained or one step broke the run.
 */
internal sealed interface WriterOutcome {
  data object Completed : WriterOutcome

  class Failed(
    val error: FilmstripError,
  ) : WriterOutcome
}

/**
 * The pumps of one export and the barrier that decides when the file is finished.
 *
 * Both pumps run on one serial [queue], so what they mutate is confined to it and needs no atomics. The completion
 * handler is the only thing that crosses back to the coroutine.
 *
 * The reader and the writer are only ever stopped on [queue], so a cancel never overlaps a read or an append.
 * AVFoundation rules that overlap out, and landing one faults inside `copyNextSampleBuffer`.
 *
 * A step the chain could not run fails the run at the next sample either pump moves, rather than once both have
 * drained. The writer and the reader are cancelled there and the step's reason is the outcome.
 *
 * A writer that failed by itself fails the run with what it reported, as soon as a pump finds it no longer writing. An
 * append the writer rejected fails the run whatever the writer's status says, so a rejected sample never ends as a
 * finished file.
 *
 * @param clockFrameRate The output rate a composition drawing on the clock renders at, or null when it draws a frame
 * for each sample. The video pump stops on the first frame at or past [videoSlots] when it is set.
 * @param copyVideoPackets How many video samples a copy writes, in the order the reader hands them over, or null when
 * the video pump writes every sample the reader has.
 * @param failure Maps what AVFoundation reported onto the error the run ends with.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class WriterSession(
  private val reader: AVAssetReader,
  private val writer: AVAssetWriter,
  private val pumps: List<WriterPump>,
  private val outcome: CompletableDeferred<WriterOutcome>,
  private val queue: dispatch_queue_t,
  private val composition: AvComposition,
  private val clockFrameRate: Int?,
  private val videoSlots: Long,
  private val copyVideoPackets: Int?,
  private val failure: (NSError?, FailingSide) -> FilmstripError,
) {
  /**
   * How far the video pump has written, in microseconds of composition time.
   *
   * Written on the pump queue and read from the collecting coroutine, which is what the volatile is
   * for. A presentation timestamp is the only progress an `AVAssetWriter` offers, since it reports
   * no completion fraction of its own.
   */
  @Volatile
  var writtenMicros = 0L
    private set

  private var drained = 0

  private var videoWritten = 0

  // Set once the video pump stops on the slot cutoff or a copy's count rather than the output's own end,
  // which leaves samples the reader never read, so finish() cancels the reader for them.
  private var stoppedEarly = false

  // What a failed reader reported to a read that came up empty, which puts its failure ahead of a writer found failed
  // later on.
  private var readerFailure: NSError? = null

  // Set from outside the queue by stop(), and read by the pumps on it.
  @Volatile
  private var stopping = false

  // Set once finish() has handed the file to finishWriting, which a cancel must not overlap.
  private var finishing = false

  /**
   * Starts every pump pulling from its reader output on [queue].
   */
  fun drive() {
    pumps.forEach(::drive)
  }

  /**
   * Stops the run on [queue], once the step a pump is on has come out.
   *
   * The running pump leaves its loop at its next check, and a later ready callback does nothing. The writer and the
   * reader are then cancelled and no outcome is completed. A writer already finishing is left alone and this waits for
   * its outcome instead. It can be called before any sample has moved, and more than once.
   *
   * A step that outlasts [wait] can remove a later export's file at the same path, since the queued `cancelWriting`
   * deletes by URL whenever it lands.
   *
   * @param wait How long to hold the caller. A step stuck inside AVFoundation outlasts it, and the cancel stays queued
   * and runs once that step comes out.
   */
  suspend fun stop(wait: Duration = STOP_WAIT) {
    stopping = true
    val writerFinishing = CompletableDeferred<Boolean>()
    dispatch_async(queue) {
      if (!finishing) {
        if (writer.status == AVAssetWriterStatusWriting) writer.cancelWriting()
        reader.cancelReading()
      }
      writerFinishing.complete(finishing)
    }
    withTimeoutOrNull(wait) {
      if (writerFinishing.await()) outcome.await()
    }
  }

  private fun drive(pump: WriterPump) {
    pump.input.requestMediaDataWhenReadyOnQueue(queue) {
      while (!stopping && pump.input.isReadyForMoreMediaData()) {
        if (brokeEarly()) return@requestMediaDataWhenReadyOnQueue
        when (autoreleasepool { step(pump) }) {
          Step.Moved -> {
            Unit
          }
          Step.Drained -> {
            finish(pump)
            return@requestMediaDataWhenReadyOnQueue
          }
          Step.Rejected -> {
            failOnWriter()
            return@requestMediaDataWhenReadyOnQueue
          }
        }
      }
    }
  }

  /**
   * Whether the run has failed on a step the chain could not run, failing it the first time one is seen.
   *
   * The chain sets its failure on the render queue before it hands the frame over, so the sample after the broken
   * frame is the latest this sees it. A run being stopped breaks here too, with no outcome, since [stop] owns the
   * cancels. A writer that is no longer writing failed by itself, and is left alone.
   */
  private fun brokeEarly(): Boolean {
    if (stopping || outcome.isCompleted) return true
    val broken = composition.chainFailure() ?: return false
    if (writer.status == AVAssetWriterStatusWriting) writer.cancelWriting()
    reader.cancelReading()
    outcome.complete(WriterOutcome.Failed(broken))
    return true
  }

  /**
   * Moves one sample across, and says how that went.
   */
  private fun step(pump: WriterPump): Step {
    val video = pump.input.mediaType == AVMediaTypeVideo
    if (video && copyVideoPackets != null && videoWritten >= copyVideoPackets) {
      stoppedEarly = true
      return Step.Drained
    }
    val buffer: CMSampleBufferRef? = pump.read()
    if (buffer == null) {
      readerFailure = reader.error.takeIf { reader.status == AVAssetReaderStatusFailed }
      return Step.Drained
    }
    if (clockFrameRate != null && video) {
      val slot = outputSlotOf(CMSampleBufferGetPresentationTimeStamp(buffer).toDuration(), clockFrameRate)
      if (slot >= videoSlots) {
        CFRelease(buffer)
        stoppedEarly = true
        return Step.Drained
      }
    }
    val appended = pump.append(buffer)
    if (appended && video) videoWritten += CMSampleBufferGetNumSamples(buffer).toInt()
    if (appended && pump.reportsProgress) {
      writtenMicros = CMSampleBufferGetPresentationTimeStamp(buffer).toDuration().inWholeMicroseconds
    }
    // copyNextSampleBuffer follows the create rule, so every buffer is released here whatever the
    // append did with it.
    CFRelease(buffer)
    return if (appended) Step.Moved else Step.Rejected
  }

  private fun finish(pump: WriterPump) {
    // markAsFinished and finishWriting both raise on a writer that is not writing, and an Objective-C
    // exception here terminates the process instead of failing the export.
    if (writer.status != AVAssetWriterStatusWriting) {
      failOnWriter()
      return
    }

    pump.input.markAsFinished()
    drained++
    if (drained < pumps.size) return
    if (stopping) return

    // A genuine reader failure has to be read before anything cancels the reader, since cancelling
    // first would turn AVAssetReaderStatusFailed into AVAssetReaderStatusCancelled and report a
    // real failure as a clean stop.
    val drainFailure = reader.error.takeIf { reader.status == AVAssetReaderStatusFailed }
    if (drainFailure != null) {
      writer.cancelWriting()
      outcome.complete(WriterOutcome.Failed(failure(drainFailure, FailingSide.Reader)))
      return
    }

    // The video pump stopping on the slot cutoff leaves this output's samples unread, and a
    // hardware decode session is a small shared pool, so the reader is cancelled here. Every pump
    // is drained by this point, so cancelling stops nothing still running, and only happens once
    // the export is going on to finish normally.
    if (stoppedEarly) {
      reader.cancelReading()
    }

    // A step that threw on the last frame is seen only here, after its sample was the last one moved. It is
    // reported over whatever the writer says, since the writer's complaint follows from the frame the chain could
    // not build.
    if (brokeEarly()) return

    finishing = true
    writer.finishWritingWithCompletionHandler {
      outcome.complete(
        if (writer.status == AVAssetWriterStatusCompleted) {
          WriterOutcome.Completed
        } else {
          WriterOutcome.Failed(failure(writer.error, FailingSide.Writer))
        },
      )
    }
  }

  /**
   * Fails the run on a writer that rejected an append or stopped writing by itself. A writer still writing is
   * cancelled, which removes its file, and the reader is cancelled so the other pump and the decode session stop.
   *
   * A stop in progress completes no outcome here, and a step that could not run keeps its own reason. A reader already
   * seen failing by a read keeps its reason too, since it failed ahead of the writer being found. A writer with no
   * error to report fails the run on a named one.
   */
  private fun failOnWriter() {
    if (brokeEarly()) return
    val reason =
      readerFailure?.let { failure(it, FailingSide.Reader) }
        ?: writer.error?.let { failure(it, FailingSide.Writer) }
        ?: FilmstripError.Underlying(FilmstripError.Underlying.NO_PLATFORM_CODE, WRITER_GAVE_NO_ERROR)
    if (writer.status == AVAssetWriterStatusWriting) writer.cancelWriting()
    reader.cancelReading()
    outcome.complete(WriterOutcome.Failed(reason))
  }

  private enum class Step {
    Moved,
    Drained,
    Rejected,
  }
}

private const val WRITER_GAVE_NO_ERROR = "The writer rejected a sample or stopped writing and reported no error."

private val STOP_WAIT = 30.seconds

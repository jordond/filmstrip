package dev.jordond.filmstrip.avfoundation.internal.writer

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.avfoundation.internal.composition.AvComposition
import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import dev.jordond.filmstrip.edit.outputSlotOf
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.autoreleasepool
import kotlinx.coroutines.CompletableDeferred
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderOutput
import platform.AVFoundation.AVAssetReaderStatusCancelled
import platform.AVFoundation.AVAssetReaderStatusFailed
import platform.AVFoundation.AVAssetWriter
import platform.AVFoundation.AVAssetWriterInput
import platform.AVFoundation.AVAssetWriterStatusCompleted
import platform.AVFoundation.AVAssetWriterStatusWriting
import platform.AVFoundation.AVMediaTypeVideo
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMSampleBufferGetPresentationTimeStamp
import platform.CoreMedia.CMSampleBufferRef
import platform.Foundation.NSError
import platform.darwin.dispatch_queue_t
import kotlin.concurrent.Volatile

/**
 * One writer input and the reader output that feeds it.
 */
internal class WriterPump(
  val input: AVAssetWriterInput,
  val output: AVAssetReaderOutput,
  val reportsProgress: Boolean,
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
 * Both pumps run on one serial [queue], so everything mutable in here is confined to it and needs
 * no atomics. The completion handler is the only thing that crosses back to the coroutine.
 *
 * A step the chain could not run fails the run at the next sample either pump moves, rather than once both have
 * drained. The writer and the reader are cancelled there and the step's reason is the outcome.
 *
 * @param heldFrameRate The output rate a held tail renders at, or null when nothing is held. The video pump stops
 * on the first frame at or past [videoSlots] when it is set.
 * @param failure Maps what AVFoundation reported onto the error the run ends with.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class WriterSession(
  val reader: AVAssetReader,
  val writer: AVAssetWriter,
  private val pumps: List<WriterPump>,
  private val outcome: CompletableDeferred<WriterOutcome>,
  private val queue: dispatch_queue_t,
  private val composition: AvComposition,
  private val heldFrameRate: Int?,
  private val videoSlots: Long,
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

  // Set once the video pump stops on the held-tail slot cutoff rather than the output's own end,
  // which leaves samples the reader never read. finish() checks this before it cancels the reader
  // for that case, so the cancel it triggers below is never read back as an outside cancel.
  private var stoppedEarly = false

  /**
   * Starts every pump pulling from its reader output on [queue].
   */
  fun drive() {
    pumps.forEach(::drive)
  }

  private fun drive(pump: WriterPump) {
    pump.input.requestMediaDataWhenReadyOnQueue(queue) {
      while (pump.input.isReadyForMoreMediaData()) {
        if (brokeEarly()) return@requestMediaDataWhenReadyOnQueue
        if (autoreleasepool { !step(pump) }) {
          finish(pump)
          return@requestMediaDataWhenReadyOnQueue
        }
      }
    }
  }

  /**
   * Whether the run has failed on a step the chain could not run, failing it the first time one is seen.
   *
   * The chain sets its failure on the render queue before it hands the frame over, so the sample after the broken
   * frame is the latest this sees it. A writer that is no longer writing was stopped from outside, and is left alone.
   */
  private fun brokeEarly(): Boolean {
    if (outcome.isCompleted) return true
    val broken = composition.chainFailure() ?: return false
    if (writer.status == AVAssetWriterStatusWriting) writer.cancelWriting()
    reader.cancelReading()
    outcome.complete(WriterOutcome.Failed(broken))
    return true
  }

  /**
   * Moves one sample across, and says whether there is more to move.
   */
  private fun step(pump: WriterPump): Boolean {
    val buffer: CMSampleBufferRef = pump.output.copyNextSampleBuffer() ?: return false
    if (heldFrameRate != null && pump.input.mediaType == AVMediaTypeVideo) {
      val slot = outputSlotOf(CMSampleBufferGetPresentationTimeStamp(buffer).toDuration(), heldFrameRate)
      if (slot >= videoSlots) {
        CFRelease(buffer)
        stoppedEarly = true
        return false
      }
    }
    val appended = pump.input.appendSampleBuffer(buffer)
    if (appended && pump.reportsProgress) {
      writtenMicros = CMSampleBufferGetPresentationTimeStamp(buffer).toDuration().inWholeMicroseconds
    }
    // copyNextSampleBuffer follows the create rule, so every buffer is released here whatever the
    // append did with it.
    CFRelease(buffer)
    return appended
  }

  private fun finish(pump: WriterPump) {
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

    // A genuine reader failure has to be read before anything cancels the reader, since cancelling
    // first would turn AVAssetReaderStatusFailed into AVAssetReaderStatusCancelled and report a
    // real failure as a clean stop.
    val readerFailure = reader.error.takeIf { reader.status == AVAssetReaderStatusFailed }
    if (readerFailure != null) {
      writer.cancelWriting()
      outcome.complete(WriterOutcome.Failed(failure(readerFailure, FailingSide.Reader)))
      return
    }
    if (reader.status == AVAssetReaderStatusCancelled) {
      writer.cancelWriting()
      outcome.complete(WriterOutcome.Failed(FilmstripError.InvalidComposition(READ_CANCELLED)))
      return
    }

    // The video pump stopping on the held-tail cutoff leaves this output's samples unread, and a
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
}

private const val READ_CANCELLED = "Reading the composition was cancelled before it finished."

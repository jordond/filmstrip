package dev.jordond.filmstrip.avfoundation.internal.writer

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.media.MediaSink
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAssetReaderStatusCancelled
import platform.AVFoundation.AVAssetReaderStatusReading
import platform.AVFoundation.AVAssetWriterStatus
import platform.AVFoundation.AVAssetWriterStatusCancelled
import platform.AVFoundation.AVAssetWriterStatusCompleted
import platform.AVFoundation.AVAssetWriterStatusFailed
import platform.AVFoundation.AVAssetWriterStatusWriting
import platform.CoreMedia.CMSampleBufferRef
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_async
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_sync
import platform.darwin.dispatch_time
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

/**
 * One export's pump and the barrier that finishes the file, driven over a real reader and writer that copy a fixture's
 * video across untouched.
 */
@OptIn(ExperimentalForeignApi::class)
class WriterSessionTest {
  private val written = "${NSTemporaryDirectory()}writer-session-${NSUUID().UUIDString}.mov"

  @AfterTest
  fun removeOutput() {
    NSFileManager.defaultManager.removeItemAtPath(written, error = null)
  }

  @Test
  fun `a run that drains finishes the file and reports how far it wrote`() =
    runTest {
      val rig = WriterSessionRig(written)

      rig.session(heldFrameRate = null).drive()

      rig.outcome.await() shouldBe WriterOutcome.Completed
      withClue("no file was written at $written") {
        NSFileManager.defaultManager.fileExistsAtPath(written).shouldBeTrue()
      }
      withClue("wrote to ${rig.writtenMicros} us") {
        (rig.writtenMicros.microseconds > 1_900_000.microseconds).shouldBeTrue()
      }
    }

  // The video stops on the first frame of the held tail and leaves samples unread, and cancelling the reader for them
  // is still a run that finishes.
  @Test
  fun `a run that stops at the held tail still finishes the file`() =
    runTest {
      val rig = WriterSessionRig(written)

      rig.session(heldFrameRate = RATE, videoSlots = SLOTS).drive()

      rig.outcome.await() shouldBe WriterOutcome.Completed
      withClue("wrote to ${rig.writtenMicros} us, past slot $SLOTS") {
        (rig.writtenMicros.microseconds < outputSlotTime(SLOTS, RATE)).shouldBeTrue()
      }
      (rig.writtenMicros.microseconds >= outputSlotTime(SLOTS - 2, RATE)).shouldBeTrue()
    }

  // The step is held open, so a cancel that doesn't wait for it overlaps a read, the overlap AVFoundation faults on.
  @Test
  fun `stop during a run waits out the step then cancels the writer and the reader`() =
    runTest {
      val rig = WriterSessionRig(written)
      val gate = StepGate { read, _ -> read == MID_RUN_READ }
      val session = rig.session(heldFrameRate = null, gate = gate)

      withContext(Dispatchers.Default) {
        session.drive()
        gate.held.await()
        val stopped = async { session.stop() }
        delay(HOLD)

        stopped.isCompleted shouldBe false
        rig.writer.status shouldBe AVAssetWriterStatusWriting
        rig.reader.status shouldBe AVAssetReaderStatusReading

        gate.release()
        stopped.await()

        rig.writer.status shouldBe AVAssetWriterStatusCancelled
        rig.reader.status shouldBe AVAssetReaderStatusCancelled
        rig.outcome.isCompleted shouldBe false

        val written = rig.writtenMicros
        delay(HOLD)
        rig.writtenMicros shouldBe written
      }
    }

  // The reader has run dry by the time this holds, so the pump goes straight on to finish() when it is let go.
  @Test
  fun `stop as the last sample drains finishes nothing`() =
    runTest {
      val rig = WriterSessionRig(written)
      val gate = StepGate { _, buffer -> buffer == null }
      val session = rig.session(heldFrameRate = null, gate = gate)

      withContext(Dispatchers.Default) {
        session.drive()
        gate.held.await()
        val stopped = async { session.stop() }
        delay(HOLD)
        gate.release()
        stopped.await()
      }

      rig.writer.status shouldBe AVAssetWriterStatusCancelled
      rig.outcome.isCompleted shouldBe false
    }

  @Test
  fun `stop before any sample moved and twice does not raise`() =
    runTest {
      val rig = WriterSessionRig(written)
      val session = rig.session(heldFrameRate = null)

      withContext(Dispatchers.Default) {
        session.stop()
        session.stop()
      }

      rig.writer.status shouldBe AVAssetWriterStatusCancelled
      rig.reader.status shouldBe AVAssetReaderStatusCancelled
      rig.writtenMicros shouldBe 0L
    }

  // The queue is held while the pump's first ready callback and the stop both line up behind it.
  @Test
  fun `a ready callback behind a stop moves no sample`() =
    runTest {
      val rig = WriterSessionRig(written)
      val session = rig.session(heldFrameRate = null)
      val released = dispatch_semaphore_create(0)
      dispatch_async(rig.queue) {
        dispatch_semaphore_wait(released, dispatch_time(DISPATCH_TIME_NOW, GATE_LIMIT.inWholeNanoseconds))
      }

      withContext(Dispatchers.Default) {
        session.drive()
        val stopped = async { session.stop() }
        delay(HOLD)
        dispatch_semaphore_signal(released)
        stopped.await()
        delay(HOLD)
      }

      rig.writtenMicros shouldBe 0L
      rig.outcome.isCompleted shouldBe false
    }

  // A step stuck inside AVFoundation can't hold the caller forever, and the cancel it left queued still runs.
  @Test
  fun `a stop that runs out of wait still cancels once the step comes out`() =
    runTest {
      val rig = WriterSessionRig(written)
      val gate = StepGate { read, _ -> read == MID_RUN_READ }
      val session = rig.session(heldFrameRate = null, gate = gate)

      withContext(Dispatchers.Default) {
        session.drive()
        gate.held.await()
        session.stop(wait = HOLD)

        rig.writer.status shouldBe AVAssetWriterStatusWriting
        rig.reader.status shouldBe AVAssetReaderStatusReading

        gate.release()
        dispatch_sync(rig.queue) {}
      }

      rig.writer.status shouldBe AVAssetWriterStatusCancelled
      rig.reader.status shouldBe AVAssetReaderStatusCancelled
    }

  // The outcome has completed before the stop starts, so there is no finish left for its teardown to overlap.
  @Test
  fun `a stop after the run finished leaves the finished file alone`() =
    runTest {
      val rig = WriterSessionRig(written)
      val session = rig.session(heldFrameRate = null)

      session.drive()
      rig.outcome.await() shouldBe WriterOutcome.Completed
      withContext(Dispatchers.Default) { session.stop() }

      rig.outcome.await() shouldBe WriterOutcome.Completed
      withClue("the finished file at $written is gone") {
        NSFileManager.defaultManager.fileExistsAtPath(written).shouldBeTrue()
      }
    }

  @Test
  fun `a stop during the finish waits for it and leaves the finished file alone`() =
    runTest {
      val rig = WriterSessionRig(written)
      val atReturn = CompletableDeferred<Pair<Boolean, AVAssetWriterStatus>>()

      withContext(Dispatchers.Default) {
        rig.driveThenAtFinish(this) { session ->
          session.stop()
          atReturn.complete(rig.outcome.isCompleted to rig.writer.status)
        }
        atReturn.await() shouldBe (true to AVAssetWriterStatusCompleted)
      }

      rig.outcome.await() shouldBe WriterOutcome.Completed
      withClue("the finished file at $written is gone") {
        NSFileManager.defaultManager.fileExistsAtPath(written).shouldBeTrue()
      }
    }

  // The wait has run out before the teardown runs, so the file is discarded with the finish still going.
  @Test
  fun `a finish outlasting the wait is not cancelled and a discard leaves no file`() =
    runTest {
      val rig = WriterSessionRig(written)
      val destination = AvDestination(NSURL.fileURLWithPath(written), written, MediaSink.Path(written))
      val discarded = CompletableDeferred<Unit>()

      withContext(Dispatchers.Default) {
        rig.driveThenAtFinish(this) { session ->
          session.stop(wait = Duration.ZERO)
          destination.discard()
          discarded.complete(Unit)
        }
        discarded.await()
        rig.outcome.await() shouldBe WriterOutcome.Completed
        dispatch_sync(rig.queue) {}
      }

      rig.writer.status shouldBe AVAssetWriterStatusCompleted
      NSFileManager.defaultManager.fileExistsAtPath(written) shouldBe false
    }

  // The reader has failed by the time the pump is let go into finish(), with the stop already set.
  @Test
  fun `stop as a failed reader drains completes no outcome`() =
    runTest {
      val rig = WriterSessionRig(written)
      val gate = StepGate { _, buffer -> buffer == null }
      val session = rig.session(heldFrameRate = null, gate = gate, sessionReader = FailedReader(rig.asset))

      withContext(Dispatchers.Default) {
        session.drive()
        gate.held.await()
        val stopped = async { session.stop() }
        delay(HOLD)
        gate.release()
        stopped.await()
      }

      rig.writer.status shouldBe AVAssetWriterStatusCancelled
      rig.outcome.isCompleted shouldBe false
      rig.reported.shouldBeEmpty()
    }

  @Test
  fun `a rejected append fails the run with the writer's error`() =
    runTest {
      val rig = WriterSessionRig(written, LONG_CLIP)
      val rejecting = RejectedRead(at = MID_RUN_READ)
      val session = rig.session(heldFrameRate = null, read = rejecting::read)

      session.drive()
      val failed = rig.awaitFailure()

      failed.error shouldBe rig.reported.single()
      rig.reportedSides.single() shouldBe FailingSide.Writer
      rig.reportedErrors.single().shouldNotBeNull() shouldBe rig.writer.error
      rig.writer.status shouldBe AVAssetWriterStatusFailed
      rig.reader.status shouldBe AVAssetReaderStatusCancelled

      withContext(Dispatchers.Default) { session.stop() }

      rig.writer.status shouldBe AVAssetWriterStatusFailed
      rig.outcome.await() shouldBe failed
    }

  @Test
  fun `the other pump moves nothing once the writer failed the run`() =
    runTest {
      val rig = WriterSessionRig(written, LONG_CLIP)
      val rejecting = RejectedRead(at = MID_RUN_READ)
      var otherReads = 0
      val session =
        rig.session(
          heldFrameRate = null,
          read = rejecting::read,
          otherPump = { output ->
            otherReads++
            output.copyNextSampleBuffer()
          },
        )

      session.drive()
      val failed = rig.awaitFailure()

      withContext(Dispatchers.Default) {
        dispatch_sync(rig.queue) {}
        val seen = otherReads
        delay(HOLD)
        dispatch_sync(rig.queue) {}
        otherReads shouldBe seen
      }
      rig.reportedSides.single() shouldBe FailingSide.Writer
      rig.reader.status shouldBe AVAssetReaderStatusCancelled
      rig.outcome.await() shouldBe failed
    }

  // The read comes up empty the way it does on a failed reader, with the writer already failed by then.
  @Test
  fun `a read that found the reader failed keeps the reader's reason`() =
    runTest {
      val rig = WriterSessionRig(written, LONG_CLIP)
      val failedReader = FailedReader(rig.asset)
      val session =
        rig.session(
          heldFrameRate = null,
          sessionReader = failedReader,
          read = { output ->
            failWriter(rig, output)
            null
          },
        )

      session.drive()
      rig.awaitFailure()

      rig.writer.status shouldBe AVAssetWriterStatusFailed
      rig.reportedSides.single() shouldBe FailingSide.Reader
      rig.reportedErrors.single() shouldBe failedReader.reason
    }

  // No read has come up empty, so the rejection is the first failure this run saw.
  @Test
  fun `a rejected append ahead of any empty read keeps the writer's reason`() =
    runTest {
      val rig = WriterSessionRig(written)
      val rejecting = RejectedRead(at = MID_RUN_READ)
      val session = rig.session(heldFrameRate = null, sessionReader = FailedReader(rig.asset), read = rejecting::read)

      session.drive()
      rig.awaitFailure()

      rig.reportedSides.single() shouldBe FailingSide.Writer
      rig.reportedErrors.single() shouldBe rig.writer.error
    }

  // The pump is held with the writer already failed, so the stop is set before the rejection and finish() see it.
  @Test
  fun `stop during a rejected append completes no outcome`() =
    runTest {
      val rig = WriterSessionRig(written, LONG_CLIP)
      val gate = StepGate { read, _ -> read == 0 }
      var writerFailed = false
      val session =
        rig.session(
          heldFrameRate = null,
          read = { output ->
            if (!writerFailed) failWriter(rig, output)
            writerFailed = true
            gate.read(output)
          },
        )

      withContext(Dispatchers.Default) {
        session.drive()
        gate.held.await()
        val stopped = async { session.stop() }
        delay(HOLD)
        gate.release()
        stopped.await()
        dispatch_sync(rig.queue) {}
      }

      rig.writer.status shouldBe AVAssetWriterStatusFailed
      rig.reader.status shouldBe AVAssetReaderStatusCancelled
      rig.outcome.isCompleted shouldBe false
      rig.reported.shouldBeEmpty()
    }

  // A real writer only rejects once it has stopped writing, so the append seam is what reaches this state.
  @Test
  fun `a rejected append with the writer still writing fails the run with a named error`() =
    runTest {
      val rig = WriterSessionRig(written, LONG_CLIP)
      val session = rig.session(heldFrameRate = null, append = rejectingAt(MID_RUN_READ, rig))

      session.drive()
      val failed = rig.awaitFailure()

      val error = failed.error.shouldBeInstanceOf<FilmstripError.Underlying>()
      error.message shouldContain "reported no error"
      rig.reported.shouldBeEmpty()
      rig.writer.status shouldBe AVAssetWriterStatusCancelled
      rig.reader.status shouldBe AVAssetReaderStatusCancelled
      NSFileManager.defaultManager.fileExistsAtPath(written) shouldBe false
    }

  // The pump is held inside the read, so the stop is set before the append it goes on to have rejected.
  @Test
  fun `stop ahead of a rejected append with the writer still writing completes no outcome`() =
    runTest {
      val rig = WriterSessionRig(written, LONG_CLIP)
      val gate = StepGate { read, _ -> read == MID_RUN_READ }
      val session = rig.session(heldFrameRate = null, gate = gate, append = rejectingAt(MID_RUN_READ, rig))

      withContext(Dispatchers.Default) {
        session.drive()
        gate.held.await()
        val stopped = async { session.stop() }
        delay(HOLD)
        gate.release()
        stopped.await()
      }

      rig.writer.status shouldBe AVAssetWriterStatusCancelled
      rig.reader.status shouldBe AVAssetReaderStatusCancelled
      rig.outcome.isCompleted shouldBe false
      rig.reported.shouldBeEmpty()
    }

  private fun rejectingAt(
    at: Int,
    rig: WriterSessionRig,
  ): (CMSampleBufferRef) -> Boolean {
    var appends = 0
    return { buffer -> if (appends++ == at) false else rig.input.appendSampleBuffer(buffer) }
  }

  // The last read queues this behind the step it is on, so it lands once finishWriting has been called and ahead of the
  // handler, which waits on the writer closing the file. Starting undispatched keeps it on the queue up to its first
  // suspension, which is after the stop has queued its teardown.
  private fun WriterSessionRig.driveThenAtFinish(
    scope: CoroutineScope,
    atFinish: suspend (WriterSession) -> Unit,
  ) {
    lateinit var session: WriterSession
    session =
      session(
        heldFrameRate = null,
        read = { output ->
          output.copyNextSampleBuffer().also { buffer ->
            if (buffer == null) {
              dispatch_async(queue) { scope.launch(start = CoroutineStart.UNDISPATCHED) { atFinish(session) } }
            }
          }
        },
      )
    session.drive()
  }

  private companion object {
    const val RATE = 30
    const val SLOTS = 15L
    val HOLD = 100.milliseconds
  }
}

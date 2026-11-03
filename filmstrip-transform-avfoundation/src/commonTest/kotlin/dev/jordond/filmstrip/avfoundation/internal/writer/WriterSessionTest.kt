package dev.jordond.filmstrip.avfoundation.internal.writer

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.avfoundation.fixture
import dev.jordond.filmstrip.avfoundation.internal.composition.AvComposition
import dev.jordond.filmstrip.avfoundation.internal.composition.toAvComposition
import dev.jordond.filmstrip.avfoundation.internal.interop.copyFormatHint
import dev.jordond.filmstrip.avfoundation.internal.laidPlan
import dev.jordond.filmstrip.avfoundation.internal.laidTrack
import dev.jordond.filmstrip.avfoundation.internal.time.toCMTime
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderTrackOutput
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVAssetWriter
import platform.AVFoundation.AVAssetWriterInput
import platform.AVFoundation.AVFileTypeQuickTimeMovie
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.tracksWithMediaType
import platform.CoreFoundation.CFRelease
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.darwin.dispatch_queue_create
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.seconds

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
      val rig = Rig()

      rig.session(heldFrameRate = null).drive()

      rig.outcome.await() shouldBe WriterOutcome.Completed
      withClue("no file was written at $written") {
        NSFileManager.defaultManager.fileExistsAtPath(written).shouldBeTrue()
      }
      withClue("wrote to ${rig.writtenMicros} us") {
        (rig.writtenMicros.microseconds > 1_900_000.microseconds).shouldBeTrue()
      }
    }

  // The video stops on the first frame of the held tail and leaves samples unread. Cancelling the reader
  // for them is the session's own doing, so it must not come back as an outside cancel.
  @Test
  fun `a run that stops at the held tail still finishes the file`() =
    runTest {
      val rig = Rig()

      rig.session(heldFrameRate = RATE, videoSlots = SLOTS).drive()

      rig.outcome.await() shouldBe WriterOutcome.Completed
      withClue("wrote to ${rig.writtenMicros} us, past slot $SLOTS") {
        (rig.writtenMicros.microseconds < outputSlotTime(SLOTS, RATE)).shouldBeTrue()
      }
      (rig.writtenMicros.microseconds >= outputSlotTime(SLOTS - 2, RATE)).shouldBeTrue()
    }

  @Test
  fun `a reader cancelled from outside fails the run rather than finishing a short file`() =
    runTest {
      val rig = Rig()
      val session = rig.session(heldFrameRate = null)

      rig.reader.cancelReading()
      session.drive()

      val failed = rig.outcome.await().shouldBeInstanceOf<WriterOutcome.Failed>()
      failed.error.shouldBeInstanceOf<FilmstripError.InvalidComposition>().message shouldBe
        "Reading the composition was cancelled before it finished."
    }

  /**
   * A reader over the fixture's video, a writer passing it through to [written], and the session's outcome.
   */
  private inner class Rig {
    private val track =
      AVURLAsset(uRL = NSURL.fileURLWithPath(fixture(CLIP)), options = null)
        .tracksWithMediaType(AVMediaTypeVideo)
        .single() as AVAssetTrack
    val reader = checkNotNull(AVAssetReader.assetReaderWithAsset(checkNotNull(track.asset), error = null))
    private val writer =
      checkNotNull(AVAssetWriter.assetWriterWithURL(NSURL.fileURLWithPath(written), AVFileTypeQuickTimeMovie, null))
    val outcome = CompletableDeferred<WriterOutcome>()
    private var session: WriterSession? = null

    val writtenMicros: Long get() = checkNotNull(session).writtenMicros

    fun session(
      heldFrameRate: Int?,
      videoSlots: Long = 0,
    ): WriterSession {
      val output = AVAssetReaderTrackOutput(track = track, outputSettings = null)
      val hint = listOf(track).copyFormatHint()
      val input =
        try {
          AVAssetWriterInput(mediaType = AVMediaTypeVideo, outputSettings = null, sourceFormatHint = hint)
        } finally {
          hint?.let { CFRelease(it) }
        }
      input.expectsMediaDataInRealTime = false
      reader.addOutput(output)
      writer.addInput(input)
      check(writer.startWriting()) { "the writer did not start: ${writer.error}" }
      writer.startSessionAtSourceTime(0.seconds.toCMTime())
      check(reader.startReading()) { "the reader did not start: ${reader.error}" }

      return WriterSession(
        reader = reader,
        writer = writer,
        pumps = listOf(WriterPump(input, output, reportsProgress = true)),
        outcome = outcome,
        queue = dispatch_queue_create("filmstrip.test.writer-session", null),
        composition = composition(),
        heldFrameRate = heldFrameRate,
        videoSlots = videoSlots,
        failure = { error, side -> FilmstripError.InvalidComposition("$side failed: $error") },
      ).also { session = it }
    }

    private fun composition(): AvComposition {
      val source = MediaSource.of(fixture(CLIP))
      return laidPlan(listOf(laidTrack(TrackContent.AudioAndVideo, 0.seconds, source to 2.seconds)), 2.seconds)
        .toAvComposition()
    }
  }

  private companion object {
    const val CLIP = "apple_export_a.mp4"
    const val RATE = 30
    const val SLOTS = 15L
  }
}

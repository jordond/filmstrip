@file:OptIn(ExperimentalForeignApi::class)

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
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import platform.AVFoundation.AVAsset
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderStatus
import platform.AVFoundation.AVAssetReaderStatusFailed
import platform.AVFoundation.AVAssetReaderTrackOutput
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVAssetWriter
import platform.AVFoundation.AVAssetWriterInput
import platform.AVFoundation.AVAssetWriterStatusWriting
import platform.AVFoundation.AVFileTypeQuickTimeMovie
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.tracksWithMediaType
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMSampleBufferCreateCopyWithNewTiming
import platform.CoreMedia.CMSampleBufferGetDecodeTimeStamp
import platform.CoreMedia.CMSampleBufferGetPresentationTimeStamp
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreMedia.CMSampleBufferRefVar
import platform.CoreMedia.CMSampleTimingInfo
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSError
import platform.Foundation.NSURL
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time
import kotlin.time.Duration.Companion.seconds

// The writer takes the refused sample and fails a few appends later, so this appends until it has.
internal fun failWriter(
  rig: WriterSessionRig,
  output: AVAssetReaderTrackOutput,
) {
  val rejecting = RejectedRead(at = MID_RUN_READ)
  while (rig.writer.status == AVAssetWriterStatusWriting) {
    val buffer = rejecting.read(output) ?: return
    rig.input.appendSampleBuffer(buffer)
    CFRelease(buffer)
  }
}

/**
 * A reader that says it failed, with its own error, whatever it has been asked to do.
 */
internal class FailedReader(
  asset: AVAsset,
) : AVAssetReader(asset, null) {
  val reason = NSError.errorWithDomain("filmstrip.test.reader", code = 1, userInfo = null)

  override fun status(): AVAssetReaderStatus = AVAssetReaderStatusFailed

  override fun error(): NSError = reason
}

/**
 * Hands over a real sample with a negative duration at one read. The writer input takes it, then fails the writer
 * and rejects an append a few samples later.
 *
 * @param at How many reads go by before the refused sample.
 * @param source Takes each sample from the output, following the create rule.
 */
internal class RejectedRead(
  private val at: Int,
  private val source: (AVAssetReaderTrackOutput) -> CMSampleBufferRef? = { it.copyNextSampleBuffer() },
) {
  private var reads = 0

  fun read(output: AVAssetReaderTrackOutput): CMSampleBufferRef? {
    val buffer = source(output)
    return if (reads++ == at) refused(checkNotNull(buffer)) else buffer
  }

  private fun refused(buffer: CMSampleBufferRef): CMSampleBufferRef =
    memScoped {
      val timing = alloc<CMSampleTimingInfo>()
      CMSampleBufferGetPresentationTimeStamp(buffer).place(timing.presentationTimeStamp.ptr)
      CMSampleBufferGetDecodeTimeStamp(buffer).place(timing.decodeTimeStamp.ptr)
      CMTimeMake(-1, REFUSED_TIMESCALE).place(timing.duration.ptr)
      val copy = alloc<CMSampleBufferRefVar>()
      val created = CMSampleBufferCreateCopyWithNewTiming(null, buffer, 1, timing.ptr, copy.ptr)
      CFRelease(buffer)
      check(created == 0) { "the sample was not retimed: $created" }
      checkNotNull(copy.value)
    }
}

/**
 * Holds a pump inside a read, after the sample has been taken, until the test lets it go.
 *
 * @param holds Whether to hold this read, given how many reads came before it and what it took.
 */
internal class StepGate(
  private val holds: (Int, CMSampleBufferRef?) -> Boolean,
) {
  val held = CompletableDeferred<Unit>()
  private val released = dispatch_semaphore_create(0)
  private var reads = 0

  fun read(output: AVAssetReaderTrackOutput): CMSampleBufferRef? {
    val buffer = output.copyNextSampleBuffer()
    if (holds(reads++, buffer)) {
      held.complete(Unit)
      dispatch_semaphore_wait(released, dispatch_time(DISPATCH_TIME_NOW, GATE_LIMIT.inWholeNanoseconds))
    }
    return buffer
  }

  fun release() {
    dispatch_semaphore_signal(released)
  }
}

/**
 * A reader over the fixture's video, a writer passing it through to [written], and the session's outcome.
 *
 * @param written Where the writer puts its file.
 * @param clip The fixture the reader reads.
 */
internal class WriterSessionRig(
  written: String,
  clip: String = CLIP,
) {
  val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(fixture(clip)), options = null)
  private val track = asset.tracksWithMediaType(AVMediaTypeVideo).single() as AVAssetTrack
  val reader = checkNotNull(AVAssetReader.assetReaderWithAsset(asset, error = null))
  val writer =
    checkNotNull(AVAssetWriter.assetWriterWithURL(NSURL.fileURLWithPath(written), AVFileTypeQuickTimeMovie, null))
  val outcome = CompletableDeferred<WriterOutcome>()
  val queue = dispatch_queue_create("filmstrip.test.writer-session", null)
  val reported = mutableListOf<FilmstripError>()
  val reportedSides = mutableListOf<FailingSide>()
  val reportedErrors = mutableListOf<NSError?>()
  lateinit var input: AVAssetWriterInput
  private var session: WriterSession? = null

  val writtenMicros: Long get() = checkNotNull(session).writtenMicros

  // Bounded on real time, since a writer whose failure nobody reports never completes the outcome.
  suspend fun awaitFailure(): WriterOutcome.Failed {
    val finished = withContext(Dispatchers.Default) { withTimeoutOrNull(OUTCOME_LIMIT) { outcome.await() } }
    return withClue("no outcome after $OUTCOME_LIMIT, the writer is ${writer.status}: ${writer.error}") {
      finished.shouldNotBeNull().shouldBeInstanceOf<WriterOutcome.Failed>()
    }
  }

  fun session(
    clockFrameRate: Int?,
    videoSlots: Long = 0,
    gate: StepGate? = null,
    read: ((AVAssetReaderTrackOutput) -> CMSampleBufferRef?)? = null,
    otherPump: ((AVAssetReaderTrackOutput) -> CMSampleBufferRef?)? = null,
    sessionReader: AVAssetReader = reader,
    append: ((CMSampleBufferRef) -> Boolean)? = null,
  ): WriterSession {
    val output = AVAssetReaderTrackOutput(track = track, outputSettings = null)
    input = passthroughInput(AVMediaTypeVideo, track)
    reader.addOutput(output)
    writer.addInput(input)
    val other =
      otherPump?.let { reads ->
        val audio = asset.tracksWithMediaType(AVMediaTypeAudio).single() as AVAssetTrack
        val audioOutput = AVAssetReaderTrackOutput(track = audio, outputSettings = null)
        val audioInput = passthroughInput(AVMediaTypeAudio, audio)
        reader.addOutput(audioOutput)
        writer.addInput(audioInput)
        WriterPump(audioInput, audioOutput, reportsProgress = false, read = { reads(audioOutput) })
      }
    check(writer.startWriting()) { "the writer did not start: ${writer.error}" }
    writer.startSessionAtSourceTime(0.seconds.toCMTime())
    check(reader.startReading()) { "the reader did not start: ${reader.error}" }

    val pumpRead = read ?: gate?.let { it::read }
    return WriterSession(
      reader = sessionReader,
      writer = writer,
      pumps =
        listOfNotNull(
          WriterPump(
            input = input,
            output = output,
            reportsProgress = true,
            read = if (pumpRead != null) ({ pumpRead(output) }) else output::copyNextSampleBuffer,
            append = append ?: input::appendSampleBuffer,
          ),
          other,
        ),
      outcome = outcome,
      queue = queue,
      composition = composition(),
      clockFrameRate = clockFrameRate,
      videoSlots = videoSlots,
      failure = { error, side ->
        FilmstripError.InvalidComposition("$side failed: $error").also {
          reported += it
          reportedSides += side
          reportedErrors += error
        }
      },
    ).also { session = it }
  }

  private fun passthroughInput(
    mediaType: String?,
    source: AVAssetTrack,
  ): AVAssetWriterInput {
    val hint = listOf(source).copyFormatHint()
    return try {
      AVAssetWriterInput(mediaType = mediaType, outputSettings = null, sourceFormatHint = hint)
    } finally {
      hint?.let { CFRelease(it) }
    }.apply { expectsMediaDataInRealTime = false }
  }

  private fun composition(): AvComposition {
    val source = MediaSource.of(fixture(CLIP))
    return laidPlan(listOf(laidTrack(TrackContent.AudioAndVideo, 0.seconds, source to 2.seconds)), 2.seconds)
      .toAvComposition()
  }
}

internal const val LONG_CLIP = "apple_export_long.mp4"
internal const val MID_RUN_READ = 5
internal val GATE_LIMIT = 30.seconds

private const val CLIP = "apple_export_a.mp4"
private const val REFUSED_TIMESCALE = 30
private val OUTCOME_LIMIT = 15.seconds

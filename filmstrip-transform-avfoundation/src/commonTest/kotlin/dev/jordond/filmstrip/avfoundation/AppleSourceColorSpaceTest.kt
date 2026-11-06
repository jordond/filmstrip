package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.avfoundation.internal.interop.PIXEL_BUFFER_HEIGHT_KEY
import dev.jordond.filmstrip.avfoundation.internal.interop.PIXEL_BUFFER_WIDTH_KEY
import dev.jordond.filmstrip.avfoundation.internal.interop.PIXEL_FORMAT_KEY
import dev.jordond.filmstrip.avfoundation.internal.time.toCMTime
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.color.Saturation
import dev.jordond.filmstrip.effects.color.colorMatrixOf
import dev.jordond.filmstrip.effects.color.transform
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAssetWriter
import platform.AVFoundation.AVAssetWriterInput
import platform.AVFoundation.AVAssetWriterInputPixelBufferAdaptor
import platform.AVFoundation.AVAssetWriterStatusCompleted
import platform.AVFoundation.AVAssetWriterStatusWriting
import platform.AVFoundation.AVFileTypeQuickTimeMovie
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVVideoCodecKey
import platform.AVFoundation.AVVideoCodecTypeH264
import platform.AVFoundation.AVVideoColorPrimariesKey
import platform.AVFoundation.AVVideoColorPrimaries_SMPTE_C
import platform.AVFoundation.AVVideoColorPropertiesKey
import platform.AVFoundation.AVVideoHeightKey
import platform.AVFoundation.AVVideoTransferFunctionKey
import platform.AVFoundation.AVVideoTransferFunction_ITU_R_709_2
import platform.AVFoundation.AVVideoWidthKey
import platform.AVFoundation.AVVideoYCbCrMatrixKey
import platform.AVFoundation.AVVideoYCbCrMatrix_ITU_R_601_4
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetDataSize
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferPoolCreatePixelBuffer
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferRefVar
import platform.CoreVideo.CVPixelBufferRelease
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.CoreVideo.kCVReturnSuccess
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time
import platform.posix.usleep
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Sources that are not tagged BT.709, graded through the SDR export chain.
 *
 * Each case writes a flat clip in a mid-range color, once with no color tags at all and once tagged BT.601, and
 * exports it with and without a saturation. The saturation runs on the BT.709 codes Core Image hands it, so it lands
 * where the shared matrix says only if those are the codes the file is written with. The expectation is the plain
 * export's own reading pushed through that matrix, so whatever the source's own tags did to the color is in both
 * readings and cancels.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleSourceColorSpaceTest {
  private val filmstrip = Filmstrip { avFoundationBackend() }

  @Test
  fun `grades an untagged source on bt709 codes`() =
    runTest(timeout = TIMEOUT) {
      assertGradesOnBt709("untagged", tags = null)
    }

  @Test
  fun `grades a bt601 source on bt709 codes`() =
    runTest(timeout = TIMEOUT) {
      assertGradesOnBt709(
        "bt601",
        tags =
          mapOf(
            AVVideoColorPrimariesKey to AVVideoColorPrimaries_SMPTE_C,
            AVVideoTransferFunctionKey to AVVideoTransferFunction_ITU_R_709_2,
            AVVideoYCbCrMatrixKey to AVVideoYCbCrMatrix_ITU_R_601_4,
          ),
      )
    }

  /**
   * Writes a clip carrying [tags], or none when it is null, and checks a saturation on it lands where the shared
   * matrix puts the plain export's reading in BT.709 codes.
   */
  private suspend fun assertGradesOnBt709(
    name: String,
    tags: Map<Any?, Any?>?,
  ) {
    val source = temporaryPath("$name-source")
    writeFlatClip(source, tags)

    val plain = centreOf(source, "$name-plain") {}
    val graded = centreOf(source, "$name-graded") { add(SATURATION) }
    remove(source)

    val matrix = checkNotNull(colorMatrixOf(SATURATION)) { "a saturation is not a color matrix" }
    val codes = plain.inBufferCodes()
    val expected = matrix.transform(codes[0], codes[1], codes[2]).inProbeCodes()

    withClue("$name asks for $expected from $plain") {
      (distance(expected, plain) > CHANNELS * CHANNEL_DRIFT).shouldBeTrue()
    }
    listOf(
      "red" to graded.first - expected.first,
      "green" to graded.second - expected.second,
      "blue" to graded.third - expected.third,
    ).forEach { (channel, off) ->
      withClue("$name read $graded and the matrix asks for $expected from a plain $plain, $channel off by $off") {
        (abs(off) <= CHANNEL_DRIFT).shouldBeTrue()
      }
    }
  }

  /**
   * The centre of the frame halfway through [source], exported with [onComposition] grading it.
   */
  private suspend fun centreOf(
    source: String,
    name: String,
    onComposition: EffectsBuilder.() -> Unit,
  ): Triple<Int, Int, Int> {
    val composition =
      compositionOf {
        clip(MediaSource.of(source)) { trim(Duration.ZERO, CLIP) }
        effects(onComposition)
      }
    val output = temporaryPath(name)

    withContext(Dispatchers.Default) {
      val statuses = filmstrip.export(composition, SPEC, MediaSink.of(output)).toList()
      when (val finished = statuses.last()) {
        is ExportStatus.Failure -> error(finished.error.message)
        else -> finished.shouldBeInstanceOf<ExportStatus.Success>()
      }
    }

    return frameOf(output, CLIP / 2).average(CENTRE, CENTRE).also { remove(output) }
  }

  /**
   * Writes [FRAMES] frames of [COLOR] to [path] as H.264, tagged with [tags] or with no color properties at all.
   */
  private fun writeFlatClip(
    path: String,
    tags: Map<Any?, Any?>?,
  ) {
    remove(path)
    val writer =
      AVAssetWriter.assetWriterWithURL(NSURL.fileURLWithPath(path), fileType = AVFileTypeQuickTimeMovie, error = null)
        ?: error("no writer for $path")
    val settings =
      mapOf<Any?, Any?>(
        AVVideoCodecKey to AVVideoCodecTypeH264,
        AVVideoWidthKey to WIDTH,
        AVVideoHeightKey to HEIGHT,
      )
    val input =
      AVAssetWriterInput(
        mediaType = AVMediaTypeVideo,
        outputSettings = if (tags == null) settings else settings + (AVVideoColorPropertiesKey to tags),
      )
    input.expectsMediaDataInRealTime = false
    val adaptor =
      AVAssetWriterInputPixelBufferAdaptor(
        assetWriterInput = input,
        sourcePixelBufferAttributes =
          mapOf(
            PIXEL_FORMAT_KEY to kCVPixelFormatType_32BGRA.toInt(),
            PIXEL_BUFFER_WIDTH_KEY to WIDTH,
            PIXEL_BUFFER_HEIGHT_KEY to HEIGHT,
          ),
      )

    check(writer.canAddInput(input)) { "the writer would not take a video input" }
    writer.addInput(input)
    check(writer.startWriting()) { "the writer refused to start: ${writer.error?.localizedDescription}" }
    writer.startSessionAtSourceTime(Duration.ZERO.toCMTime())

    val deadline = TimeSource.Monotonic.markNow() + WRITE_BUDGET
    repeat(FRAMES) { index ->
      while (!input.readyForMoreMediaData) {
        check(writer.status == AVAssetWriterStatusWriting) {
          "the writer stopped: ${writer.error?.localizedDescription}"
        }
        check(!deadline.hasPassedNow()) { "the writer took no frame for $WRITE_BUDGET" }
        usleep(READY_POLL_MICROS)
      }
      check(adaptor.appendFlatFrame(CLIP * index / FRAMES)) {
        "frame $index was refused: ${writer.error?.localizedDescription}"
      }
    }

    input.markAsFinished()
    writer.endSessionAtSourceTime(CLIP.toCMTime())
    val finished = dispatch_semaphore_create(0)
    writer.finishWritingWithCompletionHandler { dispatch_semaphore_signal(finished) }
    dispatch_semaphore_wait(finished, dispatch_time(DISPATCH_TIME_NOW, WRITE_BUDGET.inWholeNanoseconds))
    check(writer.status == AVAssetWriterStatusCompleted) {
      "the clip was not written: ${writer.error?.localizedDescription}"
    }
  }

  private fun AVAssetWriterInputPixelBufferAdaptor.appendFlatFrame(at: Duration): Boolean =
    memScoped {
      val pool = pixelBufferPool ?: return@memScoped false
      val holder = alloc<CVPixelBufferRefVar>()
      if (CVPixelBufferPoolCreatePixelBuffer(null, pool, holder.ptr) != kCVReturnSuccess) return@memScoped false
      val buffer = holder.value ?: return@memScoped false

      try {
        buffer.paint()
        appendPixelBuffer(buffer, withPresentationTime = at.toCMTime())
      } finally {
        CVPixelBufferRelease(buffer)
      }
    }

  private fun CVPixelBufferRef.paint() {
    check(CVPixelBufferLockBaseAddress(this, 0uL) == kCVReturnSuccess) { "could not lock a frame to paint it" }
    try {
      val bytes = CVPixelBufferGetBaseAddress(this)?.reinterpret<UByteVar>() ?: error("the frame has no memory")
      val size = CVPixelBufferGetDataSize(this).toInt()
      for (offset in 0 until size - BYTES_PER_PIXEL + 1 step BYTES_PER_PIXEL) {
        bytes[offset] = COLOR.third.toUByte()
        bytes[offset + 1] = COLOR.second.toUByte()
        bytes[offset + 2] = COLOR.first.toUByte()
        bytes[offset + 3] = OPAQUE
      }
    } finally {
      CVPixelBufferUnlockBaseAddress(this, 0uL)
    }
  }

  private fun temporaryPath(name: String): String = NSTemporaryDirectory() + "filmstrip-apple-source-$name.mov"

  private fun remove(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, error = null)
  }

  private companion object {
    val TIMEOUT = 2.seconds * 60
    val SPEC = ExportSpec(heightPx = 180, bitrate = COLOR_READ_BITRATE)
    val CLIP = 1.seconds
    const val FRAMES = 30
    const val WIDTH = 320
    const val HEIGHT = 180

    // Halfway to grey, the middle of the range rather than an end of it.
    val SATURATION = Saturation(0.5f)

    // A flat color off both ends of every channel, so the saturation moves each and none of them clips.
    val COLOR = Triple(0x66, 0x99, 0xCC)

    const val CENTRE = 0.5f
    const val CHANNELS = 3

    // A few code values of a real hardware encode of a flat color.
    const val CHANNEL_DRIFT = 6

    const val BYTES_PER_PIXEL = 4
    val OPAQUE: UByte = 0xFFu
    const val READY_POLL_MICROS = 1_000u
    val WRITE_BUDGET = 60.seconds
  }
}

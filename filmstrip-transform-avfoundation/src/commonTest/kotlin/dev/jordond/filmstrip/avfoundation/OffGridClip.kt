package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.avfoundation.internal.interop.PIXEL_BUFFER_HEIGHT_KEY
import dev.jordond.filmstrip.avfoundation.internal.interop.PIXEL_BUFFER_WIDTH_KEY
import dev.jordond.filmstrip.avfoundation.internal.interop.PIXEL_FORMAT_KEY
import dev.jordond.filmstrip.avfoundation.internal.time.MEDIA_TIMESCALE
import dev.jordond.filmstrip.avfoundation.internal.time.toCMTime
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import platform.AVFAudio.AVEncoderBitRateKey
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.AVFoundation.AVAssetWriter
import platform.AVFoundation.AVAssetWriterInput
import platform.AVFoundation.AVAssetWriterInputPixelBufferAdaptor
import platform.AVFoundation.AVAssetWriterStatusCompleted
import platform.AVFoundation.AVAssetWriterStatusWriting
import platform.AVFoundation.AVFileTypeQuickTimeMovie
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVVideoCodecKey
import platform.AVFoundation.AVVideoCodecTypeH264
import platform.AVFoundation.AVVideoColorPrimariesKey
import platform.AVFoundation.AVVideoColorPrimaries_ITU_R_709_2
import platform.AVFoundation.AVVideoColorPropertiesKey
import platform.AVFoundation.AVVideoHeightKey
import platform.AVFoundation.AVVideoTransferFunctionKey
import platform.AVFoundation.AVVideoTransferFunction_ITU_R_709_2
import platform.AVFoundation.AVVideoWidthKey
import platform.AVFoundation.AVVideoYCbCrMatrixKey
import platform.AVFoundation.AVVideoYCbCrMatrix_ITU_R_709_2
import platform.AVFoundation.mediaTimeScale
import platform.CoreAudioTypes.AudioStreamBasicDescription
import platform.CoreAudioTypes.kAudioFormatFlagIsPacked
import platform.CoreAudioTypes.kAudioFormatFlagIsSignedInteger
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMAudioFormatDescriptionCreate
import platform.CoreMedia.CMAudioFormatDescriptionRef
import platform.CoreMedia.CMAudioFormatDescriptionRefVar
import platform.CoreMedia.CMAudioSampleBufferCreateReadyWithPacketDescriptions
import platform.CoreMedia.CMBlockBufferCreateWithMemoryBlock
import platform.CoreMedia.CMBlockBufferFillDataBytes
import platform.CoreMedia.CMBlockBufferRefVar
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreMedia.CMSampleBufferRefVar
import platform.CoreMedia.CMTime
import platform.CoreMedia.kCMBlockBufferAssureMemoryNowFlag
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
import platform.Foundation.NSURL
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time
import platform.posix.usleep
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Writes a movie of [frames] flat frames at [frameRate] to [path], whose video track runs until [endsAt].
 *
 * Every frame is one flat color chosen by its own index, which [frameIndexOf] reads back, so a frame decoded out of
 * an export names the frame of this clip it came from. The session ends at [endsAt] rather than one frame after the
 * last, so the track is exactly that long and its last frame is held until then.
 *
 * @param audioUntil How long a silent audio track runs, when the movie carries one. An audio track longer than the
 *   picture makes the movie as long as its audio, the way a camera file often is.
 * @param encodesAudio Whether that silence is written as AAC, which an mp4 copy can carry, rather than as PCM.
 * @param side How wide and how tall every frame is.
 * @param timescale The timescale the video track is written at.
 * @param frameTime When each frame, counted from zero, is presented. One frame at [frameRate] apart by default.
 * @param matrix The YCbCr matrix the frames are tagged and encoded with.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun writeOffGridClip(
  path: String,
  frameRate: Int = 25,
  frames: Int = 26,
  endsAt: Duration = 1_030.milliseconds,
  audioUntil: Duration? = null,
  encodesAudio: Boolean = false,
  side: Int = CLIP_SIDE,
  timescale: Int = MEDIA_TIMESCALE,
  frameTime: (Int) -> CValue<CMTime> = { (1.seconds * it / frameRate).toCMTime() },
  matrix: String? = AVVideoYCbCrMatrix_ITU_R_709_2,
) {
  require(frames <= INDEXABLE_FRAMES) { "$frames frames is more than the $INDEXABLE_FRAMES colors an index reads as" }
  NSFileManager.defaultManager.removeItemAtPath(path, error = null)

  val writer =
    AVAssetWriter.assetWriterWithURL(NSURL.fileURLWithPath(path), fileType = AVFileTypeQuickTimeMovie, error = null)
      ?: error("no writer for $path")
  val input =
    AVAssetWriterInput(
      mediaType = AVMediaTypeVideo,
      outputSettings =
        mapOf(
          AVVideoCodecKey to AVVideoCodecTypeH264,
          AVVideoWidthKey to side,
          AVVideoHeightKey to side,
          // Tagged, so every platform decodes the colors with the matrix they were encoded with.
          AVVideoColorPropertiesKey to
            mapOf(
              AVVideoColorPrimariesKey to AVVideoColorPrimaries_ITU_R_709_2,
              AVVideoTransferFunctionKey to AVVideoTransferFunction_ITU_R_709_2,
              AVVideoYCbCrMatrixKey to matrix,
            ),
        ),
    )
  input.expectsMediaDataInRealTime = false
  input.mediaTimeScale = timescale
  val adaptor =
    AVAssetWriterInputPixelBufferAdaptor(
      assetWriterInput = input,
      sourcePixelBufferAttributes =
        mapOf(
          PIXEL_FORMAT_KEY to kCVPixelFormatType_32BGRA.toInt(),
          PIXEL_BUFFER_WIDTH_KEY to side,
          PIXEL_BUFFER_HEIGHT_KEY to side,
        ),
    )

  check(writer.canAddInput(input)) { "the writer would not take a video input" }
  writer.addInput(input)
  val audio =
    audioUntil?.let { until ->
      val (format, silence) = silenceFor(until)
      val audioInput =
        if (encodesAudio) {
          AVAssetWriterInput(mediaType = AVMediaTypeAudio, outputSettings = aacSettings())
        } else {
          AVAssetWriterInput(mediaType = AVMediaTypeAudio, outputSettings = null, sourceFormatHint = format)
        }
      CFRelease(format)
      audioInput.expectsMediaDataInRealTime = false
      check(writer.canAddInput(audioInput)) { "the writer would not take an audio input" }
      writer.addInput(audioInput)
      audioInput to silence
    }
  check(writer.startWriting()) { "the writer refused to start: ${writer.error?.localizedDescription}" }
  writer.startSessionAtSourceTime(Duration.ZERO.toCMTime())

  val deadline = TimeSource.Monotonic.markNow() + WRITE_BUDGET
  // All of the audio goes first and is finished, so the writer is never left waiting on it to interleave the frames.
  audio?.let { (audioInput, silence) ->
    val appended = audioInput.appendSampleBuffer(silence)
    CFRelease(silence)
    check(appended) { "the audio was refused: ${writer.error?.localizedDescription}" }
    audioInput.markAsFinished()
  }
  repeat(frames) { index ->
    while (!input.readyForMoreMediaData) {
      check(writer.status == AVAssetWriterStatusWriting) { "the writer stopped: ${writer.error?.localizedDescription}" }
      check(!deadline.hasPassedNow()) { "the writer took no frame for $WRITE_BUDGET" }
      usleep(READY_POLL_MICROS)
    }
    check(adaptor.appendFlatFrame(index, frameTime(index))) {
      "frame $index was refused: ${writer.error?.localizedDescription}"
    }
  }

  input.markAsFinished()
  writer.endSessionAtSourceTime(maxOf(endsAt, audioUntil ?: endsAt).toCMTime())
  val finished = dispatch_semaphore_create(0)
  writer.finishWritingWithCompletionHandler { dispatch_semaphore_signal(finished) }
  dispatch_semaphore_wait(finished, dispatch_time(DISPATCH_TIME_NOW, WRITE_BUDGET.inWholeNanoseconds))
  check(writer.status == AVAssetWriterStatusCompleted) {
    "the clip was not written: ${writer.error?.localizedDescription}"
  }
}

/**
 * Which frame of a clip [writeOffGridClip] wrote this one is, read off the color at its centre.
 */
internal fun frameIndexOf(frame: FrameProbe): Int {
  val (red, green, blue) = frame.average(0.5f, 0.5f)
  return (levelOf(red) * LEVELS + levelOf(green)) * LEVELS + levelOf(blue)
}

/**
 * The settings an audio input encodes the silence to AAC with, at the rate and channel count it is written in.
 */
private fun aacSettings(): Map<Any?, Any?> =
  mapOf(
    AVFormatIDKey to kAudioFormatMPEG4AAC,
    AVSampleRateKey to SILENCE_RATE.toDouble(),
    AVNumberOfChannelsKey to 1,
    AVEncoderBitRateKey to AAC_BITRATE,
  )

/**
 * One buffer of silent mono 16-bit PCM running for [length] from zero, and the format it is written in.
 *
 * The caller releases both.
 */
@OptIn(ExperimentalForeignApi::class)
private fun silenceFor(length: Duration): Pair<CMAudioFormatDescriptionRef, CMSampleBufferRef> =
  memScoped {
    val description =
      alloc<AudioStreamBasicDescription>().apply {
        mSampleRate = SILENCE_RATE.toDouble()
        mFormatID = kAudioFormatLinearPCM
        mFormatFlags = kAudioFormatFlagIsSignedInteger or kAudioFormatFlagIsPacked
        mBytesPerPacket = SILENCE_SAMPLE_BYTES.toUInt()
        mFramesPerPacket = 1u
        mBytesPerFrame = SILENCE_SAMPLE_BYTES.toUInt()
        mChannelsPerFrame = 1u
        mBitsPerChannel = (SILENCE_SAMPLE_BYTES * BITS_PER_BYTE).toUInt()
      }
    val format = alloc<CMAudioFormatDescriptionRefVar>()
    check(CMAudioFormatDescriptionCreate(null, description.ptr, 0u, null, 0u, null, null, format.ptr) == 0) {
      "could not describe the silence"
    }

    val samples = length.inWholeMicroseconds * SILENCE_RATE / MICROS_PER_SECOND
    val bytes = (samples * SILENCE_SAMPLE_BYTES).toULong()
    val block = alloc<CMBlockBufferRefVar>()
    val allocated =
      CMBlockBufferCreateWithMemoryBlock(
        null,
        null,
        bytes,
        null,
        null,
        0u,
        bytes,
        kCMBlockBufferAssureMemoryNowFlag,
        block.ptr,
      )
    check(allocated == 0) { "could not allocate the silence" }
    CMBlockBufferFillDataBytes(0, block.value, 0u, bytes)

    val buffer = alloc<CMSampleBufferRefVar>()
    val made =
      CMAudioSampleBufferCreateReadyWithPacketDescriptions(
        null,
        block.value,
        format.value,
        samples,
        Duration.ZERO.toCMTime(),
        null,
        buffer.ptr,
      )
    CFRelease(block.value)
    check(made == 0) { "could not wrap the silence in a sample buffer" }
    checkNotNull(format.value) to checkNotNull(buffer.value)
  }

/**
 * Appends a frame of the color [index] is written as, presented at [at].
 */
@OptIn(ExperimentalForeignApi::class)
private fun AVAssetWriterInputPixelBufferAdaptor.appendFlatFrame(
  index: Int,
  at: CValue<CMTime>,
): Boolean =
  memScoped {
    val pool = pixelBufferPool ?: return@memScoped false
    val holder = alloc<CVPixelBufferRefVar>()
    if (CVPixelBufferPoolCreatePixelBuffer(null, pool, holder.ptr) != kCVReturnSuccess) return@memScoped false
    val buffer = holder.value ?: return@memScoped false

    try {
      buffer.paint(index)
      appendPixelBuffer(buffer, withPresentationTime = at)
    } finally {
      CVPixelBufferRelease(buffer)
    }
  }

/**
 * Fills the whole buffer with the color [index] is written as, one base-[LEVELS] digit a channel.
 */
@OptIn(ExperimentalForeignApi::class)
private fun CVPixelBufferRef.paint(index: Int) {
  val red = channelOf(index / (LEVELS * LEVELS))
  val green = channelOf(index / LEVELS % LEVELS)
  val blue = channelOf(index % LEVELS)
  check(CVPixelBufferLockBaseAddress(this, 0uL) == kCVReturnSuccess) { "could not lock a frame to paint it" }
  try {
    val bytes = CVPixelBufferGetBaseAddress(this)?.reinterpret<UByteVar>() ?: error("the frame has no memory")
    val size = CVPixelBufferGetDataSize(this).toInt()
    for (offset in 0 until size - BYTES_PER_PIXEL + 1 step BYTES_PER_PIXEL) {
      bytes[offset] = blue
      bytes[offset + 1] = green
      bytes[offset + 2] = red
      bytes[offset + 3] = OPAQUE
    }
  } finally {
    CVPixelBufferUnlockBaseAddress(this, 0uL)
  }
}

private fun channelOf(digit: Int): UByte = (DARKEST + digit * LEVEL_STEP).toUByte()

private fun levelOf(channel: Int): Int =
  ((channel - DARKEST).toFloat() / LEVEL_STEP).roundToInt().coerceIn(
    0,
    LEVELS - 1,
  )

// Three levels a channel, far enough apart that neither an encode nor a color conversion moves a flat frame from one
// to the next.
private const val LEVELS = 3
private const val DARKEST = 32
private const val LEVEL_STEP = 96
private const val INDEXABLE_FRAMES = LEVELS * LEVELS * LEVELS

private const val CLIP_SIDE = 128
private const val BYTES_PER_PIXEL = 4
private val OPAQUE: UByte = 0xFFu
private const val READY_POLL_MICROS = 1_000u
private const val SILENCE_RATE = 48_000L
private const val SILENCE_SAMPLE_BYTES = 2
private const val AAC_BITRATE = 64_000
private const val BITS_PER_BYTE = 8
private const val MICROS_PER_SECOND = 1_000_000L
private val WRITE_BUDGET = 60.seconds

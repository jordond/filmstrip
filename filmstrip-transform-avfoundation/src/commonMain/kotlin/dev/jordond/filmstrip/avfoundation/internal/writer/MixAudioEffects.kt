@file:OptIn(ExperimentalForeignApi::class)

package dev.jordond.filmstrip.avfoundation.internal.writer

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import dev.jordond.filmstrip.export.AudioFormat
import dev.jordond.filmstrip.transform.internal.audio.KernelChain
import dev.jordond.filmstrip.transform.internal.audio.timelineFrameOf
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedAudioEffect
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreAudioTypes.AudioStreamBasicDescription
import platform.CoreAudioTypes.kAudioFormatFlagIsPacked
import platform.CoreAudioTypes.kAudioFormatFlagIsSignedInteger
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreMedia.CMAudioFormatDescriptionCreate
import platform.CoreMedia.CMAudioFormatDescriptionRefVar
import platform.CoreMedia.CMAudioSampleBufferCreateReadyWithPacketDescriptions
import platform.CoreMedia.CMBlockBufferCopyDataBytes
import platform.CoreMedia.CMBlockBufferCreateWithMemoryBlock
import platform.CoreMedia.CMBlockBufferRefVar
import platform.CoreMedia.CMBlockBufferReplaceDataBytes
import platform.CoreMedia.CMSampleBufferGetDataBuffer
import platform.CoreMedia.CMSampleBufferGetNumSamples
import platform.CoreMedia.CMSampleBufferGetPresentationTimeStamp
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreMedia.CMSampleBufferRefVar
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.kCMBlockBufferAssureMemoryNowFlag

/**
 * Runs the composition's audio effects over the mixed samples an export reads, between the reader and the writer.
 *
 * The mix arrives with every clip's level already applied, as interleaved 16-bit PCM at [format]. Each buffer goes
 * through one [KernelChain], which is handed the timeline position of the first buffer through [timelineFrameOf] and
 * counts on from there. What comes out is written to buffers of its own, timed by the frames handed on so far, so
 * kernels with latency leave the mix its length and its place on the timeline.
 *
 * Everything here runs on the queue the audio pump reads on.
 *
 * @param effects The effects to run, first to last.
 * @param format The rate and channel count [read] hands its samples over at.
 * @param read Takes the next mixed buffer from the reader, following the create rule, or null at the end.
 */
internal class MixAudioEffects(
  private val effects: List<ResolvedAudioEffect>,
  private val format: AudioFormat,
  private val read: () -> CMSampleBufferRef?,
) {
  private var chain: KernelChain? = null
  private var samples = ShortArray(0)
  private var opens = 0L
  private var handedOn = 0L
  private var ended = false

  /**
   * What stopped the effects running, or null while they run. The mix ends where it was set, and the run reads it to
   * fail the export.
   */
  var failure: FilmstripError? = null
    private set

  /**
   * The next buffer of the mix with the effects run over it, following the create rule, or null once the mix and
   * what the kernels held of it have both been handed on.
   *
   * A kernel that throws ends the mix here and sets [failure], since an exception leaving the pump's queue would
   * terminate the process.
   */
  fun next(): CMSampleBufferRef? =
    try {
      processed()
    } catch (broken: Exception) {
      ended = true
      failure = FilmstripError.InvalidComposition("$NOT_RUN ${broken.message ?: broken.toString()}")
      null
    }

  private fun processed(): CMSampleBufferRef? {
    while (!ended) {
      val buffer = read()
      if (buffer == null) {
        ended = true
        break
      }
      try {
        val made = process(buffer)
        if (made > 0) return bufferOf(made)
      } finally {
        CFRelease(buffer)
      }
    }
    val held = chain?.drain(samples, samples.size / format.channelCount) ?: 0
    return if (held > 0) bufferOf(held) else null
  }

  // Runs the kernels over the samples of [buffer], leaving what they made at the head of [samples].
  private fun process(buffer: CMSampleBufferRef): Int {
    val frames = CMSampleBufferGetNumSamples(buffer).toInt()
    val block = CMSampleBufferGetDataBuffer(buffer)
    if (frames == 0 || block == null) return 0
    if (samples.size < frames * format.channelCount) samples = ShortArray(frames * format.channelCount)
    val bytes = frames * format.channelCount * Short.SIZE_BYTES
    val copied = samples.usePinned { CMBlockBufferCopyDataBytes(block, 0u, bytes.convert(), it.addressOf(0)) }
    check(copied == 0) { "The mixed audio could not be read out of its buffer, status $copied." }
    return chainAt(buffer, frames).process(samples, frames)
  }

  private fun chainAt(
    buffer: CMSampleBufferRef,
    frames: Int,
  ): KernelChain =
    chain ?: run {
      opens = timelineFrameOf(CMSampleBufferGetPresentationTimeStamp(buffer).toDuration(), format.sampleRate)
      KernelChain(effects.map { it.effect.newKernel(format) }, format.channelCount, opens, frames).also { chain = it }
    }

  // A buffer of the first [frames] frames of [samples], timed by how many frames went before it.
  private fun bufferOf(frames: Int): CMSampleBufferRef? =
    memScoped {
      val bytes = frames * format.channelCount * Short.SIZE_BYTES
      val block = alloc<CMBlockBufferRefVar>()
      val description = alloc<CMAudioFormatDescriptionRefVar>()
      val buffer = alloc<CMSampleBufferRefVar>()
      try {
        var status =
          CMBlockBufferCreateWithMemoryBlock(
            structureAllocator = kCFAllocatorDefault,
            memoryBlock = null,
            blockLength = bytes.convert(),
            blockAllocator = kCFAllocatorDefault,
            customBlockSource = null,
            offsetToData = 0u,
            dataLength = bytes.convert(),
            flags = kCMBlockBufferAssureMemoryNowFlag,
            blockBufferOut = block.ptr,
          )
        check(status == 0) { "No memory for $frames frames of mixed audio, status $status." }
        status = samples.usePinned { CMBlockBufferReplaceDataBytes(it.addressOf(0), block.value, 0u, bytes.convert()) }
        check(status == 0) { "The mixed audio could not be written to its buffer, status $status." }
        status =
          CMAudioFormatDescriptionCreate(
            allocator = kCFAllocatorDefault,
            asbd = alloc<AudioStreamBasicDescription>().apply { describe() }.ptr,
            layoutSize = 0u,
            layout = null,
            magicCookieSize = 0u,
            magicCookie = null,
            extensions = null,
            formatDescriptionOut = description.ptr,
          )
        check(status == 0) { "The mixed audio's format could not be described, status $status." }
        status =
          CMAudioSampleBufferCreateReadyWithPacketDescriptions(
            allocator = kCFAllocatorDefault,
            dataBuffer = block.value,
            formatDescription = description.value,
            numSamples = frames.convert(),
            presentationTimeStamp = CMTimeMake(opens + handedOn, format.sampleRate),
            packetDescriptions = null,
            sampleBufferOut = buffer.ptr,
          )
        check(status == 0) { "The mixed audio could not be wrapped for the writer, status $status." }
      } finally {
        block.value?.let { CFRelease(it) }
        description.value?.let { CFRelease(it) }
      }
      handedOn += frames
      buffer.value
    }

  private fun AudioStreamBasicDescription.describe() {
    mSampleRate = format.sampleRate.toDouble()
    mFormatID = kAudioFormatLinearPCM
    mFormatFlags = kAudioFormatFlagIsSignedInteger or kAudioFormatFlagIsPacked
    mBytesPerPacket = (format.channelCount * Short.SIZE_BYTES).convert()
    mFramesPerPacket = 1u
    mBytesPerFrame = (format.channelCount * Short.SIZE_BYTES).convert()
    mChannelsPerFrame = format.channelCount.convert()
    mBitsPerChannel = Short.SIZE_BITS.convert()
    mReserved = 0u
  }
}

private const val NOT_RUN = "The composition's audio effects could not run over the mix."

package dev.jordond.filmstrip.avfoundation.internal

import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFoundation.AVAssetReaderTrackOutput
import platform.AudioToolbox.ExtAudioFileCreateWithURL
import platform.AudioToolbox.ExtAudioFileDispose
import platform.AudioToolbox.ExtAudioFileRef
import platform.AudioToolbox.ExtAudioFileRefVar
import platform.AudioToolbox.ExtAudioFileSetProperty
import platform.AudioToolbox.ExtAudioFileWrite
import platform.AudioToolbox.kAudioFileCAFType
import platform.AudioToolbox.kAudioFileFlags_EraseFile
import platform.AudioToolbox.kExtAudioFileProperty_ClientDataFormat
import platform.CoreAudioTypes.AudioBufferList
import platform.CoreAudioTypes.AudioStreamBasicDescription
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.CoreAudioTypes.kLinearPCMFormatFlagIsPacked
import platform.CoreAudioTypes.kLinearPCMFormatFlagIsSignedInteger
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFURLRef
import platform.CoreMedia.CMSampleBufferCopyPCMDataIntoAudioBufferList
import platform.CoreMedia.CMSampleBufferGetNumSamples
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSURL
import platform.darwin.OSStatus
import platform.posix.memcpy
import platform.posix.memset

/**
 * Hands the time pitch unit source frames as it asks for them, a decoded buffer at a time.
 *
 * The engine only calls this from inside a render call, and renders one block at a time. Past the end of the source it
 * hands over silence, which the unit needs to flush its window and which pads a source shorter than its clip.
 */
@OptIn(ExperimentalForeignApi::class)
internal class DecodedFeed(
  private val decode: AVAssetReaderTrackOutput,
  private val format: AVAudioFormat,
) {
  private var decoded = AVAudioPCMBuffer(pCMFormat = format, frameCapacity = RENDER_BLOCK_FRAMES)
  private var offset = 0
  private var ended = false

  /**
   * The status a sample buffer refused to copy with, which stops the render once the engine's call returns.
   */
  var failedStatus: Int? = null
    private set

  fun fill(
    silence: CPointer<BooleanVar>?,
    frames: UInt,
    list: CPointer<AudioBufferList>?,
  ): OSStatus {
    val output = list?.pointed ?: return NO_ERROR
    val wanted = frames.toInt()
    var filled = 0
    while (filled < wanted) {
      if (offset >= decoded.frameLength.toInt() && !nextBuffer()) break
      val take = minOf(wanted - filled, decoded.frameLength.toInt() - offset)
      val channels = decoded.floatChannelData ?: break
      for (channel in 0 until output.mNumberBuffers.toInt()) {
        val into = output.mBuffers[channel].mData?.reinterpret<FloatVar>() ?: continue
        val from = channels[channel] ?: continue
        memcpy(into + filled, from + offset, (take * Float.SIZE_BYTES).convert())
      }
      offset += take
      filled += take
    }
    if (filled < wanted) {
      for (channel in 0 until output.mNumberBuffers.toInt()) {
        val into = output.mBuffers[channel].mData?.reinterpret<FloatVar>() ?: continue
        memset(into + filled, 0, ((wanted - filled) * Float.SIZE_BYTES).convert())
      }
      if (filled == 0) silence?.pointed?.value = true
    }
    return NO_ERROR
  }

  // Copies the next non-empty sample buffer into decoded, growing it when a buffer is larger than any before it.
  private fun nextBuffer(): Boolean {
    while (!ended) {
      val buffer = decode.copyNextSampleBuffer()
      if (buffer == null) {
        ended = true
        return false
      }
      try {
        val count = CMSampleBufferGetNumSamples(buffer).toInt()
        if (count == 0) continue
        if (count.toUInt() > decoded.frameCapacity) {
          decoded = AVAudioPCMBuffer(pCMFormat = format, frameCapacity = count.toUInt())
        }
        decoded.frameLength = count.toUInt()
        val status = CMSampleBufferCopyPCMDataIntoAudioBufferList(buffer, 0, count, decoded.mutableAudioBufferList)
        if (status != NO_ERROR) {
          failedStatus = status
          ended = true
          decoded.frameLength = 0u
          return false
        }
        offset = 0
        return true
      } finally {
        CFRelease(buffer)
      }
    }
    return false
  }
}

/**
 * A CAF of 16-bit PCM written a block at a time, closed by [close] rather than whenever the object is collected, so its
 * length is on disk before an asset reads it.
 */
@OptIn(ExperimentalForeignApi::class)
internal class StretchFile(
  path: String,
  client: AVAudioFormat,
  channels: Int,
  private val source: String,
) {
  private val file: ExtAudioFileRef =
    memScoped {
      val fileFormat =
        alloc<AudioStreamBasicDescription> {
          mSampleRate = client.sampleRate
          mFormatID = kAudioFormatLinearPCM
          mFormatFlags = kLinearPCMFormatFlagIsSignedInteger or kLinearPCMFormatFlagIsPacked
          mBytesPerPacket = (channels * BYTES_PER_SAMPLE).convert()
          mFramesPerPacket = 1u
          mBytesPerFrame = (channels * BYTES_PER_SAMPLE).convert()
          mChannelsPerFrame = channels.convert()
          mBitsPerChannel = (BYTES_PER_SAMPLE * Byte.SIZE_BITS).convert()
        }
      val opened = alloc<ExtAudioFileRefVar>()
      val url: CFURLRef? = CFBridgingRetain(NSURL.fileURLWithPath(path))?.reinterpret()
      val created =
        ExtAudioFileCreateWithURL(
          url,
          kAudioFileCAFType,
          fileFormat.ptr,
          null,
          kAudioFileFlags_EraseFile,
          opened.ptr,
        )
      url?.let { CFRelease(it) }
      val file = opened.value
      if (created != NO_ERROR || file == null) throw AudioStretchFailure(refused(source, created))
      val described =
        ExtAudioFileSetProperty(
          file,
          kExtAudioFileProperty_ClientDataFormat,
          sizeOf<AudioStreamBasicDescription>().convert(),
          client.streamDescription,
        )
      if (described != NO_ERROR) {
        ExtAudioFileDispose(file)
        throw AudioStretchFailure(refused(source, described))
      }
      file
    }

  fun write(block: AVAudioPCMBuffer) {
    val status = ExtAudioFileWrite(file, block.frameLength, block.audioBufferList)
    if (status != NO_ERROR) throw AudioStretchFailure(refused(source, status))
  }

  fun close() {
    ExtAudioFileDispose(file)
  }
}

/**
 * The frames every block of a render holds.
 */
internal const val RENDER_BLOCK_FRAMES: UInt = 4_096u

/**
 * The bytes one sample of a stretched file holds.
 */
internal const val BYTES_PER_SAMPLE: Int = 2

private const val NO_ERROR = 0

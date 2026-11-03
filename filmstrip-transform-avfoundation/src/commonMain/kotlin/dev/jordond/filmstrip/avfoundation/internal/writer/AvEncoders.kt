package dev.jordond.filmstrip.avfoundation.internal.writer

import dev.jordond.filmstrip.capability.AudioEncoderCapability
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.capability.VideoEncoderCapability
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.transform.internal.DEFAULT_HDR_LADDER
import dev.jordond.filmstrip.transform.internal.HDR_PROBE_SIZE
import dev.jordond.filmstrip.transform.internal.RESOLUTION_LADDER
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CValuesRef
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.CoreFoundation.CFArrayGetCount
import platform.CoreFoundation.CFArrayGetValueAtIndex
import platform.CoreFoundation.CFArrayRefVar
import platform.CoreFoundation.CFBooleanGetValue
import platform.CoreFoundation.CFBooleanRef
import platform.CoreFoundation.CFDictionaryCreate
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFNumberGetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFNumberSInt32Type
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreMedia.kCMVideoCodecType_H264
import platform.CoreMedia.kCMVideoCodecType_HEVC
import platform.CoreVideo.kCVPixelBufferPixelFormatTypeKey
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange
import platform.VideoToolbox.VTCompressionSessionCreate
import platform.VideoToolbox.VTCompressionSessionInvalidate
import platform.VideoToolbox.VTCompressionSessionRef
import platform.VideoToolbox.VTCompressionSessionRefVar
import platform.VideoToolbox.VTCopyVideoEncoderList
import platform.VideoToolbox.VTSessionCopyProperty
import platform.VideoToolbox.VTSessionSetProperty
import platform.VideoToolbox.kVTCompressionPropertyKey_ProfileLevel
import platform.VideoToolbox.kVTCompressionPropertyKey_UsingHardwareAcceleratedVideoEncoder
import platform.VideoToolbox.kVTProfileLevel_HEVC_Main10_AutoLevel
import platform.VideoToolbox.kVTVideoEncoderList_CodecType
import platform.VideoToolbox.kVTVideoEncoderList_IsHardwareAccelerated
import platform.darwin.noErr

/**
 * Reads what this device's encoders can do, by asking VideoToolbox to open a session.
 *
 * Apple publishes no size or rate ceiling, so the only honest answer for those comes from trying.
 * Each codec walks a resolution ladder largest first and stops at the first size that opens. The
 * session is torn down once it has answered, and nothing is encoded through it. Hardware
 * acceleration is read off that same session when it will say, and falls back to
 * [VTCopyVideoEncoderList]'s encoder list when it will not, the way [hardwareFlag] reads them.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun avEncoderCapabilities(): DeviceCapabilities {
  val hardwareCodecs = hardwareAcceleratedCodecTypes()
  val video = VIDEO_CODEC_TYPES.mapNotNull { (codec, type) -> probeVideo(codec, type, type in hardwareCodecs) }

  return DeviceCapabilities(
    video = video,
    // AAC ships with the OS, so there is nothing to probe.
    audio =
      listOf(
        AudioEncoderCapability(
          codec = AudioCodec.Aac,
          sampleRates = SUPPORTED_SAMPLE_RATES,
          maxChannelCount = MAX_CHANNELS,
        ),
      ),
    // The codec a kept grade is pinned to is the planner's to name, so hdrProbeCodecType reads
    // which one of DEFAULT_HDR_LADDER this device already lists. Only a device that lists one is
    // asked whether it can actually open a Main10 session.
    supportsHdrEncoding = hdrProbeCodecType(video)?.let(::opensMain10Session) ?: false,
    concurrentSessionBudget = null,
  )
}

@OptIn(ExperimentalForeignApi::class)
private fun probeVideo(
  codec: VideoCodec,
  codecType: UInt,
  hardwareFromList: Boolean,
): VideoEncoderCapability? {
  val (largest, probe) =
    RESOLUTION_LADDER.firstNotNullOfOrNull { size -> canEncode(codecType, size)?.let { size to it } }
      ?: return null

  return VideoEncoderCapability(
    codec = codec,
    // VideoToolbox resolves the encoder from the codec type the session asks for, so filmstrip
    // never names one.
    encoderName = null,
    maxSize = largest,
    // Apple publishes neither a rate ceiling nor a bitrate ceiling.
    maxFrameRate = null,
    maxBitrate = null,
    isHardwareAccelerated = hardwareFlag(session = probe.hardwareAccelerated, listed = hardwareFromList),
    sizeAlignment = SIZE_ALIGNMENT,
  )
}

/**
 * Whether an encoder is hardware, from what the probe session reported and whether the encoder list tags its codec.
 *
 * A [session] answer wins. Without one, a [listed] codec is hardware, and anything else is null, since the list flag is
 * only ever present when true. VideoToolbox reports neither below iOS 17.4, where the session key is unavailable and
 * the list flag is absent even for a hardware encoder, nor on the iOS simulator, so null is the answer on both.
 */
internal fun hardwareFlag(
  session: Boolean?,
  listed: Boolean,
): Boolean? = session ?: true.takeIf { listed }

/**
 * The codec types VideoToolbox lists at least one hardware-accelerated encoder for.
 *
 * The fallback signal, read only when the session opened for a probe will not say whether it is
 * hardware. `VTCopyVideoEncoderList` is the only place this is published. A compression session
 * opens whichever encoder VideoToolbox picks without saying which one that was. The
 * `kVTVideoEncoderList_IsHardwareAccelerated` key is optional and only ever present when true, so a
 * codec with no hardware encoder simply never appears here.
 *
 * The key is known to be absent on iOS even for a codec whose only encoder is hardware, so a
 * missing entry there is not proof of a software-only encoder. macOS, where filmstrip's own tests
 * run, tags both variants correctly.
 */
@OptIn(ExperimentalForeignApi::class)
private fun hardwareAcceleratedCodecTypes(): Set<UInt> =
  memScoped {
    val listOut = alloc<CFArrayRefVar>()
    val status = VTCopyVideoEncoderList(null, listOut.ptr)
    val encoders = listOut.value ?: return emptySet()

    try {
      if (status != noErr.toInt()) return emptySet()

      val count = CFArrayGetCount(encoders).toInt()
      (0 until count)
        .mapNotNull { index -> CFArrayGetValueAtIndex(encoders, index.toLong())?.asDictionary() }
        .filter { it.isHardwareAccelerated() }
        .mapNotNullTo(mutableSetOf()) { it.codecType() }
    } finally {
      CFRelease(encoders)
    }
  }

@OptIn(ExperimentalForeignApi::class)
private fun COpaquePointer.asDictionary(): CFDictionaryRef = reinterpret()

@OptIn(ExperimentalForeignApi::class)
private fun CFDictionaryRef.isHardwareAccelerated(): Boolean {
  val value = CFDictionaryGetValue(this, kVTVideoEncoderList_IsHardwareAccelerated) ?: return false
  val flag: CFBooleanRef = value.reinterpret()
  return CFBooleanGetValue(flag)
}

@OptIn(ExperimentalForeignApi::class)
private fun CFDictionaryRef.codecType(): UInt? {
  val value = CFDictionaryGetValue(this, kVTVideoEncoderList_CodecType) ?: return null
  return memScoped {
    val code = alloc<IntVar>()
    if (CFNumberGetValue(value.reinterpret(), kCFNumberSInt32Type, code.ptr)) code.value.toUInt() else null
  }
}

/**
 * Opens a compression session at [size], or null when it will not open at all.
 *
 * The session answers [SessionProbe.hardwareAccelerated] itself before it is invalidated and
 * released, so the flag is read off the actual session a real export would open, not merely the
 * codec's presence in an encoder list.
 */
@OptIn(ExperimentalForeignApi::class)
private fun canEncode(
  codecType: UInt,
  size: Size,
): SessionProbe? =
  memScoped {
    val session = alloc<VTCompressionSessionRefVar>()
    val status =
      VTCompressionSessionCreate(
        allocator = null,
        width = size.width,
        height = size.height,
        codecType = codecType,
        encoderSpecification = null,
        sourceImageBufferAttributes = null,
        compressedDataAllocator = null,
        outputCallback = null,
        outputCallbackRefCon = null,
        compressionSessionOut = session.ptr as CValuesRef<VTCompressionSessionRefVar>,
      )

    val opened = session.value
    if (status != noErr.toInt() || opened == null) return@memScoped null

    val hardware = opened.usesHardwareAcceleration()
    // Create hands back a retained session, so tearing it down is invalidate followed by a
    // release. Invalidate alone frees the encoder but leaks the reference.
    VTCompressionSessionInvalidate(opened)
    CFRelease(opened)
    SessionProbe(hardware)
  }

/**
 * Whether VideoToolbox opened this session on a hardware encoder, or null when the session will
 * not say.
 *
 * `kVTCompressionPropertyKey_UsingHardwareAcceleratedVideoEncoder` is unavailable on iOS below
 * 17.4, where the query fails rather than crashing, so null is the honest answer there and the
 * caller falls back to the broader encoder list.
 */
@OptIn(ExperimentalForeignApi::class)
private fun VTCompressionSessionRef.usesHardwareAcceleration(): Boolean? =
  memScoped {
    val propertyValueOut = alloc<COpaquePointerVar>()
    val status =
      VTSessionCopyProperty(
        this@usesHardwareAcceleration,
        kVTCompressionPropertyKey_UsingHardwareAcceleratedVideoEncoder,
        null,
        propertyValueOut.ptr,
      )
    val flag = propertyValueOut.value
    if (status != noErr.toInt() || flag == null) return@memScoped null

    val hardware = CFBooleanGetValue(flag.reinterpret())
    CFRelease(flag)
    hardware
  }

private class SessionProbe(
  val hardwareAccelerated: Boolean?,
)

/**
 * The codec type to probe for HDR support, read off [DEFAULT_HDR_LADDER] against what this device
 * already lists in [video], or null when it lists none of them.
 */
internal fun hdrProbeCodecType(video: List<VideoEncoderCapability>): UInt? {
  val codec = DEFAULT_HDR_LADDER.firstOrNull { ladder -> video.any { it.codec == ladder } } ?: return null
  return VIDEO_CODEC_TYPES[codec]
}

/**
 * Opens a compression session for [codecType] at HEVC's Main10 profile, at [HDR_PROBE_SIZE], and
 * reports whether it opened.
 *
 * The source pixel buffer attributes ask for a ten-bit format too, so the session is genuinely
 * asked to carry ten bits rather than merely tagged with a profile it is free to ignore. Nothing is
 * encoded through the session, and it is invalidated and released on every path before
 * returning.
 *
 * The attributes come from [tenBitSourceAttributes], a Core Foundation dictionary iOS reads in
 * full.
 *
 * `kVTProfileLevel_HEVC_Main10_AutoLevel` is HEVC's own limit, not a filmstrip choice, so it is the
 * one constant this probe hardcodes.
 */
@OptIn(ExperimentalForeignApi::class)
private fun opensMain10Session(codecType: UInt): Boolean {
  val sourceAttributes = tenBitSourceAttributes() ?: return false

  return try {
    memScoped {
      val session = alloc<VTCompressionSessionRefVar>()
      val size = HDR_PROBE_SIZE
      val status =
        VTCompressionSessionCreate(
          allocator = null,
          width = size.width,
          height = size.height,
          codecType = codecType,
          encoderSpecification = null,
          sourceImageBufferAttributes = sourceAttributes,
          compressedDataAllocator = null,
          outputCallback = null,
          outputCallbackRefCon = null,
          compressionSessionOut = session.ptr as CValuesRef<VTCompressionSessionRefVar>,
        )

      val opened = session.value
      if (status != noErr.toInt() || opened == null) return@memScoped false

      val profileStatus =
        VTSessionSetProperty(opened, kVTCompressionPropertyKey_ProfileLevel, kVTProfileLevel_HEVC_Main10_AutoLevel)
      // Create hands back a retained session, so tearing it down is invalidate followed by a
      // release. Invalidate alone frees the encoder but leaks the reference.
      VTCompressionSessionInvalidate(opened)
      CFRelease(opened)
      profileStatus == noErr.toInt()
    }
  } finally {
    CFRelease(sourceAttributes)
  }
}

/**
 * A retained one-entry `CFDictionary` naming the ten-bit 4:2:0 pixel format, for the caller to
 * release.
 *
 * Built through Core Foundation, which both platforms read. iOS runs the encoder out of process
 * and answers 3840 to a Kotlin map bridged into an `NSDictionary` holding anything at all, BGRA and
 * eight-bit included, which reads back as a device with no Main10 encoder. macOS keeps the encoder
 * in process and takes either.
 */
@OptIn(ExperimentalForeignApi::class)
private fun tenBitSourceAttributes(): CFDictionaryRef? =
  memScoped {
    val format = alloc<UIntVar>()
    format.value = kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange
    val value = CFNumberCreate(null, kCFNumberSInt32Type, format.ptr) ?: return@memScoped null

    val keys = allocArrayOf(kCVPixelBufferPixelFormatTypeKey)
    val values = allocArrayOf(value.reinterpret<CPointed>())
    val dictionary =
      CFDictionaryCreate(
        allocator = null,
        keys = keys.reinterpret(),
        values = values.reinterpret(),
        numValues = 1,
        keyCallBacks = kCFTypeDictionaryKeyCallBacks.ptr,
        valueCallBacks = kCFTypeDictionaryValueCallBacks.ptr,
      )
    CFRelease(value)
    dictionary
  }

private val VIDEO_CODEC_TYPES: Map<VideoCodec, UInt> =
  mapOf(
    VideoCodec.H264 to kCMVideoCodecType_H264,
    VideoCodec.Hevc to kCMVideoCodecType_HEVC,
  )

private const val SIZE_ALIGNMENT = 2

internal const val MAX_CHANNELS = 2

private val SUPPORTED_SAMPLE_RATES = listOf(44_100, 48_000)

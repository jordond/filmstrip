package dev.jordond.filmstrip.avfoundation.internal

import dev.jordond.filmstrip.capability.AudioEncoderCapability
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.capability.VideoEncoderCapability
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.RenderFeature
import dev.jordond.filmstrip.export.AudioCodec
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.transform.internal.DEFAULT_HDR_LADDER
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CValuesRef
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.AVFAudio.AVEncoderBitRateKey
import platform.AVFAudio.AVFormatIDKey
import platform.AVFAudio.AVLinearPCMBitDepthKey
import platform.AVFAudio.AVLinearPCMIsBigEndianKey
import platform.AVFAudio.AVLinearPCMIsFloatKey
import platform.AVFAudio.AVLinearPCMIsNonInterleavedKey
import platform.AVFAudio.AVNumberOfChannelsKey
import platform.AVFAudio.AVSampleRateKey
import platform.AVFoundation.AVOutputSettingsAssistant
import platform.AVFoundation.AVOutputSettingsPreset1280x720
import platform.AVFoundation.AVOutputSettingsPreset1920x1080
import platform.AVFoundation.AVOutputSettingsPreset3840x2160
import platform.AVFoundation.AVOutputSettingsPreset640x480
import platform.AVFoundation.AVOutputSettingsPreset960x540
import platform.AVFoundation.AVOutputSettingsPresetHEVC1920x1080
import platform.AVFoundation.AVOutputSettingsPresetHEVC3840x2160
import platform.AVFoundation.AVVideoAverageBitRateKey
import platform.AVFoundation.AVVideoCodecKey
import platform.AVFoundation.AVVideoCodecTypeH264
import platform.AVFoundation.AVVideoCodecTypeHEVC
import platform.AVFoundation.AVVideoColorPrimariesKey
import platform.AVFoundation.AVVideoColorPrimaries_ITU_R_2020
import platform.AVFoundation.AVVideoColorPrimaries_ITU_R_709_2
import platform.AVFoundation.AVVideoColorPropertiesKey
import platform.AVFoundation.AVVideoCompressionPropertiesKey
import platform.AVFoundation.AVVideoExpectedSourceFrameRateKey
import platform.AVFoundation.AVVideoHeightKey
import platform.AVFoundation.AVVideoProfileLevelKey
import platform.AVFoundation.AVVideoTransferFunctionKey
import platform.AVFoundation.AVVideoTransferFunction_ITU_R_2100_HLG
import platform.AVFoundation.AVVideoTransferFunction_ITU_R_709_2
import platform.AVFoundation.AVVideoTransferFunction_SMPTE_ST_2084_PQ
import platform.AVFoundation.AVVideoWidthKey
import platform.AVFoundation.AVVideoYCbCrMatrixKey
import platform.AVFoundation.AVVideoYCbCrMatrix_ITU_R_2020
import platform.AVFoundation.AVVideoYCbCrMatrix_ITU_R_709_2
import platform.AVFoundation.setSourceVideoFormat
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.CoreFoundation.CFArrayGetCount
import platform.CoreFoundation.CFArrayGetValueAtIndex
import platform.CoreFoundation.CFArrayRefVar
import platform.CoreFoundation.CFBooleanGetValue
import platform.CoreFoundation.CFBooleanRef
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFNumberGetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFNumberSInt32Type
import platform.CoreMedia.CMVideoFormatDescriptionRef
import platform.CoreMedia.kCMVideoCodecType_H264
import platform.CoreMedia.kCMVideoCodecType_HEVC
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSDictionary
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
 * session is invalidated once it has answered, and nothing is encoded through it. Hardware
 * acceleration is read off that same session when it will say, and falls back to
 * [VTCopyVideoEncoderList]'s encoder list when it will not.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun appleEncoderCapabilities(): DeviceCapabilities {
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
    // which one of DEFAULT_HDR_LADDER this device already lists rather than naming HEVC again
    // here. Only a device that lists one is asked whether it can actually open a Main10 session.
    supportsHdrEncoding = hdrProbeCodecType(video)?.let(::opensMain10Session) ?: false,
    concurrentSessionBudget = null,
  )
}

/**
 * What Core Image can draw for an offline export.
 */
internal fun coreImageRenderCapabilities(
  outputSize: Size,
  hdr: Boolean,
): RenderCapabilities =
  RenderCapabilities(
    api = RenderApi.Metal,
    supportsFragmentShader = true,
    supportsComputeShader = true,
    // True only once the grade reaches the encoder, which is what [hdr] answers.
    supportsHdr = hdr,
    colorSpaces = setOf(ColorSpace.Bt709, ColorSpace.Bt601, ColorSpace.Bt2020),
    maxTextureSize = maxOf(outputSize.width, outputSize.height, MAX_TEXTURE_FLOOR),
    features =
      buildSet {
        add(RenderFeature.MultipassRender)
        add(RenderFeature.TextRendering)
        // Core Image converts into whatever space the output names, so tone mapping is always on
        // offer. It is not [hdr], which is true when a grade is being kept and so when nothing is
        // being tone-mapped at all.
        add(RenderFeature.HdrToneMapping)
      },
  )

/**
 * The settings the video writer input is opened with.
 *
 * Seeded from [AVOutputSettingsAssistant], which is where Apple publishes a sane bitrate and
 * profile level for a given frame. Everything the plan settles then overwrites the seed, so what is
 * left of it is only ever the parts filmstrip has no opinion about.
 *
 * @param sourceFormat The first clip's video format, when there is one. It lets the assistant seed
 *   against the real source, not the preset's nominal one.
 * @param encodesHdr Whether an HDR grade reaches the encoder. False for an SDR source whatever the
 *   plan asked for, since there is no grade to keep.
 * @param transfer The source's HDR transfer function, which decides HLG against PQ.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun videoWriterSettings(
  output: OutputFormat,
  encodesHdr: Boolean,
  transfer: HdrTransfer?,
  sourceFormat: CMVideoFormatDescriptionRef?,
): Map<Any?, Any?> {
  val seed = assistantSettings(output, sourceFormat)
  val compression =
    (seed[AVVideoCompressionPropertiesKey] as? Map<*, *>).orEmptyMap() +
      buildMap {
        output.bitrate?.let { put(AVVideoAverageBitRateKey, it.bitsPerSecond) }
        output.frameRate?.takeIf { it > 0 }?.let { put(AVVideoExpectedSourceFrameRateKey, it) }
        // Ten-bit needs a profile that can carry it, and VideoToolbox's constants are CFStringRef,
        // which does not bridge into a Kotlin dictionary. The literal is the documented value of
        // kVTProfileLevel_HEVC_Main10_AutoLevel.
        if (encodesHdr) put(AVVideoProfileLevelKey, HEVC_MAIN_10)
      }

  return seed +
    mapOf(
      AVVideoCodecKey to output.videoCodec.toAvCodecType(),
      AVVideoWidthKey to output.size.width,
      AVVideoHeightKey to output.size.height,
      AVVideoCompressionPropertiesKey to compression,
      AVVideoColorPropertiesKey to colorProperties(encodesHdr, transfer),
    )
}

/**
 * The settings the audio writer input is opened with, or null when the output carries no audio.
 */
internal fun audioWriterSettings(output: OutputFormat): Map<Any?, Any?>? {
  if (output.audioCodec == AudioCodec.None) return null
  val format = output.audioFormat ?: return null

  return mapOf(
    // Auto and Alac never reach here. The planner resolves both against the device's real encoder
    // list, and AAC is the only one this platform advertises.
    AVFormatIDKey to kAudioFormatMPEG4AAC,
    AVSampleRateKey to format.sampleRate.toDouble(),
    AVNumberOfChannelsKey to format.channelCount,
    AVEncoderBitRateKey to AAC_BITRATE,
  )
}

/**
 * The settings the audio reader output is opened with.
 *
 * Uncompressed, because an [platform.AVFoundation.AVAudioMix] applies its gain to samples and there
 * are none to apply it to while the stream is still AAC.
 */
internal fun pcmReaderSettings(output: OutputFormat): Map<Any?, Any?> {
  val format = output.audioFormat

  return mapOf(
    AVFormatIDKey to kAudioFormatLinearPCM,
    AVSampleRateKey to (format?.sampleRate ?: DEFAULT_SAMPLE_RATE).toDouble(),
    AVNumberOfChannelsKey to (format?.channelCount ?: MAX_CHANNELS),
    AVLinearPCMBitDepthKey to PCM_BIT_DEPTH,
    AVLinearPCMIsFloatKey to false,
    AVLinearPCMIsBigEndianKey to false,
    AVLinearPCMIsNonInterleavedKey to false,
  )
}

/**
 * The pixel format the video reader output hands the writer.
 *
 * Ten-bit biplanar for an HDR grade, which BGRA cannot carry, and BGRA otherwise because that is
 * what Core Image renders into fastest.
 */
internal fun videoReaderSettings(encodesHdr: Boolean): Map<Any?, Any?> {
  val format = if (encodesHdr) kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange else kCVPixelFormatType_32BGRA
  // The CoreVideo key constants are CFStringRef and do not bridge into a Kotlin map as NSString
  // keys. AVFoundation raises on the whole dictionary as carrying no recognised keys. The literal
  // is the documented value of kCVPixelBufferPixelFormatTypeKey.
  return mapOf(PIXEL_FORMAT_KEY to format.toInt())
}

/**
 * The codec type string the writer names, which is always one the device advertised.
 */
internal fun VideoCodec.toAvCodecType(): String =
  when (this) {
    VideoCodec.H264 -> checkNotNull(AVVideoCodecTypeH264)
    VideoCodec.Hevc -> checkNotNull(AVVideoCodecTypeHEVC)
    // The planner resolves Auto against the device's real encoder list before a plan exists, and
    // AVFoundation's ladder never offers Vp9, Vp8 or Av1, so none of them should reach here.
    // Erroring rather than falling through to H264 is what makes a ladder change that forgets this
    // function fail loudly.
    VideoCodec.Auto, VideoCodec.Vp9, VideoCodec.Vp8, VideoCodec.Av1 -> error("AVFoundation has no encoder for $this.")
  }

@OptIn(ExperimentalForeignApi::class)
private fun assistantSettings(
  output: OutputFormat,
  sourceFormat: CMVideoFormatDescriptionRef?,
): Map<Any?, Any?> {
  val assistant =
    AVOutputSettingsAssistant.outputSettingsAssistantWithPreset(output.preset())
      ?: return emptyMap()
  if (sourceFormat != null) assistant.setSourceVideoFormat(sourceFormat)

  return assistant.videoSettings.orEmptyMap()
}

/**
 * The preset whose frame is closest to the output's without being smaller than it.
 *
 * Only the seed depends on this. Width and height are overwritten from the plan either way, so a
 * frame that sits between two presets takes the larger one's bitrate.
 */
private fun OutputFormat.preset(): String? {
  val side = maxOf(size.width, size.height)
  val hevc = videoCodec == VideoCodec.Hevc

  return when {
    hevc && side > FULL_HD_SIDE -> AVOutputSettingsPresetHEVC3840x2160
    hevc -> AVOutputSettingsPresetHEVC1920x1080
    side > FULL_HD_SIDE -> AVOutputSettingsPreset3840x2160
    side > HD_SIDE -> AVOutputSettingsPreset1920x1080
    side > QHD_SIDE -> AVOutputSettingsPreset1280x720
    side > SD_SIDE -> AVOutputSettingsPreset960x540
    else -> AVOutputSettingsPreset640x480
  }
}

/**
 * The colour target the encoder is pinned to.
 *
 * Tone mapping falls out of this instead of being a named mode. Core Image renders into the working
 * space the context was built with, and naming Rec.709 here is what makes a BT.2020 source land
 * inside it.
 */
private fun colorProperties(
  encodesHdr: Boolean,
  transfer: HdrTransfer?,
): Map<Any?, Any?> =
  if (encodesHdr) {
    mapOf(
      AVVideoColorPrimariesKey to AVVideoColorPrimaries_ITU_R_2020,
      AVVideoTransferFunctionKey to
        if (transfer == HdrTransfer.Pq) {
          AVVideoTransferFunction_SMPTE_ST_2084_PQ
        } else {
          AVVideoTransferFunction_ITU_R_2100_HLG
        },
      AVVideoYCbCrMatrixKey to AVVideoYCbCrMatrix_ITU_R_2020,
    )
  } else {
    mapOf(
      AVVideoColorPrimariesKey to AVVideoColorPrimaries_ITU_R_709_2,
      AVVideoTransferFunctionKey to AVVideoTransferFunction_ITU_R_709_2,
      AVVideoYCbCrMatrixKey to AVVideoYCbCrMatrix_ITU_R_709_2,
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
    // Falls back to the encoder list flag when the session will not report.
    isHardwareAccelerated = probe.hardwareAccelerated ?: hardwareFromList,
    sizeAlignment = SIZE_ALIGNMENT,
  )
}

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
 * The session answers [SessionProbe.hardwareAccelerated] itself before it is invalidated, so the
 * flag is read off the actual session a real export would open, not merely the codec's presence in
 * an encoder list.
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
    VTCompressionSessionInvalidate(opened)
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
 * Opens a compression session for [codecType] at HEVC's Main10 profile, at the smallest rung of
 * [RESOLUTION_LADDER], and reports whether it opened.
 *
 * The source pixel buffer attributes ask for a ten-bit format too, so the session is genuinely
 * asked to carry ten bits rather than merely tagged with a profile it is free to ignore. Nothing is
 * encoded through the session, and it is invalidated and released on every path before
 * returning.
 *
 * `kVTProfileLevel_HEVC_Main10_AutoLevel` is HEVC's own limit, not a filmstrip choice, so it is the
 * one constant this probe hardcodes.
 */
@OptIn(ExperimentalForeignApi::class)
private fun opensMain10Session(codecType: UInt): Boolean {
  val attributes = mapOf<Any?, Any?>(PIXEL_FORMAT_KEY to kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange.toInt())
  val sourceAttributes = CFBridgingRetain(attributes as NSDictionary) ?: return false

  return try {
    memScoped {
      val session = alloc<VTCompressionSessionRefVar>()
      val size = RESOLUTION_LADDER.last()
      val status =
        VTCompressionSessionCreate(
          allocator = null,
          width = size.width,
          height = size.height,
          codecType = codecType,
          encoderSpecification = null,
          sourceImageBufferAttributes = sourceAttributes.asDictionary(),
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

@Suppress("UNCHECKED_CAST")
private fun Map<*, *>?.orEmptyMap(): Map<Any?, Any?> = this as? Map<Any?, Any?> ?: emptyMap()

private val RESOLUTION_LADDER =
  listOf(
    Size(3840, 2160),
    Size(1920, 1080),
    Size(1280, 720),
    Size(640, 480),
  )

private val VIDEO_CODEC_TYPES: Map<VideoCodec, UInt> =
  mapOf(
    VideoCodec.H264 to kCMVideoCodecType_H264,
    VideoCodec.Hevc to kCMVideoCodecType_HEVC,
  )

private const val SIZE_ALIGNMENT = 2

private const val MAX_TEXTURE_FLOOR = 8_192
private const val MAX_CHANNELS = 2
private const val DEFAULT_SAMPLE_RATE = 44_100
private const val PCM_BIT_DEPTH = 16
private const val AAC_BITRATE = 128_000

private const val FULL_HD_SIDE = 1_920
private const val HD_SIDE = 1_280
private const val QHD_SIDE = 960
private const val SD_SIDE = 640

private const val HEVC_MAIN_10 = "HEVC_Main10_AutoLevel"
private const val PIXEL_FORMAT_KEY = "PixelFormatType"

private val SUPPORTED_SAMPLE_RATES = listOf(44_100, 48_000)
